package com.ardulink.sketch;

import static com.github.pfichtner.virtualavr.SerialConnectionAwait.awaiter;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinReportMode.DIGITAL;
import static org.awaitility.Awaitility.await;
import static org.testcontainers.shaded.com.google.common.base.Objects.equal;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.SerialConnection;
import com.github.pfichtner.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;

@Testcontainers
class ArdulinkFirmwareTest {

	static final String REMOTE_INO_FILE = "https://raw.githubusercontent.com/Ardulink/Ardulink-2/master/deploy-dist/rootfolder/sketches/ArdulinkProtocol/ArdulinkProtocol.ino";

	static File inoFile;

	@BeforeAll
	static void loadFromNet(@TempDir File tmpDir) throws MalformedURLException, IOException {
		inoFile = downloadTo(new URL(REMOTE_INO_FILE), new File(tmpDir, "ArdulinkProtocol.ino"));
	}

	private static File downloadTo(URL source, File target)
			throws IOException, MalformedURLException, FileNotFoundException {
		try (BufferedInputStream in = new BufferedInputStream(source.openStream());
				FileOutputStream out = new FileOutputStream(target)) {
			out.write(in.readAllBytes());
		}
		return target;
	}

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = new VirtualAvrContainer<>().withSketchFile(inoFile);

	@Test
	void canDetectArduinoThatSendInitialMessage() throws Exception {
		try (SerialConnection connection = virtualAvrContainer.serialConnection()) {
			awaiter(connection).waitReceivedAnything();
		}
	}

	@Test
	void sendsReplyIfReplyRequested() throws Exception {
		String id = "42";
		try (SerialConnection connection = virtualAvrContainer.serialConnection()) {
			awaiter(connection).waitReceivedAnything().sendAwait("alp://notn/?id=" + id + "\n",
					"alp://rply/ok?id=" + id + "\n");
		}
	}

	@Test
	void canSwitchPin() throws Exception {
		int pin = 12;
		try (SerialConnection connection = virtualAvrContainer.serialConnection()) {
			awaiter(connection).waitReceivedAnything();
			VirtualAvrConnection avr = virtualAvrContainer.avr();
			avr.pinReportMode("D" + pin, DIGITAL);
			{
				boolean state = true;
				connection.send(powerDigital(pin, state));
				await().until(() -> stateOfPinIs(avr, "D" + pin, state));
			}
			{
				boolean state = false;
				connection.send(powerDigital(pin, state));
				await().until(() -> stateOfPinIs(avr, "D" + pin, state));
			}
		}
	}

	static boolean stateOfPinIs(VirtualAvrConnection avr, String pin, boolean state) {
		return equal(avr.lastStates().get(pin), state);
	}

	static String powerDigital(int pin, boolean state) {
		return "alp://ppsw/" + pin + "/" + (state ? "1" : "0") + "\n";
	}

}
