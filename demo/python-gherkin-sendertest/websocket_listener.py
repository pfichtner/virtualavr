import websocket
import threading
import queue
import json
import time

class WebSocketListener:
    def __init__(self, ws_url, max_retries=20, retry_interval=1):
        """
        Initialize the WebSocketListener with the WebSocket URL and retry settings.
        :param ws_url: URL of the WebSocket server
        :param max_retries: Maximum number of retries for the initial connection
        :param retry_interval: Time (in seconds) between retry attempts
        """
        self.ws_url = ws_url
        self.ws = None
        self.queue = queue.Queue()
        self.running = False
        self.max_retries = max_retries
        self.retry_interval = retry_interval

    def start(self):
        """
        Start the WebSocket connection and listener in a background thread with retries.
        """
        self.running = True
        for attempt in range(1, self.max_retries + 1):
            try:
                # sleep before first try since the websocket server start needs some time
                if attempt < self.max_retries:
                    time.sleep(self.retry_interval)
                else:
                    print("Max retries reached. Failed to establish WebSocket connection.")
                    self.running = False
                    return
                print(f"Attempting to connect to WebSocket ({attempt}/{self.max_retries})...")
                self.ws = websocket.create_connection(self.ws_url, timeout=5)
                print(f"WebSocket connection established to {self.ws_url}")
                break
            except Exception as e:
                print(f"Connection attempt {attempt} failed: {e}")

        self.thread = threading.Thread(target=self._listen, daemon=True)
        self.thread.start()

    def _listen(self):
        """
        Continuously listen for WebSocket messages and add them to the queue.
        """
        while self.running:
            try:
                message = self.ws.recv()
                if not message:
                    continue

                try:
                    parsed_message = json.loads(message)
                    print(f"Background listener received: {parsed_message}")
                    self.queue.put(parsed_message)
                except json.JSONDecodeError as e:
                    print(f"Received invalid JSON message: {message}, error: {e}")

            except websocket.WebSocketTimeoutException:
                print("WebSocket recv timed out.")
            except websocket.WebSocketConnectionClosedException:
                print("WebSocket connection closed.")
                self.running = False
            except Exception as e:
                print(f"Error in WebSocket listener: {e}")
                self.running = False

    def stop(self):
        """
        Stop the WebSocket listener and close the WebSocket connection.
        """
        if not self.running:
            return

        self.running = False
        try:
            if self.ws:
                self.ws.close()
                print("WebSocket connection closed.")
        except Exception as e:
            print(f"Error closing WebSocket connection: {e}")

        if self.thread:
            self.thread.join()
            print("WebSocket listener thread stopped.")

    def get_message(self, timeout=None):
        """
        Get the next message from the queue.
        :param timeout: Maximum time to wait for a message (in seconds)
        :return: The next message or None if the timeout expires
        """
        try:
            return self.queue.get(timeout=timeout)
        except queue.Empty:
            return None

    def get_all_messages(self):
        """
        Get all messages from the queue without removing them.
        :return: A list of all messages in the queue
        """
        return list(self.queue.queue)
