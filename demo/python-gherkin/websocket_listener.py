import websocket
import threading
import queue
import json

class WebSocketListener:
    def __init__(self, ws):
        """
        Initialize the WebSocketListener with the WebSocket connection.
        :param ws: WebSocket object
        """
        self.ws = ws
        self.queue = queue.Queue()
        self.running = True

    def start(self):
        """
        Start a background thread that listens for WebSocket messages.
        """
        self.thread = threading.Thread(target=self._listen, daemon=True)
        self.thread.start()

    def _listen(self):
        """
        Continuously listen for WebSocket messages and add them to the queue.
        """
        while self.running:
            try:
                # Receive a message from the WebSocket
                message = self.ws.recv()
                # Parse the message as JSON
                parsed_message = json.loads(message)
                # Print the message (for debugging)
                print(f"Background listener received: {parsed_message}")
                # Add the message to the queue
                self.queue.put(parsed_message)
            except Exception as e:
                # Handle any errors that occur while listening
                print(f"Error in WebSocket listener: {e}")
                self.running = False

    def stop(self):
        """
        Stop the WebSocket listener and close the WebSocket connection.
        """
        self.running = False
        self.thread.join()

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
        return list(self.queue.queue)  # Return a copy of the current messages in the queue

