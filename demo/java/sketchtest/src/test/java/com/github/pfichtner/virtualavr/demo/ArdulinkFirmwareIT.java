package com.github.pfichtner.virtualavr.demo;

import static com.github.pfichtner.virtualavr.IOUtil.downloadTo;
import static com.github.pfichtner.virtualavr.IOUtil.filename;
import static com.github.pfichtner.virtualavr.SerialConnectionAwait.awaiter;
import static com.github.pfichtner.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinReportMode.ANALOG;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinReportMode.DIGITAL;
import static java.util.stream.Collectors.joining;
import static org.awaitility.Awaitility.await;
import static org.testcontainers.shaded.com.google.common.base.Objects.equal;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.SerialConnection;
import com.github.pfichtner.virtualavr.SerialConnectionAwait;
import com.github.pfichtner.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;

/**
 * Downloads Ardulink firmware and run some virtualavr tests on it. This way the
 * Arduino based Ardulink firmware behavior can be tested without having real
 * hardware/flashing real hardware.
 * 
 * This test should become part of Ardulink itself (to test the local/internal
 * sketch file not the one uploaded to github).
 * 
 * @author Peter Fichtner
 */
@Testcontainers
class ArdulinkFirmwareIT {

	static final String REMOTE_INO_FILE = "https://raw.githubusercontent.com/Ardulink/Ardulink-2/"
			+ "master/deploy-dist/rootfolder/sketches/ArdulinkProtocol/ArdulinkProtocol.ino";

	static File inoFile;

	@BeforeAll
	static void loadFromNet(@TempDir File tmpDir) throws MalformedURLException, IOException {
		URL source = new URL(REMOTE_INO_FILE);
		inoFile = downloadTo(source, new File(tmpDir, filename(source)));
	}

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer(inoFile);

	@Test
	void canDetectArduinoThatSendInitialMessage() throws Exception {
		try (SerialConnection serial = virtualAvrContainer.serialConnection()) {
			awaiter(serial).waitReceivedAnything();
		}
	}

	@Test
	void sendsReplyIfReplyRequested() throws Exception {
		int id = -42;
		try (SerialConnection serial = virtualAvrContainer.serialConnection()) {
			awaiter(serial).waitReceivedAnything().sendAwait(ardulinkMessage("notn/?id=" + id),
					ardulinkMessage("rply/ok?id=" + id));
		}
	}

	@Test
	void canSwitchDigitalPin() throws Exception {
		int pin = 12;
		try (SerialConnection serial = virtualAvrContainer.serialConnection()) {
			awaiter(serial).waitReceivedAnything();
			VirtualAvrConnection avr = virtualAvrContainer.avr();
			avr.pinReportMode(String.valueOf(pin), DIGITAL);
			{
				boolean state = true;
				serial.send(powerDigitalMessage(pin, state));
				await().until(() -> stateOfPinIs(avr, String.valueOf(pin), state));
			}
			{
				boolean state = false;
				serial.send(powerDigitalMessage(pin, state));
				await().until(() -> stateOfPinIs(avr, String.valueOf(pin), state));
			}
		}
	}

	@Test
	void canSwitchAnalogBetterSaidDigitalPwmPin() throws Exception {
		int pin = 10;
		try (SerialConnection serial = virtualAvrContainer.serialConnection()) {
			awaiter(serial).waitReceivedAnything();
			VirtualAvrConnection avr = virtualAvrContainer.avr();
			avr.pinReportMode(String.valueOf(pin), ANALOG);
			{
				int value = 42;
				serial.send(powerAnalogMessage(pin, value));
				await().until(() -> stateOfPinIs(avr, String.valueOf(pin), value));
			}
			{
				int value = 0;
				serial.send(powerAnalogMessage(pin, value));
				await().until(() -> stateOfPinIs(avr, String.valueOf(pin), value));
			}
		}
	}

	@Test
	void doesDetectDigitalPinSwitches() throws Exception {
		int pin = 12;
		try (SerialConnection serial = virtualAvrContainer.serialConnection()) {
			SerialConnectionAwait awaiter = awaiter(serial);
			awaiter.waitReceivedAnything();
			serial.send(startListeningDigitalMessage(pin));
			{
				virtualAvrContainer.avr().pinState(String.valueOf(pin), true);
				awaiter.awaitReceived(s -> s.contains(ardulinkMessage("dred", pin, 1)));
			}
			{
				virtualAvrContainer.avr().pinState(String.valueOf(pin), false);
				awaiter.awaitReceived(s -> s.contains(ardulinkMessage("dred", pin, 0)));
			}
		}
	}

	@Test
	void doesDetectAnalogPinSwitches() throws Exception {
		int pin = 0;
		try (SerialConnection serial = virtualAvrContainer.serialConnection()) {
			SerialConnectionAwait awaiter = awaiter(serial);
			awaiter.waitReceivedAnything();
			serial.send(startListeningAnalogMessage(pin));
			{
				int value = 42;
				virtualAvrContainer.avr().pinState("A" + pin, value);
				awaiter.awaitReceived(s -> s.contains(ardulinkMessage("ared", pin, value)));
			}
			{
				int value = 0;
				virtualAvrContainer.avr().pinState("A" + pin, value);
				awaiter.awaitReceived(s -> s.contains(ardulinkMessage("ared", pin, value)));
			}
		}
	}

	static boolean stateOfPinIs(VirtualAvrConnection avr, String pin, boolean expected) {
		return equal(stateOfPin(avr, pin), expected);
	}

	static boolean stateOfPinIs(VirtualAvrConnection avr, String pin, int expected) {
		return equal(stateOfPin(avr, pin), expected);
	}

	static Object stateOfPin(VirtualAvrConnection avr, String pin) {
		return avr.lastStates().get(pin);
	}

	static String powerDigitalMessage(int pin, boolean state) {
		return ardulinkMessage("ppsw", pin, (state ? "1" : "0"));
	}

	static String powerAnalogMessage(int pin, int state) {
		return ardulinkMessage("ppin", pin, state);
	}

	static String startListeningDigitalMessage(int pin) {
		return ardulinkMessage("srld", pin);
	}

	static String startListeningAnalogMessage(int pin) {
		return ardulinkMessage("srla", pin);
	}

	static String ardulinkMessage(Object... parts) {
		return ardulinkMessage(concat(parts));
	}

	static String ardulinkMessage(String message) {
		return "alp://" + message + "\n";
	}

	static String concat(Object... parts) {
		return Arrays.stream(parts).map(Object::toString).collect(joining("/"));
	}

}
