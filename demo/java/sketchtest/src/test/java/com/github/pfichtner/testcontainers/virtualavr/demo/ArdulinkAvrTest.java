package com.github.pfichtner.testcontainers.virtualavr.demo;

import static com.github.pfichtner.testcontainers.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinReportMode.ANALOG;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinReportMode.DIGITAL;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinState.stateIsOff;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinState.stateIsOn;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinState.stateOfPinIs;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.IterableAssert;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.testcontainers.virtualavr.SerialConnection;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinState;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrContainer;

@Testcontainers
class ArdulinkAvrTest {

	private static final String STEADY_MESSAGE = "alp://rply/ok?id=0\n";

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer(
			new File("../../../test-artifacts/ArdulinkProtocol/ArdulinkProtocol.ino")) //
			.withBaudrate(115200);

	@Test
	void canTurnOnAndOffDigital() throws Exception {
		int pinNumber = 12;
		VirtualAvrConnection avr = enableSerialDebug(virtualAvrContainer.avr());
		avr.pinReportMode(String.valueOf(pinNumber), DIGITAL);
		try (SerialConnection serial = steadyConnection()) {
			serial.send(format("alp://ppsw/%d/1\n", pinNumber));
			awaitState(s -> s.contains(stateIsOn(String.valueOf(pinNumber))));

			serial.send(format("alp://ppsw/%d/0\n", pinNumber));
			awaitState(s -> s.contains(stateIsOff(String.valueOf(pinNumber))));
		}
	}

	@Test
	void canSetValueOnAnalog() throws Exception {
		int pinNumber = 9;
		VirtualAvrConnection avr = enableSerialDebug(virtualAvrContainer.avr());
		avr.pinReportMode(String.valueOf(pinNumber), ANALOG);
		try (SerialConnection serial = steadyConnection()) {
			virtualAvrContainer.avr().clearStates();
			serial.send(format("alp://ppin/%d/123\n", pinNumber));
			awaitState(s -> s.contains(stateOfPinIs(String.valueOf(pinNumber), 123)));

			serial.send(format("alp://ppin/%d/0\n", pinNumber));
			awaitState(s -> s.contains(stateOfPinIs(String.valueOf(pinNumber), 0)));
		}
	}

	@Test
	void toneWithoutRplyMessage() throws Exception {
		int pinNumber = 9;
		VirtualAvrConnection avr = enableSerialDebug(virtualAvrContainer.avr());
		avr.pinReportMode(String.valueOf(pinNumber), ANALOG);
		try (SerialConnection serial = steadyConnection()) {
			virtualAvrContainer.avr().clearStates();
			serial.send(format("alp://tone/%d/123/-1\n", pinNumber));
			awaitState(s -> s.contains(stateOfPinIs(String.valueOf(pinNumber), 127)));

			virtualAvrContainer.avr().clearStates();
			serial.send(format("alp://notn/%d\n", pinNumber));
			awaitState(s -> s.contains(stateOfPinIs(String.valueOf(pinNumber), 0)));
		}
	}

	@Test
	void toneWithRplyMessage() throws Exception {
		int pinNumber = 9;
		VirtualAvrConnection avr = enableSerialDebug(virtualAvrContainer.avr());
		avr.pinReportMode(String.valueOf(pinNumber), ANALOG);
		try (SerialConnection serial = steadyConnection()) {
			virtualAvrContainer.avr().clearStates();
			serial.send(format("alp://tone/%d/123/-1?id=42\n", pinNumber));
			awaitState(s -> s.contains(stateOfPinIs(String.valueOf(pinNumber), 127)));
			awaitOkRply(42);

			virtualAvrContainer.avr().clearStates();
			serial.send(format("alp://notn/%d?id=43\n", pinNumber));
			awaitOkRply(43);
			awaitState(s -> s.contains(stateOfPinIs(String.valueOf(pinNumber), 0)));
		}
	}

	@Test
	void customMessagesAreNotSupportedInStandardImplementation() throws Exception {
		VirtualAvrConnection avr = enableSerialDebug(virtualAvrContainer.avr());
		try (SerialConnection serial = steadyConnection()) {
			avr.clearStates();
			serial.send("alp://cust/abc/xyz?id=42\n");
			awaitKoRply(42);
		}
	}

	@Test
	void unknownCommandResultInNokRply() throws Exception {
		VirtualAvrConnection avr = enableSerialDebug(virtualAvrContainer.avr());
		try (SerialConnection serial = steadyConnection()) {
			avr.clearStates();
			serial.send("alp://XXXX/123/abc/X-Y-Z?id=42\n");
			awaitKoRply(42);
		}
	}

	@Test
	void canReadAnalogPinStateWithoutInitialValue() throws Exception {
		int pinNumber = 5;
		VirtualAvrConnection avr = enableSerialDebug(virtualAvrContainer.avr());
		try (SerialConnection serial = steadyConnection()) {
			avr.clearStates();
			serial.send(format("alp://srla/%d?id=42\n", pinNumber));
			serialAwait(r -> r.contains("alp://rply/ok?id=42\n"));

			avr.clearStates();
			avr.pinState("A" + pinNumber, 987);
			serialAwait(r -> r.contains(format("alp://ared/%d/987\n", pinNumber)));

			serial.send(format("alp://spla/%d?id=43\n", pinNumber));
			serialAwait(r -> r.contains("alp://rply/ok?id=43\n"));
		}
	}

	@Test
	void canReadAnalogPinStateWithInitialValue() throws Exception {
		int pinNumber = 5;
		VirtualAvrConnection avr = enableSerialDebug(virtualAvrContainer.avr());
		try (SerialConnection serial = steadyConnection()) {
			avr.pinState("A" + pinNumber, 987);
			avr.clearStates();
			serial.send(format("alp://srla/%d?id=42\n", pinNumber));
			serialAwait(r -> r.contains("alp://rply/ok?id=42\n"));

			avr.clearStates();
			serialAwait(r -> r.contains(format("alp://ared/%d/987\n", pinNumber)));

			serial.send(format("alp://spla/%d?id=43\n", pinNumber));
			serialAwait(r -> r.contains("alp://rply/ok?id=43\n"));
		}
	}

	@Test
	void canReadDigitalPinStateWithoutInitialValue() throws Exception {
		int pinNumber = 12;
		VirtualAvrConnection avr = enableSerialDebug(virtualAvrContainer.avr());
		try (SerialConnection serial = steadyConnection()) {
			avr.clearStates();
			serial.send(format("alp://srld/%d?id=42\n", pinNumber));
			awaitOkRply(42);

			avr.clearStates();
			avr.pinState(String.valueOf(pinNumber), true);
			serialAwait(r -> r.contains(format("alp://dred/%d/1\n", pinNumber)));

			serial.send(format("alp://spld/%d?id=43\n", pinNumber));
			awaitOkRply(43);
		}
	}

	@Test
	void canReadDigitalPinStateWithInitialValue() throws Exception {
		int pinNumber = 12;
		VirtualAvrConnection avr = enableSerialDebug(virtualAvrContainer.avr());
		try (SerialConnection serial = steadyConnection()) {
			avr.pinState(String.valueOf(pinNumber), true);
			avr.clearStates();
			serial.send(format("alp://srld/%d?id=42\n", pinNumber));
			awaitOkRply(42);

			avr.clearStates();
			serialAwait(r -> r.contains(format("alp://dred/%d/1\n", pinNumber)));

			serial.send(format("alp://spld/%d?id=43\n", pinNumber));
			awaitOkRply(43);
		}
	}

	private static VirtualAvrConnection enableSerialDebug(VirtualAvrConnection avr) {
		avr.addSerialDebugListener(t -> System.out.println(t.direction() + " " + new String(t.bytes())));
		return avr;
	}

	private SerialConnection steadyConnection() throws IOException {
		SerialConnection serialConnection = virtualAvrContainer.serialConnection();
		awaitSteadyMessage();
		return serialConnection;
	}

	private void awaitOkRply(int id) throws IOException {
		awaitRply("ok", id);
	}

	private void awaitKoRply(int id) throws IOException {
		awaitRply("ko", id);
	}

	private void awaitRply(String state, int id) throws IOException {
		serialAwait(r -> r.contains(format("alp://rply/" + state + "?id=%d\n", id)));
	}

	private void awaitSteadyMessage() throws IOException {
		serialAwait(r -> r.isEqualTo(STEADY_MESSAGE));
	}

	private void serialAwait(Consumer<AbstractStringAssert<?>> consumer) throws IOException {
		SerialConnection serial = virtualAvrContainer.serialConnection();
		await().untilAsserted(() -> consumer.accept(assertThat(serial.received())));
	}

	private void awaitState(Consumer<IterableAssert<PinState>> consumer) {
		await().untilAsserted(() -> consumer.accept(assertThat(virtualAvrContainer.avr().pinStates())));
	}

}
