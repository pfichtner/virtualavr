package com.ardulink.sketch;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.testcontainers.containers.GenericContainer;

import com.google.gson.Gson;

public class VirtualAvrConnection extends WebSocketClient implements AutoCloseable {

	public interface Listener<T> {
		void accept(T t);
	}

	private final Gson gson = new Gson();
	private final List<Listener<PinState>> pinStateListeners = new CopyOnWriteArrayList<>();
	private final List<PinState> pinStates = new CopyOnWriteArrayList<>();

	public static class PinState {
		public int pin;
		public int state;

		public static Predicate<PinState> switchedOn(int pin) {
			return stateOfPinIs(pin, 1);
		}

		public static Predicate<PinState> switchedOff(int pin) {
			return stateOfPinIs(pin, 0);
		}

		public static Predicate<PinState> stateOfPinIs(int pin, int state) {
			return p -> p.pin == pin && p.state == state;
		}

	}

	public static VirtualAvrConnection connectionToVirtualAvr(GenericContainer<?> container) {
		VirtualAvrConnection connection = new VirtualAvrConnection(
				URI.create("ws://localhost:" + container.getFirstMappedPort()));
		connection.addPinStateListeners(pinState -> System.out.println("Pin " + pinState.pin + " = " + pinState.state));
		return connection;
	}

	public VirtualAvrConnection(URI serverUri) {
		super(serverUri);
		addPinStateListeners(this.pinStates::add);
		connect();
	}

	public void addPinStateListeners(Listener<PinState> listener) {
		pinStateListeners.add(listener);
	}

	public List<PinState> pinStates() {
		return pinStates;
	}

	@Override
	public void onOpen(ServerHandshake handshakedata) {
	}

	@Override
	public void onMessage(String message) {
		PinState pinState = gson.fromJson(message, PinState.class);
		for (Listener<PinState> listener : pinStateListeners) {
			listener.accept(pinState);
		}
	}

	@Override
	public void onError(Exception ex) {
	}

	@Override
	public void onClose(int code, String reason, boolean remote) {
	}

}