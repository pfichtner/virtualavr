package com.github.pfichtner.virtualavr;

import java.net.URI;
import java.util.List;
import java.util.Objects;
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
		public String pin;
		public Object state;

		public static Predicate<PinState> switchedOn(int pin) {
			return stateOfPinIs("D" + pin, true);
		}

		public static Predicate<PinState> switchedOff(int pin) {
			return stateOfPinIs("D" + pin, false);
		}

		public static Predicate<PinState> stateOfPinIs(String pin, boolean state) {
			return p -> Objects.equals(p.pin, pin) && Objects.equals(p.state, state);
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

	private static class FakePinState {
		FakePinState(String pin, Object state) {
			this.pin = pin;
			this.state = state;
		}

		public String type = "fakePinState";
		public String pin;
		public Object state;
	}

	public void setPinState(String pin, boolean state) {
		send(gson.toJson(new FakePinState(pin, state)));
	}

	@Override
	public void onError(Exception ex) {
	}

	@Override
	public void onClose(int code, String reason, boolean remote) {
	}

}