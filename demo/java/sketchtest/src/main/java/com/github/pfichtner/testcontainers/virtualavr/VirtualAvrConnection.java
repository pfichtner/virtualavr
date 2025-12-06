package com.github.pfichtner.testcontainers.virtualavr;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.lang.String.format;
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

	private static final class PinStateJsonDeserializer implements JsonDeserializer<PinState> {
		@Override
		public PinState deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
				throws JsonParseException {
			JsonObject object = json.getAsJsonObject();
			String pin = object.get("pin").getAsString();
			JsonPrimitive state = object.get("state").getAsJsonPrimitive();
			return createPinState(pin, state).withCpuTime(object.get("cpuTime").getAsDouble());
		}

		private PinState createPinState(String pin, JsonPrimitive state) {
			return state.isBoolean() //
					? new PinState(pin, state.getAsBoolean())
					: new PinState(pin, state.getAsInt());
		}
	}

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

	private final Gson gson = new GsonBuilder().registerTypeAdapter(PinState.class, new PinStateJsonDeserializer())
			.create();

	private final List<Listener<PinState>> pinStateListeners = new CopyOnWriteArrayList<>();
	private final List<Listener<SerialDebug>> serialDebugListeners = new CopyOnWriteArrayList<>();
	private final List<Listener<CommandReply>> commandReplyListeners = new CopyOnWriteArrayList<>();
	private final List<PinState> pinStates = new CopyOnWriteArrayList<>();
	private boolean debugSerial;

	public static class PinState {

		private final String pin;
		private final Object state;
		private final double cpuTime;

		public PinState(String pin, Object state) {
			this(pin, state, 0);
		}

		public PinState(String pin, Object state, double cpuTime) {
			this.pin = pin;
			this.state = state;
			this.cpuTime = cpuTime;
		}

		public PinState withCpuTime(double cpuTime) {
			return new PinState(pin, state, cpuTime);
		}

		public String getPin() {
			return pin;
		}

		public Object getState() {
			return state;
		}

		public double getCpuTime() {
			return cpuTime;
		}

		public static PinState stateIsOn(int pin) {
			return stateIsOn(pin);
		}

		public static PinState stateIsOn(String pin) {
			return stateOfPinIs(pin, TRUE);
		}

		public static PinState stateIsOff(int pin) {
			return stateIsOff(pin);
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
			// ignore cpuTime
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
			// ignore cpuTime
			return Objects.equals(pin, other.pin) && Objects.equals(state, other.state);
		}

		@Override
		public String toString() {
			return format("PinState [pin=%s, state=%s, cpuTime=%f]", pin, state, cpuTime);
		}

	}

	public static class SerialDebug {

		public enum Direction {
			RX, TX;
		}

		private final Direction direction;
		private final byte[] bytes;

		public SerialDebug(Direction direction, byte[] bytes) {
			this.direction = direction;
			this.bytes = bytes;
		}

		public Direction direction() {
			return direction;
		}

		public byte[] bytes() {
			return bytes;
		}

	}

	public static class CommandReply {

		private final UUID replyId;

		public CommandReply(UUID replyId) {
			this.replyId = replyId;
		}

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
		connection.addPinStateListener(p -> System.out.format("Pin %s = %s\n", p.getPin(), p.getState()));
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

	private static <T> BinaryOperator<T> lastWins() {
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
		if (isDeprecated(json)) {
			return;
		}
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

	private static boolean isDeprecated(Map<?, ?> json) {
		return json.get("deprecated") != null;
	}

	private static boolean isResponse(Map<?, ?> json) {
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

		private final UUID replyId = UUID.randomUUID();

		public UUID replyId() {
			return replyId;
		}
	}

	@SuppressWarnings("unused")
	private static class Control extends WithReplyId {

		private static final Control PAUSE = new Control("pause");
		private static final Control UNPAUSE = new Control("unpause");

		private final String type = "control";
		private final String action;

		private Control(String action) {
			this.action = action;
		}

	}

	@SuppressWarnings("unused")
	private static class SetPinState extends WithReplyId {

		private final String type = "pinState";
		private final String pin;
		private final Object state;

		private SetPinState(String pin, Object state) {
			this.pin = pin;
			this.state = state;
		}

	}

	public VirtualAvrConnection pinState(String pin, boolean state) {
		return sendAndWaitForReply(new SetPinState(pin, state));
	}

	public VirtualAvrConnection pinState(String pin, int state) {
		return sendAndWaitForReply(new SetPinState(pin, state));
	}

	@SuppressWarnings("unused")
	private static class SetPinReportMode extends WithReplyId {

		private final String type = "pinMode";
		private final String pin;
		private final String mode;

		private SetPinReportMode(String pin, PinReportMode mode) {
			this.pin = pin;
			this.mode = mode.modeName;
		}

	}

	@SuppressWarnings("unused")
	private static class SetSerialDebug extends WithReplyId {

		private final String type = "serialDebug";
		private final boolean state;

		private SetSerialDebug(boolean state) {
			this.state = state;
		}

	}

	public VirtualAvrConnection pinReportMode(String pin, PinReportMode mode) {
		return sendAndWaitForReply(new SetPinReportMode(pin, mode));
	}

	public VirtualAvrConnection pause() {
		return sendAndWaitForReply(Control.PAUSE);
	}

	public VirtualAvrConnection unpause() {
		return sendAndWaitForReply(Control.UNPAUSE);
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