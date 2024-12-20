import time
import docker
from websocket import create_connection
from websocket_listener import WebSocketListener
import os

def before_all(context):
    # Start Docker container
    client = docker.from_env()

    # Ensure SKETCH_FILE environment variable is set for the sketch file location
    sketch_path = os.getenv("SKETCH_FILE")
    if not sketch_path:
        raise ValueError("Environment variable 'SKETCH_FILE' is not set.")
    
    sketch_dir, sketch_file = os.path.split(sketch_path)
    
    docker_image_tag = os.getenv("DOCKER_IMAGE_TAG", "latest")
    context.container = client.containers.run(
        f"pfichtner/virtualavr:{docker_image_tag}",
        detach=True,
        auto_remove=False,  # auto_remove should be False to allow explicit removal
        ports={"8080/tcp": None},  # Map container port to a random free port on the host
        volumes={os.path.abspath(sketch_dir): {"bind": "/sketch", "mode": "ro"}},
        environment={"FILENAME": sketch_file}
    )

    # Wait for container to be ready and retrieve the dynamic port
    context.container.reload()
    host_port = context.container.attrs['NetworkSettings']['Ports']['8080/tcp'][0]['HostPort']
    context.ws_url = f"ws://localhost:{host_port}"
    print(f"Docker container started. WebSocket URL: {context.ws_url}")

    # Set up WebSocket connection
    context.ws = None
    for _ in range(20):
        try:
            context.ws = create_connection(context.ws_url, timeout=5)
            break
        except Exception as e:
            time.sleep(1)
    if not context.ws:
        raise RuntimeError("Failed to establish WebSocket connection after retries")

    # Start the WebSocket listener
    context.listener = WebSocketListener(context.ws)
    context.listener.start()

def after_all(context):
    # Clean up WebSocket connection and Docker container
    if context.listener:
        context.listener.stop()
    if context.ws:
        context.ws.close()

    # Stop and remove the container after tests are done
    if hasattr(context, 'container') and context.container:
        context.container.stop()
        context.container.remove(force=True)
        print("Docker container stopped and removed successfully.")
