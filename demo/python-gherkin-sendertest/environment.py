import time
import os
import docker
from websocket_listener import WebSocketListener
import logging

# Setup logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

def before_scenario(context, scenario):
    # Start Docker container
    client = docker.from_env()

    sketch_path = "rf-send/rf-send.ino"
    sketch_dir, sketch_file = os.path.split(sketch_path)
    
    docker_image_tag = os.getenv("DOCKER_IMAGE_TAG", "latest")
    context.container = client.containers.run(
        f"pfichtner/virtualavr:{docker_image_tag}",
        detach=True,
        auto_remove=False,          # auto_remove should be False to allow explicit removal
        ports={"8080/tcp": None},   # map container port to a random free port on the host
        volumes={
            os.path.abspath(sketch_dir): {"bind": "/sketch", "mode": "ro"}, 
            "/dev/": {"bind": "/dev/", "mode": "rw"}
        },
        environment={
            "FILENAME": sketch_file,
            "BUILD_EXTRA_FLAGS": "-DPUBLISH_ONLY_EVERY_X_MS=2500"
        }
    )

    # Wait for container to be ready and retrieve the dynamic port
    context.container.reload()
    ports = context.container.attrs['NetworkSettings']['Ports'].get('8080/tcp')
    if not ports or not ports[0].get('HostPort'):
        raise RuntimeError("Failed to retrieve the host port for the WebSocket connection.")
    host_port = ports[0]['HostPort']
    ws_url = f"ws://localhost:{host_port}"
    logger.info(f"WebSocket URL: {ws_url}")

    # Start the WebSocket listener with retries
    context.listener = WebSocketListener(ws_url, max_retries=20, retry_interval=1)
    context.listener.start()
    logger.info("WebSocket listener started.")

def after_scenario(context, scenario):
    # Stop the WebSocket listener if it was started
    if getattr(context, 'listener', None):
        try:
            context.listener.stop()
            logger.info("WebSocket listener stopped.")
        except Exception as e:
            logger.error(f"Error stopping WebSocket listener: {e}")

    # Stop and remove Docker container
    if getattr(context, 'container', None):
        try:
            context.container.stop()
            context.container.remove(force=True)
            logger.info("Docker container stopped and removed successfully.")
        except Exception as e:
            logger.error(f"Error stopping/removing Docker container: {e}")
