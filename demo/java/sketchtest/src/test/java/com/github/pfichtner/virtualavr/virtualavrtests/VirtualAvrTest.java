package com.github.pfichtner.virtualavr.virtualavrtests;

import static com.github.pfichtner.virtualavr.SerialConnectionAwait.awaiter;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinReportMode.ANALOG;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.off;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.on;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.net.URISyntaxException;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.SerialConnection;
import com.github.pfichtner.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;

import jssc.SerialPortException;

@Testcontainers
class VirtualAvrTest {

	private static final String INTERNAL_LED = "D13";
	private static final String PWM_PIN = "D10";

	@Container
	// TODO we should not use the latest version of the docker image here but the
	// one actual build (switch to Dockerfile here?)
	VirtualAvrContainer<?> virtualAvrContainer = new VirtualAvrContainer<>()
			.withSketchFile(loadClasspath("/integrationtest.ino"));

	static File loadClasspath(String name) {
		try {
			return new File(VirtualAvrTest.class.getResource(name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

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
	void canReadDigitalAndDoesPublishStateChangesViaWebsocket() {
		VirtualAvrConnection virtualAvr = virtualAvrContainer.avr();
		await().until(() -> count(virtualAvr.pinStates(), on(INTERNAL_LED)) >= 3
				&& count(virtualAvr.pinStates(), off(INTERNAL_LED)) >= 3);
	}

	@Test
	void canReadAnalogAndDoesPublishStateChangesViaWebsocket() {
		VirtualAvrConnection virtualAvr = virtualAvrContainer.avr();
		virtualAvr.pinReportMode(PWM_PIN, ANALOG);
		PinState p = PinState.stateOfPinIs(PWM_PIN, 42);
		await().until(() -> virtualAvr.pinStates().stream().anyMatch(p));
	}

	@Test
	void canSetDigitalPinStateViaWebsocket() throws SerialPortException {
		VirtualAvrConnection virtualAvr = virtualAvrContainer.avr();
		SerialConnection serialConnection = virtualAvrContainer.serialConnection();

		await().until(() -> {
			virtualAvr.pinState("D11", true);
			return serialConnection.received().contains("State-Change-D11: ON");
		});

		await().until(() -> {
			virtualAvr.pinState("D11", false);
			return serialConnection.received().contains("State-Change-D11: OFF");
		});
	}

	@Test
	void canSetAnalogPinStateViaWebsocket() throws SerialPortException {
		VirtualAvrConnection virtualAvr = virtualAvrContainer.avr();
		SerialConnection serialConnection = virtualAvrContainer.serialConnection();

		await().until(() -> {
			virtualAvr.pinState("A0", 42);
			return serialConnection.received().contains("State-Change-A0: 42");
		});

		await().until(() -> {
			virtualAvr.pinState("A0", 84);
			return serialConnection.received().contains("State-Change-A0: 84");
		});

		await().until(() -> {
			virtualAvr.pinState("A0", 0);
			return serialConnection.received().contains("State-Change-A0: 0");
		});
	}

	long count(List<PinState> pinStates, Predicate<PinState> pinState) {
		return pinStates.stream().filter(pinState).count();
	}

}
