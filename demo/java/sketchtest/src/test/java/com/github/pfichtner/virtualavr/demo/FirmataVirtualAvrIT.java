package com.github.pfichtner.virtualavr.demo;

import static com.github.pfichtner.virtualavr.IOUtil.withSketchFromClasspath;
import static com.github.pfichtner.virtualavr.SerialConnectionAwait.awaiter;
import static com.github.pfichtner.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static java.lang.Integer.parseInt;
import static java.lang.Integer.toBinaryString;
import static java.time.temporal.ChronoUnit.SECONDS;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.SerialConnection;
import com.github.pfichtner.virtualavr.SerialConnectionAwait;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;

@Testcontainers
class FirmataVirtualAvrIT {

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer(withSketchFromClasspath("/firmata-project.zip")) //
			.withBaudrate(57600);

	static final byte startSysex = (byte) 0xF0;
	static final byte reportFirmwareQuery = 0x79;
	static final byte capabilityQuery = 0x6B;
	static final byte endSysex = (byte) 0xF7;

	byte[] firmwareInfo = new byte[] { startSysex, 121, 2, 5, 115, 0, 107, 0, 101, 0, 116, 0, 99, 0, 104, 0, 46, 0, 105,
			0, 110, 0, 111, 0, endSysex };
	byte[] firmwareStartupInfo = concat(new byte[] { -7, 2, 5 }, firmwareInfo);
	byte[] capabilities = new byte[] { startSysex, 108, 127, 127, 0, 1, 11, 1, 1, 1, 4, 14, 127, 0, 1, 11, 1, 1, 1, 3,
			8, 4, 14, 127, 0, 1, 11, 1, 1, 1, 4, 14, 127, 0, 1, 11, 1, 1, 1, 3, 8, 4, 14, 127, 0, 1, 11, 1, 1, 1, 3, 8,
			4, 14, 127, 0, 1, 11, 1, 1, 1, 4, 14, 127, 0, 1, 11, 1, 1, 1, 4, 14, 127, 0, 1, 11, 1, 1, 1, 3, 8, 4, 14,
			127, 0, 1, 11, 1, 1, 1, 3, 8, 4, 14, 127, 0, 1, 11, 1, 1, 1, 3, 8, 4, 14, 127, 0, 1, 11, 1, 1, 1, 4, 14,
			127, 0, 1, 11, 1, 1, 1, 4, 14, 127, 0, 1, 11, 1, 1, 1, 2, 10, 4, 14, 127, 0, 1, 11, 1, 1, 1, 2, 10, 4, 14,
			127, 0, 1, 11, 1, 1, 1, 2, 10, 4, 14, 127, 0, 1, 11, 1, 1, 1, 2, 10, 4, 14, 127, 0, 1, 11, 1, 1, 1, 2, 10,
			4, 14, 6, 1, 127, 0, 1, 11, 1, 1, 1, 2, 10, 4, 14, 6, 1, 127, endSysex };

	SerialConnection serial;
	SerialConnectionAwait awaiter;

	@BeforeEach
	void setup() throws IOException {
		serial = virtualAvrContainer.serialConnection().clearReceived();
		awaiter = awaiter(serial).withTimeout(Duration.of(20, SECONDS));
	}

	@AfterEach
	void tearDown(TestInfo testInfo) {
		byte[] bytes = serial.receivedBytes();
		if (bytes.length > 0) {
			System.out.println(testInfo.getDisplayName() + ", remaining bytes (" + new String(bytes) + "): ");
			printBytes(bytes);
		}
	}

	@Test
	void doesReceiveInformationOnStartup() throws Exception {
		waitForStartupInfoReceived();
	}

	@Test
	void canQueryFirmware() throws Exception {
		waitForStartupInfoReceived() //
				.sendAwait(sysex(reportFirmwareQuery), b -> Arrays.equals(b, firmwareInfo));
	}

	@Test
	void canQueryCapability() throws Exception {
		waitForStartupInfoReceived() //
				.sendAwait(sysex(capabilityQuery), b -> Arrays.equals(b, capabilities));
	}

	static byte[] concat(byte[]... arrays) {
		return Stream.of(arrays).collect(ByteArrayOutputStream::new, FirmataVirtualAvrIT::write,
				(baos1, baos2) -> baos1.write(baos2.toByteArray(), 0, baos2.size())).toByteArray();
	}

	static void write(ByteArrayOutputStream baos, byte[] bytes) {
		try {
			baos.write(bytes);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	static void printBytes(byte[] bytes) {
		for (int i = 0; i < bytes.length; i++) {
			byte b = bytes[i];
			System.out.printf("%04d: %08d %02x %d%n", i, parseInt(toBinaryString(b & 0xFF)), b, b);
		}
	}

	static byte[] sysex(byte... message) {
		byte[] bytes = new byte[message.length + 2];
		System.arraycopy(message, 0, bytes, 1, message.length);
		bytes[0] = startSysex;
		bytes[bytes.length - 1] = endSysex;
		return bytes;
	}

	SerialConnectionAwait waitForStartupInfoReceived() {
		return awaiter.awaitReceivedBytes(b -> Arrays.equals(b, firmwareStartupInfo))
				.withTimeout(Duration.of(5, SECONDS));
	}

}
