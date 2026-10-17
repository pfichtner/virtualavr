package com.github.pfichtner.testcontainers.virtualavr;

import static java.lang.String.format;
import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BinaryOperator;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	public static class DefaultPinStates implements PinStates {

		private final List<PinState> pinStates = new CopyOnWriteArrayList<>();

		@Override
		public Iterator<PinState> iterator() {
			return pinStates.iterator();
		}

		public void add(PinState pinState) {
			this.pinStates.add(pinState);
		}

		public void clear() {
			this.pinStates.clear();
		}

		public Map<String, Object> last() {
			return this.pinStates.stream().collect(toMap(PinState::getPin, PinState::getState, lastWins()));
		}

		public Object last(String pin) {
			return this.pinStates.stream() //
					.filter(p -> p.getPin().equals(pin)) //
					.reduce(lastWins()) //
					.map(PinState::getState) //
					.orElse(null);
		}

		private static <T> BinaryOperator<T> lastWins() {
			return (first, last) -> last;
		}

	}

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

	private final Map<Class<?>, List<VirtualAvrConnection.Listener<?>>> listeners = new ConcurrentHashMap<>();
	private final DefaultPinStates pinStates = new DefaultPinStates();
	private final Map<String, PinReportMode> pinReportModes = new ConcurrentHashMap<>();
	private final List<CompletableFuture<CommandReply>> pendingReplies = new CopyOnWriteArrayList<>();
	private final AtomicBoolean reconnecting = new AtomicBoolean();
	private volatile boolean closedByUser;
	private volatile boolean debugSerial;

	private static final long RECONNECT_INTERVAL_MILLIS = 1000;

	private VirtualAvrConnection sendAndWaitForReply(WithReplyId messageToSend) {
		sendAndWaitForReplyAsync(messageToSend).join();
		return this;
	}

	private CompletableFuture<CommandReply> sendAndWaitForReplyAsync(WithReplyId message) {
		CompletableFuture<CommandReply> future = createReplyFuture(message);
		pendingReplies.add(future);
		future.whenComplete((__r, __t) -> pendingReplies.remove(future));
		try {
			send(gson.toJson(message));
		} catch (Exception ex) {
			future.completeExceptionally(ex);
		}
		return future;
	}

	private CompletableFuture<CommandReply> createReplyFuture(WithReplyId message) {
		CompletableFuture<CommandReply> future = new CompletableFuture<>();
		VirtualAvrConnection.Listener<CommandReply> listener = r -> {
			if (Objects.equals(message.replyId(), r.replyId())) {
				future.complete(r);
			}
		};
		addCommandReplyListener(listener);
		future.whenComplete((__r, __t) -> removeCommandReplyListener(listener));
		return future;
	}

	@SuppressWarnings("resource")
	public static VirtualAvrConnection connectionToVirtualAvr(VirtualAvrContainer<?> container) {
		String baseUri = format("ws://%s:%s", "localhost", container.getWebsocketPort());
		logger.info("WebSocket: Connecting to {}", baseUri);
		return new DefaultVirtualAvrConnection(URI.create(baseUri + appendToken(container)))
				.addPinStateListener(p -> logger.info("Pin {} = {}", p.getPin(), p.getState()));
	}

	private static String appendToken(VirtualAvrContainer<?> container) {
		return container.token().map(t -> "?token=" + encode(t, UTF_8)).orElse("");
	}

	public DefaultVirtualAvrConnection(URI serverUri) {
		super(serverUri);
		addPinStateListener(pinStates::add);
		try {
			connectBlocking();
			logger.info("WebSocket: Connection established: isConnected={}", isConnected());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public PinStates pinStates() {
		return pinStates;
	}

	/**
	 * @deprecated use {@link PinStates#last()} instead
	 * @see #pinStates()
	 * @see PinStates#last()
	 */
	@Deprecated
	public Map<String, Object> lastStates() {
		return pinStates().last();
	}

	/**
	 * @deprecated use {@link PinStates#last(String)} instead
	 * @see #pinStates()
	 * @see PinStates#last(String)
	 */
	@Deprecated
	public Object lastState(String pin) {
		return pinStates().last(pin);
	}

	/**
	 * @deprecated use {@link PinStates#clear()} instead
	 * @see #pinStates()
	 * @see PinStates#clear()
	 */
	@Override
	@Deprecated
	public VirtualAvrConnection clearStates() {
		pinStates().clear();
		return this;
	}

	@Override
	public boolean isConnected() {
		return isOpen();
	}

	private <T> DefaultVirtualAvrConnection addListener(Class<T> type, VirtualAvrConnection.Listener<T> listener) {
		listeners.computeIfAbsent(type, __ -> new CopyOnWriteArrayList<Listener<?>>()).add(listener);
		return this;
	}

	private <T> DefaultVirtualAvrConnection removeListener(Class<T> type, VirtualAvrConnection.Listener<T> listener) {
		getListeners(type).remove(listener);
		return this;
	}

	@SuppressWarnings("unchecked")
	private <T> List<Listener<T>> getListeners(Class<T> type) {
		return (List<Listener<T>>) (List<?>) listeners.getOrDefault(type, List.of());
	}

	public VirtualAvrConnection addPinStateListener(VirtualAvrConnection.Listener<PinState> listener) {
		return addListener(PinState.class, listener);
	}

	public VirtualAvrConnection removePinStateListener(VirtualAvrConnection.Listener<PinState> listener) {
		return removeListener(PinState.class, listener);
	}

	public VirtualAvrConnection addSerialDebugListener(VirtualAvrConnection.Listener<SerialDebug> listener) {
		addListener(SerialDebug.class, listener);
		return serialDebugListenersChanged();
	}

	public VirtualAvrConnection removeSerialDebugListener(VirtualAvrConnection.Listener<SerialDebug> listener) {
		removeListener(SerialDebug.class, listener);
		return serialDebugListenersChanged();
	}

	private VirtualAvrConnection serialDebugListenersChanged() {
		return debugSerial(!getListeners(SerialDebug.class).isEmpty());
	}

	public VirtualAvrConnection addCommandReplyListener(VirtualAvrConnection.Listener<CommandReply> listener) {
		return addListener(CommandReply.class, listener);
	}

	public VirtualAvrConnection removeCommandReplyListener(VirtualAvrConnection.Listener<CommandReply> listener) {
		return removeListener(CommandReply.class, listener);
	}

	public VirtualAvrConnection addFirmwareReloadListener(VirtualAvrConnection.Listener<FirmwareReload> listener) {
		return addListener(FirmwareReload.class, listener);
	}

	public VirtualAvrConnection removeFirmwareReloadListener(VirtualAvrConnection.Listener<FirmwareReload> listener) {
		return removeListener(FirmwareReload.class, listener);
	}

	@Override
	public void onOpen(ServerHandshake handshakedata) {
		// Crash recovery: after reconnecting to a restarted simulator process,
		// drop the pin states of the previous firmware and restore the
		// subscriptions the fresh process does not know about anymore. (WATCH
		// reloads happen in place and never disconnect, so they do not come
		// through here.)
		pinStates.clear();
		pinReportModes.forEach((pin, mode) -> sendQuietly(new SetPinReportMode(pin, mode)));
		if (debugSerial) {
			sendQuietly(new SetSerialDebug(true));
		}
	}

	private void sendQuietly(WithReplyId message) {
		try {
			send(gson.toJson(message));
		} catch (Exception e) {
			logger.debug("WebSocket: could not send {}: {}", message.getClass().getSimpleName(), e.getMessage());
		}
	}

	@Override
	public void onMessage(String message) {
		Map<?, ?> json = gson.fromJson(message, Map.class);
		if (isDeprecated(json)) {
			return;
		}
		if (isResponse(json)) {
			callListeners(CommandReply.class, message);
			return;
		}
		String type = String.valueOf(json.get("type"));
		if ("pinState".equals(type)) {
			callListeners(PinState.class, message);
		} else if ("serialDebug".equals(type)) {
			callListeners(SerialDebug.class, message);
		} else if ("firmwareReload".equals(type)) {
			callListeners(FirmwareReload.class, message);
		}
	}

	private static boolean isDeprecated(Map<?, ?> json) {
		return hasAttribute(json, "deprecated");
	}

	private static boolean isResponse(Map<?, ?> json) {
		return hasAttribute(json, "replyId") && hasAttribute(json, "executed");
	}

	private static boolean hasAttribute(Map<?, ?> values, String name) {
		return values.get(name) != null;
	}

	private <T> void callListeners(Class<T> clazz, String message) {
		callAccept(getListeners(clazz), gson.fromJson(message, clazz));
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
		pinReportModes.put(pin, mode);
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
	public void close() {
		closedByUser = true;
		super.close();
	}

	@Override
	public void onError(Exception ex) {
		logger.warn("WebSocket error: {}", ex.getMessage());
	}

	@Override
	public void onClose(int code, String reason, boolean remote) {
		logger.debug("WebSocket closed: code={}, reason={}, remote={}", code, reason, remote);
		failPendingReplies(code, reason);
		if (!closedByUser) {
			scheduleReconnect();
		}
	}

	private void failPendingReplies(int code, String reason) {
		pendingReplies.forEach(future -> future
				.completeExceptionally(new IOException(format("WebSocket closed: code=%s, reason=%s", code, reason))));
	}

	/**
	 * Keeps trying to reconnect to the (restarted) simulation until the connection
	 * is re-established or {@link #close()} was called. This is a crash-recovery
	 * safety net; note that WATCH reloads happen in place and do not drop the
	 * connection at all.
	 */
	private void scheduleReconnect() {
		if (!reconnecting.compareAndSet(false, true)) {
			return;
		}
		Thread thread = new Thread(this::reconnectLoop, "virtualavr-websocket-reconnect");
		thread.setDaemon(true);
		thread.start();
	}

	private void reconnectLoop() {
		try {
			while (!closedByUser && !isOpen()) {
				boolean connected = false;
				try {
					connected = reconnectBlocking();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				} catch (Exception e) {
					logger.debug("WebSocket: reconnect to {} failed: {}", getURI(), e.getMessage());
				}
				if (connected) {
					logger.info("WebSocket: reconnected to {}", getURI());
					return;
				}
				try {
					Thread.sleep(RECONNECT_INTERVAL_MILLIS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		} finally {
			reconnecting.set(false);
		}
	}

}