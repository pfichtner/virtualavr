package com.github.pfichtner.virtualavr.virtualavrtests;

import static com.github.pfichtner.virtualavr.SerialConnectionAwait.awaiter;
import static com.github.pfichtner.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinReportMode.ANALOG;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinReportMode.DIGITAL;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinReportMode.NONE;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.stateIsOff;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.stateIsOn;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.stateOfPinIs;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.testcontainers.shaded.com.google.common.base.Objects.equal;

import java.io.File;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.SerialConnectionAwait;
import com.github.pfichtner.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;

@Testcontainers
class SwitchAllPortsViaArdulinkTest {

	private static final String ALP_PPSW = "alp://ppsw/%s/%d?id=%s\n";
	private static final String ALP_PPIN = "alp://ppin/%s/%d?id=%s\n";

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer(
			new File("../../../test-artifacts/ArdulinkProtocol/ArdulinkProtocol.ino")) //
			.withBaudrate(115200);

	@Timeout(value = 30, unit = SECONDS)
	@ParameterizedTest
	@ValueSource(ints = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13 })
	void canSwitchDigitalPinOnAndOff(int pinNumber) throws Exception {
		String digitalPort = "D" + pinNumber;
		virtualAvrContainer.avr().pinReportMode(digitalPort, DIGITAL);
		SerialConnectionAwait awaiter = awaiter(virtualAvrContainer.serialConnection());
		try {
			awaiter.sendAwait(format(ALP_PPSW, pinNumber, 1, 42), r -> r.contains(okRplyReceived(42)));
			awaitUntil(stateIsOn(digitalPort));

			awaiter.sendAwait(format(ALP_PPSW, pinNumber, 0, 43), r -> r.contains(okRplyReceived(43)));
			awaitUntil(stateIsOff(digitalPort));
		} finally {
			virtualAvrContainer.avr().pinReportMode(digitalPort, NONE);
		}
	}

	@Timeout(value = 30, unit = SECONDS)
	@ParameterizedTest
	@ValueSource(ints = { 3, 9, 10, 11 })
//	@ValueSource(ints = { 3, 5, 6, 9, 10, 11 }) // TODO 5 and 6 have PWM frequency: ~980 Hz (others ~490 Hz) and are not yet correct handled by virtualavr.js
	void canSetPwmPinToSomeValue(int pinNumber) throws Exception {
		String analogPort = "D" + pinNumber;
		virtualAvrContainer.avr().pinReportMode(analogPort, ANALOG);
		SerialConnectionAwait awaiter = awaiter(virtualAvrContainer.serialConnection());
		try {
			awaiter.sendAwait(format(ALP_PPIN, pinNumber, 127, 44), r -> r.contains(okRplyReceived(44)));
			awaitUntil(stateOfPinIs(analogPort, 127));

			awaiter.sendAwait(format(ALP_PPIN, pinNumber, 0, 45), r -> r.contains(okRplyReceived(45)));
			awaitUntil(stateOfPinIs(analogPort, 0));
		} finally {
			virtualAvrContainer.avr().pinReportMode(analogPort, NONE);
		}
	}

	String okRplyReceived(long id) {
		return format("alp://rply/ok?id=%s", id);
	}

	void awaitUntil(PinState state) {
		await().until(() -> equal(virtualAvrContainer.avr().lastStates().get(state.getPin()), state.getState()));
	}

	static VirtualAvrConnection enableSerialDebug(VirtualAvrConnection avr) {
		avr.addSerialDebugListener(t -> System.out.println(t.direction() + " " + new String(t.bytes())));
		return avr;
	}

}
