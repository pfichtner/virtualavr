package com.github.pfichtner.virtualavr.virtualavrtests;

import static com.github.pfichtner.virtualavr.IOUtil.withSketchFromClasspath;
import static com.github.pfichtner.virtualavr.SerialConnectionAwait.awaiter;
import static com.github.pfichtner.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinReportMode.ANALOG;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinReportMode.DIGITAL;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinReportMode.NONE;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.stateIsOff;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.stateIsOn;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.stateOfPinIs;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.SerialDebug.Direction.RX;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.SerialDebug.Direction.TX;
import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.function.Predicate.isEqual;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.SerialConnection;
import com.github.pfichtner.virtualavr.SerialConnectionAwait;
import com.github.pfichtner.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.virtualavr.VirtualAvrConnection.Listener;
import com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState;
import com.github.pfichtner.virtualavr.VirtualAvrConnection.SerialDebug;
import com.github.pfichtner.virtualavr.VirtualAvrConnection.SerialDebug.Direction;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;

/**
 * Integration test for virtualavr. Fires up the docker container and runs
 * integration tests against it. Features checked are:
 * <ul>
 * <li>Serial communication: Can the AVR read data sent from serial line and can
 * data being received sent by the avr via serial line?
 * <li>Websocket communication: Are the pin state changes propagated via
 * websockets? Can the pin states of the avr beeing controlled via websockets?
 * </ul>
 * 
 * @author Peter Fichtner
 */
@Testcontainers
class VirtualAvrIT {

	private static class RxTxListener implements AutoCloseable {

		private final VirtualAvrConnection avr;
		private final Map<Direction, ByteArrayOutputStream> serialData = new HashMap<>();
		private final Listener<SerialDebug> listener = s -> write(outputStream(s.direction()), s.bytes());

		private RxTxListener(VirtualAvrConnection avr) {
			this.avr = avr;
			avr.addSerialDebugListener(listener);
		}

		public String text(Direction direction) {
			return new String(outputStream(direction).toByteArray());
		}

		private ByteArrayOutputStream outputStream(Direction direction) {
			return serialData.computeIfAbsent(direction, __ -> new ByteArrayOutputStream());
		}

		@Override
		public void close() {
			avr.removeSerialDebugListener(listener);
		}

	}

	private static final String INTERNAL_LED = "D13";
	private static final String PWM_PIN = "D10";

	// since integrationtest.ino toggles each 250 ms between 0 and 42 we have to
	// measure at least each 250ms / 4
	private static final int PUBLISH_MILLIS = 250 / 4;

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer(withSketchFromClasspath("/integrationtest.ino"))
			.withPublishMillis(PUBLISH_MILLIS);

	@Test
	void canReadSerial() throws Exception {
		awaiter(virtualAvrContainer.serialConnection()).awaitReceived(r -> r.contains("Welcome virtualavr!"));
	}

	@Test
	void canWriteSerial() throws Exception {
		String send = "Echo Test!";
		awaiter(virtualAvrContainer.serialConnection()).sendAwait(send, r -> r.contains("Echo response: " + send));
	}

	@Test
	void serialConnectionCanBeReestablished() throws Exception {
		String send = "Echo Test!";
		for (int i = 0; i < 3; i++) {
			try (SerialConnection serialConnection = virtualAvrContainer.newSerialConnection()) {
				awaiter(serialConnection).sendAwait(send, r -> r.contains("Echo response: " + send));
			}
		}
	}

	@Test
	void canReadDigitalAndDoesPublishStateChangesViaWebsocket() {
		VirtualAvrConnection virtualAvr = virtualAvrContainer.avr();
		virtualAvr.pinReportMode(INTERNAL_LED, DIGITAL);
		waitForToggles(INTERNAL_LED, 3);
	}

	@Test
	void canReadAnalogAndDoesPublishStateChangesViaWebsocket() {
		VirtualAvrConnection virtualAvr = virtualAvrContainer.avr();
		virtualAvr.pinReportMode(PWM_PIN, ANALOG);
		await().untilAsserted(() -> assertThat(virtualAvr.pinStates()).contains(stateOfPinIs(PWM_PIN, 42))
				.describedAs(virtualAvr.pinStates().toString()));
	}

	@Test
	void canSwitchOffListening() throws InterruptedException {
		VirtualAvrConnection virtualAvr = virtualAvrContainer.avr();
		virtualAvr.pinReportMode(INTERNAL_LED, DIGITAL);
		long timeToTogglePinThreeTimes = waitForToggles(INTERNAL_LED, 3);

		virtualAvr.pinReportMode(INTERNAL_LED, NONE);
		// Test could fail because "pinReportMode" is async and there are some messages
		// sent even after #clearStates call. To prevent we should implement an
		// non-async "pause" command to halt/pause the simulator
		await().until(() -> {
			virtualAvr.clearStates();
			MILLISECONDS.sleep(500);
			return virtualAvr.pinStates().isEmpty();
		});

		MILLISECONDS.sleep(timeToTogglePinThreeTimes * 2);
		assertThat(virtualAvr.pinStates().stream()).noneMatch(s -> INTERNAL_LED.equals(s.getPin()));
	}

	private long waitForToggles(String pin, int times) {
		VirtualAvrConnection virtualAvr = virtualAvrContainer.avr();
		long start = currentTimeMillis();
		await().until(() -> count(virtualAvr.pinStates(), isEqual(stateIsOn(pin))) >= times
				&& count(virtualAvr.pinStates(), isEqual(stateIsOff(pin))) >= times);
		return currentTimeMillis() - start;
	}

	@Test
	void canSetDigitalPinStateViaWebsocket() throws IOException {
		VirtualAvrConnection virtualAvr = virtualAvrContainer.avr();
		SerialConnectionAwait awaiter = awaiter(virtualAvrContainer.serialConnection());

		// TODO make side-affect-free
		awaiter.awaitReceived(s -> {
			virtualAvr.pinState("D11", true);
			return s.contains("State-Change-D11: ON");
		});

		// TODO make side-affect-free
		awaiter.awaitReceived(s -> {
			virtualAvr.pinState("D11", false);
			return s.contains("State-Change-D11: OFF");
		});
	}

	@Test
	void canSetAnalogPinStateViaWebsocket() throws IOException {
		VirtualAvrConnection virtualAvr = virtualAvrContainer.avr();
		SerialConnectionAwait awaiter = awaiter(virtualAvrContainer.serialConnection());

		// TODO make side-affect-free
		awaiter.awaitReceived(s -> {
			virtualAvr.pinState("A0", 42);
			return s.contains("State-Change-A0: 42");
		});

		// TODO make side-affect-free
		awaiter.awaitReceived(s -> {
			virtualAvr.pinState("A0", 84);
			return s.contains("State-Change-A0: 84");
		});

		// TODO make side-affect-free
		awaiter.awaitReceived(s -> {
			virtualAvr.pinState("A0", 0);
			return s.contains("State-Change-A0: 0");
		});
	}

	@Test
	void doesPublishRxTxWhenEnabled() throws IOException {
		String send = "Echo Test!";
		try (RxTxListener rxTx = new RxTxListener(virtualAvrContainer.avr())) {
			awaiter(virtualAvrContainer.serialConnection()).sendAwait(send, r -> r.contains("Echo response: " + send));
			await().untilAsserted(() -> assertThat(rxTx.text(RX)).isEqualTo(send));
			await().untilAsserted(() -> assertThat(rxTx.text(TX)).contains("Loop").contains("Echo response: " + send));
		}
	}

	static void write(ByteArrayOutputStream outputStream, byte[] bytes) {
		try {
			outputStream.write(bytes);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	long count(List<PinState> pinStates, Predicate<PinState> pinState) {
		return pinStates.stream().filter(pinState).count();
	}

}
