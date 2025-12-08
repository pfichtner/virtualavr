package com.github.pfichtner.testcontainers.virtualavr;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;

public class DefaultVirtualAvrConnection extends WebSocketClient implements VirtualAvrConnection, AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(VirtualAvrConnection.class);

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

	private final Gson gson = new GsonBuilder().registerTypeAdapter(PinState.class, new PinStateJsonDeserializer())
			.create();

	private final List<VirtualAvrConnection.Listener<PinState>> pinStateListeners = new CopyOnWriteArrayList<>();
	private final List<VirtualAvrConnection.Listener<SerialDebug>> serialDebugListeners = new CopyOnWriteArrayList<>();
	private final List<VirtualAvrConnection.Listener<CommandReply>> commandReplyListeners = new CopyOnWriteArrayList<>();
	private final List<PinState> pinStates = new CopyOnWriteArrayList<>();
	private boolean debugSerial;

	private VirtualAvrConnection sendAndWaitForReply(WithReplyId messageToSend) {
		AtomicBoolean replyReceived = new AtomicBoolean();
		VirtualAvrConnection.Listener<CommandReply> listener = r -> {
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

	@SuppressWarnings("resource")
	public static VirtualAvrConnection connectionToVirtualAvr(GenericContainer<?> container) {
		URI serverUri = URI.create(format("ws://%s:%s", "localhost", container.getFirstMappedPort()));
		return new DefaultVirtualAvrConnection(serverUri)
				.addPinStateListener(p -> System.out.printf("Pin %s = %s\n", p.getPin(), p.getState()));
	}

	public DefaultVirtualAvrConnection(URI serverUri) {
		super(serverUri);
		addPinStateListener(this.pinStates::add);
		try {
			connectBlocking();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public boolean isConnected() {
		return isOpen();
	}

	public VirtualAvrConnection addPinStateListener(VirtualAvrConnection.Listener<PinState> listener) {
		pinStateListeners.add(listener);
		return this;
	}

	public VirtualAvrConnection removePinStateListener(VirtualAvrConnection.Listener<PinState> listener) {
		pinStateListeners.remove(listener);
		return this;
	}

	public VirtualAvrConnection addSerialDebugListener(VirtualAvrConnection.Listener<SerialDebug> listener) {
		serialDebugListeners.add(listener);
		return serialDebugListenersChanged();
	}

	public VirtualAvrConnection removeSerialDebugListener(VirtualAvrConnection.Listener<SerialDebug> listener) {
		serialDebugListeners.remove(listener);
		return serialDebugListenersChanged();
	}

	private VirtualAvrConnection serialDebugListenersChanged() {
		return debugSerial(!serialDebugListeners.isEmpty());
	}

	public VirtualAvrConnection addCommandReplyListener(VirtualAvrConnection.Listener<CommandReply> listener) {
		commandReplyListeners.add(listener);
		return this;
	}

	public VirtualAvrConnection removeCommandReplyListener(VirtualAvrConnection.Listener<CommandReply> listener) {
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

	private <T> void callAccept(List<VirtualAvrConnection.Listener<T>> listeners, String message, Class<T> clazz) {
		callAccept(listeners, gson.fromJson(message, clazz));
	}

	private static <T> void callAccept(List<VirtualAvrConnection.Listener<T>> listeners, T message) {
		for (VirtualAvrConnection.Listener<T> listener : listeners) {
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

		private SetPinReportMode(String pin, VirtualAvrConnection.PinReportMode mode) {
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

	public VirtualAvrConnection pinReportMode(String pin, VirtualAvrConnection.PinReportMode mode) {
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
		logger.warn("WebSocket error: {}", ex.getMessage());
	}

	@Override
	public void onClose(int code, String reason, boolean remote) {
		logger.debug("WebSocket closed: code={}, reason={}, remote={}", code, reason, remote);
	}

}