import pytest
import docker
import websocket
import json
import time
import os
import queue
import threading
import uuid


class WebSocketListener:
    def __init__(self, ws_url):
        self.ws_url = ws_url
        self.ws = None
        self.queue = queue.Queue()
        self.running = True
        self.thread = None

    def connect(self, token=None, retries=20, delay=1):
        url = f"{self.ws_url}/?token={token}" if token else self.ws_url
        last_error = None
        for _ in range(retries):
            try:
                self.ws = websocket.create_connection(url, timeout=5)
                break
            except Exception as e:
                last_error = e
                time.sleep(delay)
        else:
            raise ConnectionError(f"Failed to connect to {url} after {retries} retries: {last_error}")
        self.thread = threading.Thread(target=self._listen, daemon=True)
        self.thread.start()
        return self.ws

    def _listen(self):
        while self.running:
            try:
                message = self.ws.recv()
                parsed_message = json.loads(message)
                print(f"WebSocket listener received: {parsed_message}")
                self.queue.put(parsed_message)
            except Exception as e:
                print(f"WebSocket listener error: {e}")
                self.running = False
                break

    def stop(self):
        self.running = False
        if self.thread:
            self.thread.join(timeout=5)
        if self.ws:
            self.ws.close()

    def get_message(self, timeout=1):
        try:
            return self.queue.get(timeout=timeout)
        except queue.Empty:
            return None

    def get_all_messages(self):
        return list(self.queue.queue)


def connect_with_retry(ws_url, token=None, retries=20, delay=1):
    """Connect to the WebSocket server, retrying until it is reachable."""
    url = f"{ws_url}/?token={token}" if token else ws_url
    last_error = None
    for _ in range(retries):
        try:
            return websocket.create_connection(url, timeout=5)
        except Exception as e:
            last_error = e
            time.sleep(delay)
    raise ConnectionError(f"Failed to connect to {url} after {retries} retries: {last_error}")


def assert_connection_rejected(ws):
    """Assert the server closed the connection instead of serving it.

    The WebSocket handshake succeeds even for a rejected token, the server
    terminates the connection afterwards, so the next receive must fail.
    A receive timeout means the connection was *not* rejected.
    """
    ws.settimeout(5)
    with pytest.raises(Exception) as exc_info:
        ws.recv()
    assert not isinstance(exc_info.value, websocket.WebSocketTimeoutException), \
        "Connection was not rejected (receive timed out)"
    print(f"Connection rejected as expected: {exc_info.value}")


def send_and_wait_reply(listener, timeout=5):
    """Send a message and wait for its reply to prove the connection works."""
    reply_id = str(uuid.uuid4())
    listener.ws.send(json.dumps({"type": "control", "action": "pause", "replyId": reply_id}))
    deadline = time.time() + timeout
    while time.time() < deadline:
        for msg in listener.get_all_messages():
            if msg.get("replyId") == reply_id and msg.get("executed"):
                return msg
        time.sleep(0.05)
    pytest.fail(f"No reply for replyId {reply_id} received within {timeout} seconds")


def wait_for_log(container, expected, timeout=5):
    """Wait until the container logs contain the expected text."""
    logs = ""
    deadline = time.time() + timeout
    while time.time() < deadline:
        logs = container.logs().decode('utf-8')
        if expected in logs:
            return
        time.sleep(0.2)
    pytest.fail(f"Expected log message not found within {timeout}s. Logs: {logs}")


def create_container(token=None):
    """Helper to create a Docker container with optional WS_TOKEN"""
    client = docker.from_env()

    sketch_path = os.getenv("SKETCH_FILE")
    if not sketch_path:
        raise ValueError("Environment variable 'SKETCH_FILE' is not set.")
    sketch_dir, sketch_file = os.path.split(sketch_path)

    print("Starting Docker container...")
    docker_image_tag = os.getenv("DOCKER_IMAGE_TAG", "latest")

    env = {"FILENAME": sketch_file}
    if token is not None:
        env["WS_TOKEN"] = token

    container = client.containers.run(
        f"pfichtner/virtualavr:{docker_image_tag}",
        detach=True,
        auto_remove=False,
        ports={"8080/tcp": None},
        volumes={os.path.abspath(sketch_dir): {"bind": "/sketch", "mode": "ro"}},
        environment=env
    )

    container.reload()
    host_port = container.attrs['NetworkSettings']['Ports']['8080/tcp'][0]['HostPort']
    print(f"Docker container is running with WebSocket bound to host port {host_port}")

    ws_url = f"ws://localhost:{host_port}"

    return {
        "container": container,
        "ws_url": ws_url,
        "host_port": host_port
    }


def cleanup_container(container_info):
    """Clean up Docker container"""
    try:
        container_info["container"].stop()
        container_info["container"].remove(force=True)
    except Exception:
        pass


class TestWebSocketAuth:
    """Test WebSocket authentication"""

    def test_auth_disabled_no_token(self):
        """Test that connection works when WS_TOKEN is not set (backward compatibility)"""
        auth = create_container(token=None)
        try:
            ws_listener = WebSocketListener(auth["ws_url"])
            ws_listener.connect()  # No token

            # The connection must carry traffic, not just complete the handshake
            send_and_wait_reply(ws_listener)
            ws_listener.stop()
        finally:
            cleanup_container(auth)

    def test_auth_enabled_wrong_token(self):
        """Test that connection is rejected when wrong token is provided"""
        token = "valid-token-123"
        auth = create_container(token=token)
        try:
            ws = connect_with_retry(auth["ws_url"], token="wrong-token-456")
            assert_connection_rejected(ws)
            wait_for_log(auth["container"], "WebSocket connection rejected: invalid token")
        finally:
            cleanup_container(auth)

    def test_auth_enabled_correct_token(self):
        """Test that connection succeeds when correct token is provided"""
        token = "secret-token-456"
        auth = create_container(token=token)
        try:
            ws_listener = WebSocketListener(auth["ws_url"])
            ws_listener.connect(token=token)

            # The connection must carry traffic, not just complete the handshake
            send_and_wait_reply(ws_listener)
            ws_listener.stop()
        finally:
            cleanup_container(auth)

    def test_auth_enabled_no_token_rejected(self):
        """Test that connection is rejected when WS_TOKEN is set but none provided"""
        token = "required-token-789"
        auth = create_container(token=token)
        try:
            ws = connect_with_retry(auth["ws_url"])  # No token provided
            assert_connection_rejected(ws)
            wait_for_log(auth["container"], "WebSocket connection rejected: invalid token")
        finally:
            cleanup_container(auth)
