import pytest
import docker
from collections import deque
import threading
import websocket
import json
import time
import queue
import os

# Constants used in the test
REF_PIN = "A0"
VALUE_PIN = "A1"
GREEN_LED = "D10"
YELLOW_LED = "D11"
RED_LED = "D12"

class WebSocketListener:
    def __init__(self, ws):
        """
        Initialize the WebSocketListener with the WebSocket connection
        :param ws: WebSocket object
        """
        self.ws = ws
        self.queue = queue.Queue()
        self.running = True

    def start(self):
        self.thread = threading.Thread(target=self._listen, daemon=True)
        self.thread.start()

    def _listen(self):
        while self.running:
            try:
                message = self.ws.recv()
                parsed_message = json.loads(message)
                print(f"Background listener received: {parsed_message}")
                self.queue.put(parsed_message)
            except Exception as e:
                print(f"Error in WebSocket listener: {e}")
                self.running = False

    def stop(self):
        self.running = False
        self.thread.join()

    def get_message(self, timeout=None):
        try:
            return self.queue.get(timeout=timeout)
        except queue.Empty:
            return None

    def get_all_messages(self):
        """
        Retrieve all messages from the queue without removing them.
        Returns a list of messages in the queue.
        """
        return list(self.queue.queue)  # Return a copy of the current messages in the queue

@pytest.fixture
def docker_container():
    client = docker.from_env()

    print("Starting Docker container...")
    docker_image_tag = os.getenv("DOCKER_IMAGE_TAG", "latest")
    script_dir = os.path.dirname(os.path.abspath(__file__))
    container = client.containers.run(
	f"pfichtner/virtualavr:{docker_image_tag}",
        detach=True,
        auto_remove=True,
        ports={"8080/tcp": None},  # Map container port to a random free port on the host
        volumes={script_dir: {"bind": "/sketch", "mode": "ro"}},
        environment={"FILENAME": "trafficlight.ino"}
    )

    # Retrieve the dynamically assigned host port
    container.reload()  # Ensure the latest state of the container
    host_port = container.attrs['NetworkSettings']['Ports']['8080/tcp'][0]['HostPort']
    print(f"Docker container is running with WebSocket bound to host port {host_port}")

    ws_url = f"ws://localhost:{host_port}"
    yield ws_url

    container.remove(force=True)

@pytest.fixture
def ws_listener(docker_container):
    """
    Starts a global WebSocket listener after the Docker container is ready.
    Retries connection until the WebSocket server is reachable.
    """
    ws_url = docker_container

    # Retry connection to WebSocket
    for _ in range(20):
        try:
            ws = websocket.create_connection(ws_url, timeout=5)
            print(f"WebSocket connection established to {ws_url}")
            break
        except Exception as e:
            print(f"Retrying WebSocket connection: {e}")
            time.sleep(1)
    else:
        pytest.fail("Failed to establish WebSocket connection after retries")

    # Start the WebSocket listener
    listener = WebSocketListener(ws)
    listener.start()

    yield listener

    # Stop the listener and close the WebSocket
    listener.stop()
    ws.close()

def send_ws_message(listener, message=None):
    """
    Send a WebSocket message using the listener's WebSocket instance.
    """
    if message:
        listener.ws.send(json.dumps(message))
        print(f"Sent WebSocket message: {json.dumps(message)}")


def wait_for_ws_message(listener, pin, expected_state, timeout=20):
    """
    Check the last message of a specific pin in the WebSocket listener's buffer.
    If it does not match or no message for the pin is found, wait for it until the timeout.

    Args:
        listener: The WebSocket listener instance.
        pin: The pin to look for in the messages.
        expected_state: The expected state of the pin.
        timeout: The maximum time to wait for the message.

    Raises:
        AssertionError: If the expected state for the pin is not received within the timeout.
    """
    start_time = time.time()

    while time.time() - start_time < timeout:
        # Filter messages related to the specified pin
        pin_messages = [msg for msg in listener.get_all_messages() if msg.get("pin") == pin]

        if pin_messages:
            last_message = pin_messages[-1]
            if last_message.get("state") == expected_state:
                print(f"Last message for pin {pin} has the expected state: {expected_state}")
                return last_message

        time.sleep(0.1)

    pytest.fail(f"No message for pin {pin} with the expected state {expected_state} received within {timeout} seconds.")


def set_pin_mode(ws, pin, mode):
    send_ws_message(ws, {"type": "pinMode", "pin": pin, "mode": mode})


def pin_state(pin, state):
    return {"type": "pinState", "pin": pin, "state": state}


def test_valueEqualsIs90PercentOfRef_GreenLedIsOn(ws_listener):
    set_pin_mode(ws_listener, GREEN_LED, "digital")
    set_pin_mode(ws_listener, YELLOW_LED, "digital")
    set_pin_mode(ws_listener, RED_LED, "digital")

    ref = 1000
    send_ws_message(ws_listener, pin_state(REF_PIN, ref))
    send_ws_message(ws_listener, pin_state(VALUE_PIN, int(ref * 0.9)))

    wait_for_ws_message(ws_listener, GREEN_LED, True)
    wait_for_ws_message(ws_listener, YELLOW_LED, False)
    wait_for_ws_message(ws_listener, RED_LED, False)


def test_valueGreaterThenRef_RedLedIsOn(ws_listener):
    set_pin_mode(ws_listener, GREEN_LED, "digital")
    set_pin_mode(ws_listener, YELLOW_LED, "digital")
    set_pin_mode(ws_listener, RED_LED, "digital")

    someValue = 1023
    send_ws_message(ws_listener, pin_state(REF_PIN, someValue - 1))
    send_ws_message(ws_listener, pin_state(VALUE_PIN, someValue))

    wait_for_ws_message(ws_listener, GREEN_LED, False)
    wait_for_ws_message(ws_listener, YELLOW_LED, False)
    wait_for_ws_message(ws_listener, RED_LED, True)


def test_valueGreaterWithin90Percent_YellowLedIsOn(ws_listener):
    set_pin_mode(ws_listener, GREEN_LED, "digital")
    set_pin_mode(ws_listener, YELLOW_LED, "digital")
    set_pin_mode(ws_listener, RED_LED, "digital")

    ref = 1000
    send_ws_message(ws_listener, pin_state(REF_PIN, ref))
    send_ws_message(ws_listener, pin_state(VALUE_PIN, int(ref * 0.9 + 1)))

    wait_for_ws_message(ws_listener, GREEN_LED, False)
    wait_for_ws_message(ws_listener, YELLOW_LED, True)
    wait_for_ws_message(ws_listener, RED_LED, False)
