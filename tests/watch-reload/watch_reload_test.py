"""Integration test for the WATCH/auto-reload feature.

Unlike the host-side bats tests, this runs against the built Docker image so it
exercises the real socat -> node(virtualavr.js) process chain and the mounted
/sketch volume that the reload logic depends on.

The test starts a container with WATCH=true, changes the sketch on the host and
asserts that the entrypoint recompiles the sketch and starts a fresh simulator
running the new firmware. The new firmware is detected by observing a pin that
is only driven by the reloaded sketch.
"""

import json
import os
import queue
import threading
import time

import docker
import pytest
import websocket

pytestmark = pytest.mark.timeout(360)

DOCKER_IMAGE_TAG = os.getenv("DOCKER_IMAGE_TAG", "latest")
IMAGE = f"pfichtner/virtualavr:{DOCKER_IMAGE_TAG}"

# v1 drives pin 8, v2 drives pin 9. Observing the pin proves which firmware runs.
SKETCH_V1 = """
void setup() { pinMode(8, OUTPUT); }
void loop() {
  digitalWrite(8, HIGH);
  delay(200);
  digitalWrite(8, LOW);
  delay(200);
}
"""

SKETCH_V2 = """
void setup() { pinMode(9, OUTPUT); }
void loop() {
  digitalWrite(9, HIGH);
  delay(200);
  digitalWrite(9, LOW);
  delay(200);
}
"""


class WebSocketListener:
    def __init__(self, ws_url):
        self.ws_url = ws_url
        self.ws = None
        self.queue = queue.Queue()
        self.running = True
        self.thread = None

    def connect(self, retries=30, delay=1):
        last_error = None
        for _ in range(retries):
            try:
                self.ws = websocket.create_connection(self.ws_url, timeout=5)
                break
            except Exception as e:  # noqa: BLE001 - retry on any connection error
                last_error = e
                time.sleep(delay)
        else:
            raise ConnectionError(
                f"Failed to connect to {self.ws_url} after {retries} retries: {last_error}"
            )
        self.thread = threading.Thread(target=self._listen, daemon=True)
        self.thread.start()
        return self.ws

    def _listen(self):
        while self.running:
            try:
                self.queue.put(json.loads(self.ws.recv()))
            except Exception:  # noqa: BLE001 - connection closed on reload
                self.running = False
                break

    def send(self, message):
        self.ws.send(json.dumps(message))

    def wait_for_pin_state(self, pin, state, timeout=30):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                message = self.queue.get(timeout=0.2)
            except queue.Empty:
                continue
            if (
                message.get("type") == "pinState"
                and message.get("pin") == pin
                and message.get("state") == state
            ):
                return True
        return False

    def stop(self):
        self.running = False
        if self.thread:
            self.thread.join(timeout=5)
        if self.ws:
            try:
                self.ws.close()
            except Exception:  # noqa: BLE001
                pass


def wait_for_log(container, expected, count=1, timeout=120):
    """Wait until the container logs contain `expected` at least `count` times."""
    deadline = time.time() + timeout
    logs = ""
    while time.time() < deadline:
        logs = container.logs().decode("utf-8", errors="replace")
        if logs.count(expected) >= count:
            return logs
        time.sleep(0.5)
    pytest.fail(
        f"Expected {count}x '{expected}' in container logs within {timeout}s.\nLogs:\n{logs}"
    )


@pytest.fixture
def watch_container(tmp_path):
    client = docker.from_env()

    sketch_dir = tmp_path
    (sketch_dir / "sketch.ino").write_text(SKETCH_V1)

    container = client.containers.run(
        IMAGE,
        detach=True,
        auto_remove=False,
        ports={"8080/tcp": None},
        volumes={str(sketch_dir): {"bind": "/sketch", "mode": "rw"}},
        environment={
            "FILENAME": "sketch.ino",
            "WATCH": "true",
            "WATCH_INTERVAL": "1",
        },
    )

    try:
        container.reload()
        host_port = container.attrs["NetworkSettings"]["Ports"]["8080/tcp"][0]["HostPort"]
        ws_url = f"ws://localhost:{host_port}"
        print(f"Container running, WebSocket on {ws_url}")

        # Wait for the initial firmware to be compiled and started.
        wait_for_log(container, "Starting simulator...", count=1, timeout=120)

        yield {
            "container": container,
            "ws_url": ws_url,
            "sketch_file": sketch_dir / "sketch.ino",
        }
    finally:
        print("Container logs:\n" + container.logs().decode("utf-8", errors="replace"))
        container.stop(timeout=10)
        container.remove(force=True)


def test_sketch_change_is_recompiled_and_reloaded(watch_container):
    container = watch_container["container"]
    ws_url = watch_container["ws_url"]
    sketch_file = watch_container["sketch_file"]

    # Initial firmware (v1) drives pin 8.
    listener = WebSocketListener(ws_url)
    listener.connect()
    try:
        listener.send({"type": "pinMode", "pin": "8", "mode": "digital"})
        assert listener.wait_for_pin_state("8", True, timeout=30), \
            "Initial firmware (must drive pin 8) is not running"
    finally:
        listener.stop()

    # Change the sketch on the host (mounted read/write into the container).
    time.sleep(1.1)  # ensure the modification time changes
    sketch_file.write_text(SKETCH_V2)

    wait_for_log(container, "Detected changes in /sketch. Recompiling...", timeout=30)
    # A second "Starting simulator..." proves the simulator was restarted.
    wait_for_log(container, "Starting simulator...", count=2, timeout=120)

    # The reloaded firmware (v2) drives pin 9.
    listener = WebSocketListener(ws_url)
    listener.connect()
    try:
        listener.send({"type": "pinMode", "pin": "9", "mode": "digital"})
        assert listener.wait_for_pin_state("9", True, timeout=30), \
            "Reloaded firmware (must drive pin 9) is not running"
    finally:
        listener.stop()


def test_watch_disabled_does_not_reload(tmp_path):
    client = docker.from_env()

    (tmp_path / "sketch.ino").write_text(SKETCH_V1)

    container = client.containers.run(
        IMAGE,
        detach=True,
        auto_remove=False,
        ports={"8080/tcp": None},
        volumes={str(tmp_path): {"bind": "/sketch", "mode": "rw"}},
        environment={"FILENAME": "sketch.ino", "WATCH": "false"},
    )

    try:
        wait_for_log(container, "Starting simulator...", count=1, timeout=120)

        time.sleep(1.1)
        (tmp_path / "sketch.ino").write_text(SKETCH_V2)
        time.sleep(10)

        logs = container.logs().decode("utf-8", errors="replace")
        assert "Detected changes" not in logs, \
            "WATCH=false must not trigger a reload"
        assert logs.count("Starting simulator...") == 1, \
            "WATCH=false must start the simulator exactly once"
    finally:
        print("Container logs:\n" + container.logs().decode("utf-8", errors="replace"))
        container.stop(timeout=10)
        container.remove(force=True)
