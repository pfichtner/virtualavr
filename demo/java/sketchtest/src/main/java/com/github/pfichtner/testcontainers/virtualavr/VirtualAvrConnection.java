package com.github.pfichtner.testcontainers.virtualavr;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.lang.String.format;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public interface VirtualAvrConnection extends AutoCloseable {

	enum PinReportMode {
		ANALOG("analog"), DIGITAL("digital"), NONE("none");

		final String modeName;

		PinReportMode(String message) {
			this.modeName = message;
		}
	}

	public static interface PinStates extends Iterable<PinState> {

		void clear();

		Map<String, Object> last();

		Object last(String pin);

		default Stream<PinState> stream() {
			return StreamSupport.stream(spliterator(), false);
		}

	}

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
			return stateIsOn(String.valueOf(pin));
		}

		public static PinState stateIsOn(String pin) {
			return stateOfPinIs(pin, TRUE);
		}

		public static PinState stateIsOff(int pin) {
			return stateIsOff(String.valueOf(pin));
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

	public static class CommandReply {

		private final UUID replyId;

		public CommandReply(UUID replyId) {
			this.replyId = replyId;
		}

		public UUID replyId() {
			return replyId;
		}
	}

	public static class SerialDebug {

		public enum Direction {
			RX, TX
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

	interface Listener<T> {
		void accept(T t);
	}

	boolean isConnected();

	void close();

	VirtualAvrConnection addPinStateListener(VirtualAvrConnection.Listener<PinState> listener);

	VirtualAvrConnection removePinStateListener(VirtualAvrConnection.Listener<PinState> listener);

	VirtualAvrConnection addSerialDebugListener(VirtualAvrConnection.Listener<SerialDebug> listener);

	VirtualAvrConnection removeSerialDebugListener(VirtualAvrConnection.Listener<SerialDebug> listener);

	VirtualAvrConnection addCommandReplyListener(VirtualAvrConnection.Listener<CommandReply> listener);

	VirtualAvrConnection removeCommandReplyListener(VirtualAvrConnection.Listener<CommandReply> listener);

	PinStates pinStates();

	/**
	 * @deprecated use {@link PinStates#last()} instead
	 * @see #pinStates()
	 * @see PinStates#last()
	 */
	@Deprecated
	Map<String, Object> lastStates();

	/**
	 * @deprecated use {@link PinStates#last(String)} instead
	 * @see #pinStates()
	 * @see PinStates#last(String)
	 */
	@Deprecated
	Object lastState(String pin);

	/**
	 * @deprecated use {@link PinStates#clear()} instead
	 * @see #pinStates()
	 * @see PinStates#clear()
	 */
	@Deprecated
	VirtualAvrConnection clearStates();

	VirtualAvrConnection pinState(String pin, boolean state);

	VirtualAvrConnection pinState(String pin, int state);

	VirtualAvrConnection pinReportMode(String pin, VirtualAvrConnection.PinReportMode mode);

	VirtualAvrConnection pause();

	VirtualAvrConnection unpause();

}