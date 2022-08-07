package com.github.pfichtner.virtualavr;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.util.stream.Collectors.toMap;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BinaryOperator;
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

	public static class PinState implements Predicate<PinState> {

		private String pin;
		private Object state;

		public PinState(String pin, Object state) {
			this.pin = pin;
			this.state = state;
		}

		public String getPin() {
			return pin;
		}

		public Object getState() {
			return state;
		}

		public static PinState on(int pin) {
			return on("D" + pin);
		}

		public static PinState on(String pin) {
			return stateOfPinIs(pin, TRUE);
		}

		public static PinState off(int pin) {
			return off("D" + pin);
		}

		public static PinState off(String pin) {
			return stateOfPinIs(pin, FALSE);
		}

		public static PinState stateOfPinIs(String pin, Boolean state) {
			return new PinState(pin, state);
		}

		@Override
		public boolean test(PinState other) {
			return Objects.equals(other.pin, pin) && Objects.equals(other.state, state);
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
		try {
			connectBlocking();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public void addPinStateListeners(Listener<PinState> listener) {
		pinStateListeners.add(listener);
	}

	public List<PinState> pinStates() {
		return pinStates;
	}

	public Map<String, Object> lastStates() {
		return pinStates().stream().collect(toMap(s -> s.pin, s -> s.state, lastWins()));
	}

	private static BinaryOperator<Object> lastWins() {
		return (e0, e1) -> e1;
	}

	public VirtualAvrConnection clearStates() {
		pinStates.clear();
		return this;
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

	@SuppressWarnings("unused")
	private static class SetPinState {

		private SetPinState(String pin, Object state) {
			this.pin = pin;
			this.state = state;
		}

		public String type = "pinState";
		public String pin;
		public Object state;
	}

	public VirtualAvrConnection pinState(String pin, boolean state) {
		send(gson.toJson(new SetPinState(pin, state)));
		return this;
	}

	public VirtualAvrConnection pinState(String pin, int state) {
		send(gson.toJson(new SetPinState(pin, state)));
		return this;
	}

	@Override
	public void onError(Exception ex) {
	}

	@Override
	public void onClose(int code, String reason, boolean remote) {
	}

}