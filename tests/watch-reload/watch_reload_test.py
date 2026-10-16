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
import re
import threading
import time

import docker
import pytest
import websocket

pytestmark = pytest.mark.timeout(360)

DOCKER_IMAGE_TAG = os.getenv("DOCKER_IMAGE_TAG", "latest")
IMAGE = f"pfichtner/virtualavr:{DOCKER_IMAGE_TAG}"

# The sketch drives a single pin. The pin is a #define so the test can change
# just the define (and with it the running firmware) instead of rewriting the
# whole sketch. Initially it drives pin 8, the reload switches it to pin 9.
SKETCH = """
#define PIN 8
void setup() { pinMode(PIN, OUTPUT); }
void loop() {
  digitalWrite(PIN, HIGH);
  delay(200);
  digitalWrite(PIN, LOW);
  delay(200);
}
"""

PIN_DEFINE = re.compile(r"#define PIN \d+")


def set_pin(sketch_file, pin):
    """Change the pin the sketch drives by rewriting its #define."""
    content = sketch_file.read_text()
    updated, replacements = PIN_DEFINE.subn(f"#define PIN {pin}", content)
    if replacements != 1:
        raise AssertionError(f"Expected exactly one '#define PIN' in {sketch_file}")
    sketch_file.write_text(updated)


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

    def wait_for_disconnect(self, timeout):
        """Wait until the server closes the connection (e.g. on reload)."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if not self.running:
                return True
            time.sleep(0.1)
        return False

    def probe_pin(self, pin, timeout=30):
        """Subscribe to a pin and wait until it is driven high."""
        self.send({"type": "pinMode", "pin": str(pin), "mode": "digital"})
        return self.wait_for_pin_state(pin, True, timeout)

    def stop(self):
        self.running = False
        if self.thread:
            self.thread.join(timeout=5)
        if self.ws:
            try:
                self.ws.close()
            except Exception:  # noqa: BLE001
                pass


@pytest.fixture
def watch_container(tmp_path):
    client = docker.from_env()

    sketch_dir = tmp_path
    (sketch_dir / "sketch.ino").write_text(SKETCH)

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
    ws_url = watch_container["ws_url"]
    sketch_file = watch_container["sketch_file"]

    # Initial firmware drives pin 8.
    listener = WebSocketListener(ws_url)
    listener.connect(retries=120, delay=1)
    try:
        assert listener.probe_pin("8", timeout=30), \
            "Initial firmware (must drive pin 8) is not running"
    finally:
        listener.stop()

    # Change the sketch on the host (mounted read/write into the container).
    time.sleep(1.1)  # ensure the modification time changes
    set_pin(sketch_file, 9)

    # The reloaded firmware drives pin 9. Probing the not-yet-killed old
    # simulator yields no pin-9 activity, so keep reconnecting until the new
    # simulator answers: the pin-9 probe is the proof of the reload.
    deadline = time.time() + 120
    reloaded = False
    while time.time() < deadline and not reloaded:
        listener = WebSocketListener(ws_url)
        try:
            listener.connect(retries=3, delay=1)
            reloaded = listener.probe_pin("9", timeout=5)
        except ConnectionError:
            pass
        finally:
            listener.stop()
    assert reloaded, "Reloaded firmware (must drive pin 9) is not running"


def test_watch_disabled_does_not_reload(tmp_path):
    client = docker.from_env()

    (tmp_path / "sketch.ino").write_text(SKETCH)

    container = client.containers.run(
        IMAGE,
        detach=True,
        auto_remove=False,
        ports={"8080/tcp": None},
        volumes={str(tmp_path): {"bind": "/sketch", "mode": "rw"}},
        environment={"FILENAME": "sketch.ino", "WATCH": "false"},
    )

    try:
        container.reload()
        host_port = container.attrs["NetworkSettings"]["Ports"]["8080/tcp"][0]["HostPort"]
        ws_url = f"ws://localhost:{host_port}"

        listener = WebSocketListener(ws_url)
        listener.connect(retries=120, delay=1)
        try:
            assert listener.probe_pin("8", timeout=30), \
                "Initial firmware (must drive pin 8) is not running"
            # Subscribe to the would-be reloaded pin: it must never be driven high.
            listener.send({"type": "pinMode", "pin": "9", "mode": "digital"})

            time.sleep(1.1)
            set_pin(tmp_path / "sketch.ino", 9)

            assert not listener.wait_for_pin_state("9", True, timeout=10), \
                "WATCH=false must not reload the sketch (pin 9 must not be driven)"
            assert not listener.wait_for_disconnect(0.5), \
                "WATCH=false must not restart the simulator"
            assert listener.wait_for_pin_state("8", True, timeout=10), \
                "Original firmware must keep running (pin 8 must keep toggling)"
        finally:
            listener.stop()
    finally:
        print("Container logs:\n" + container.logs().decode("utf-8", errors="replace"))
        container.stop(timeout=10)
        container.remove(force=True)
