package com.github.pfichtner.virtualavr;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.util.stream.Collectors.toMap;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import java.lang.reflect.Type;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BinaryOperator;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.testcontainers.containers.GenericContainer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;

public class VirtualAvrConnection extends WebSocketClient implements AutoCloseable {

	public enum PinReportMode {
		ANALOG("analog"), DIGITAL("digital"), NONE("none");

		private String modeName;

		private PinReportMode(String message) {
			this.modeName = message;
		}
	}

	public interface Listener<T> {
		void accept(T t);
	}

	private final Gson gson = new GsonBuilder().registerTypeAdapter(PinState.class, pinStateDeserializer()).create();

	private static JsonDeserializer<PinState> pinStateDeserializer() {
		return new JsonDeserializer<PinState>() {
			@Override
			public PinState deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
					throws JsonParseException {
				JsonObject object = json.getAsJsonObject();
				String pin = object.get("pin").getAsString();
				JsonPrimitive state = object.get("state").getAsJsonPrimitive();
				return state.isBoolean() //
						? new PinState(pin, state.getAsBoolean())
						: new PinState(pin, state.getAsInt());
			}
		};
	}

	private final List<Listener<PinState>> pinStateListeners = new CopyOnWriteArrayList<>();
	private final List<Listener<SerialDebug>> serialDebugListeners = new CopyOnWriteArrayList<>();
	private final List<Listener<CommandReply>> commandReplyListeners = new CopyOnWriteArrayList<>();
	private final List<PinState> pinStates = new CopyOnWriteArrayList<>();
	private boolean debugSerial;

	public static class PinState {

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

		public static PinState stateIsOn(int pin) {
			return stateIsOn("D" + pin);
		}

		public static PinState stateIsOn(String pin) {
			return stateOfPinIs(pin, TRUE);
		}

		public static PinState stateIsOff(int pin) {
			return stateIsOff("D" + pin);
		}

		public static PinState stateIsOff(String pin) {
			return stateOfPinIs(pin, FALSE);
		}

		public static PinState stateOfPinIs(String pin, Boolean state) {
			return new PinState(pin, state);
		}

		public static PinState stateOfPinIs(String pin, Integer value) {
			return new PinState(pin, value);
		}

		@Override
		public int hashCode() {
			return Objects.hash(pin, state);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			PinState other = (PinState) obj;
			return Objects.equals(pin, other.pin) && Objects.equals(state, other.state);
		}

		@Override
		public String toString() {
			return "PinState [pin=" + pin + ", state=" + state + "]";
		}

	}

	public static class SerialDebug {

		public static enum Direction {
			RX, TX;
		}

		private Direction direction;
		private byte[] bytes;

		public Direction direction() {
			return direction;
		}

		public byte[] bytes() {
			return bytes;
		}

	}

	public static class CommandReply {
		UUID replyId;

		public UUID replyId() {
			return replyId;
		}
	}

	private VirtualAvrConnection sendAndWaitForReply(WithReplyId messageToSend) {
		AtomicBoolean replyReceived = new AtomicBoolean();
		Listener<CommandReply> listener = r -> {
			if (Objects.equals(messageToSend.replyId(), r.replyId())) {
				replyReceived.set(true);
			}
		};
		addCommandReplyListener(listener);
		try {
			send(gson.toJson(messageToSend));
			await().untilTrue(replyReceived);
		} finally {
			removeCommandReplyListener(listener);
		}
		return this;
	}

	public static VirtualAvrConnection connectionToVirtualAvr(GenericContainer<?> container) {
		VirtualAvrConnection connection = new VirtualAvrConnection(
				URI.create("ws://localhost:" + container.getFirstMappedPort()));
		connection.addPinStateListener(
				pinState -> System.out.println("Pin " + pinState.getPin() + " = " + pinState.getState()));
		return connection;
	}

	public VirtualAvrConnection(URI serverUri) {
		super(serverUri);
		addPinStateListener(this.pinStates::add);
		try {
			connectBlocking();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public VirtualAvrConnection addPinStateListener(Listener<PinState> listener) {
		pinStateListeners.add(listener);
		return this;
	}

	public VirtualAvrConnection removePinStateListener(Listener<PinState> listener) {
		pinStateListeners.remove(listener);
		return this;
	}

	public VirtualAvrConnection addSerialDebugListener(Listener<SerialDebug> listener) {
		serialDebugListeners.add(listener);
		return serialDebugListenersChanged();
	}

	public VirtualAvrConnection removeSerialDebugListener(Listener<SerialDebug> listener) {
		serialDebugListeners.remove(listener);
		return serialDebugListenersChanged();
	}

	private VirtualAvrConnection serialDebugListenersChanged() {
		return debugSerial(!serialDebugListeners.isEmpty());
	}

	public VirtualAvrConnection addCommandReplyListener(Listener<CommandReply> listener) {
		commandReplyListeners.add(listener);
		return this;
	}

	public VirtualAvrConnection removeCommandReplyListener(Listener<CommandReply> listener) {
		commandReplyListeners.remove(listener);
		return this;
	}

	public List<PinState> pinStates() {
		return pinStates;
	}

	public Map<String, Object> lastStates() {
		return pinStates().stream().collect(toMap(PinState::getPin, PinState::getState, lastWins()));
	}

	private static BinaryOperator<Object> lastWins() {
		return (first, last) -> last;
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
		Map<?, ?> json = gson.fromJson(message, Map.class);
		if (isResponse(json)) {
			callAccept(commandReplyListeners, message, CommandReply.class);
		} else {
			String type = String.valueOf(json.get("type"));
			if ("pinState".equals(type)) {
				callAccept(pinStateListeners, message, PinState.class);
			} else if ("serialDebug".equals(type)) {
				callAccept(serialDebugListeners, message, SerialDebug.class);
			}
		}
	}

	private boolean isResponse(Map<?, ?> json) {
		return json.get("replyId") != null && json.get("executed") != null;
	}

	private <T> void callAccept(List<Listener<T>> listeners, String message, Class<T> clazz) {
		callAccept(listeners, gson.fromJson(message, clazz));
	}

	private static <T> void callAccept(List<Listener<T>> listeners, T message) {
		for (Listener<T> listener : listeners) {
			listener.accept(message);
		}
	}

	private static class WithReplyId {

		private UUID replyId = UUID.randomUUID();

		public UUID replyId() {
			return replyId;
		}
	}

	@SuppressWarnings("unused")
	private static class SetPinState extends WithReplyId {

		private SetPinState(String pin, Object state) {
			this.pin = pin;
			this.state = state;
		}

		private String type = "pinState";
		private String pin;
		private Object state;
	}

	public VirtualAvrConnection pinState(String pin, boolean state) {
		return sendAndWaitForReply(new SetPinState(pin, state));
	}

	public VirtualAvrConnection pinState(String pin, int state) {
		return sendAndWaitForReply(new SetPinState(pin, state));
	}

	@SuppressWarnings("unused")
	private static class SetPinReportMode extends WithReplyId {

		private String type = "pinMode";
		private String pin;
		private String mode;

		private SetPinReportMode(String pin, PinReportMode mode) {
			this.pin = pin;
			this.mode = mode.modeName;
		}

	}

	@SuppressWarnings("unused")
	private static class SetSerialDebug extends WithReplyId {

		private String type = "serialDebug";
		private boolean state;

		private SetSerialDebug(boolean state) {
			this.state = state;
		}

	}

	public VirtualAvrConnection pinReportMode(String pin, PinReportMode mode) {
		return sendAndWaitForReply(new SetPinReportMode(pin, mode));
	}

	private VirtualAvrConnection debugSerial(boolean state) {
		if (state != debugSerial) {
			VirtualAvrConnection connection = sendAndWaitForReply(new SetSerialDebug(state));
			debugSerial = state;
			return connection;
		}
		return this;
	}

	@Override
	public void onError(Exception ex) {
	}

	@Override
	public void onClose(int code, String reason, boolean remote) {
	}

}