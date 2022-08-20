package com.github.pfichtner.virtualavr.virtualavrtests;

import static com.github.pfichtner.virtualavr.SerialConnectionAwait.awaiter;
import static java.lang.Integer.parseInt;
import static java.lang.Integer.toBinaryString;
import static java.time.temporal.ChronoUnit.SECONDS;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.github.pfichtner.virtualavr.SerialConnection;
import com.github.pfichtner.virtualavr.SerialConnectionAwait;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;

@Testcontainers
class FirmataVirtualAvrIT {

	private static final String VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME = "virtualavr.docker.tag";

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = new VirtualAvrContainer<>(imageName()) //
			.withSketchFile(loadClasspath("/firmata-project.zip")) //
			.withDeviceName("virtualavr" + UUID.randomUUID()) //
			.withBaudrate(57600) //
			.withDeviceGroup("root") //
			.withDeviceMode(666) //
	;

	/**
	 * If you want the version from dockerhub, you have to use <code>latest</code>
	 * as value for {@value #VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME}. <br>
	 * To prevent that we test accidentally the image pulled from dockerhub when
	 * running our integration tests there is <b>NO</b> default value!
	 * 
	 * @return the image name including tag
	 */
	static DockerImageName imageName() {
		String dockerTagName = System.getProperty(VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME);
		if (dockerTagName == null) {
			throw new IllegalStateException("\"" + VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME + "\" property not set!");
		}
		return VirtualAvrContainer.DEFAULT_IMAGE_NAME.withTag(dockerTagName);
	}

	static File loadClasspath(String name) {
		try {
			return new File(FirmataVirtualAvrIT.class.getResource(name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	byte startSysex = (byte) 0xF0;
	byte reportFirmwareQuery = 0x79;
	byte capabilityQuery = 0x6B;
	byte endSysex = (byte) 0xF7;

	byte[] firmwareStartupInfo = new byte[] { -61, -71, 2, 5, -61, -80, 121, 2, 5, 115, 0, 107, 0, 101, 0, 116, 0, 99,
			0, 104, 0, 46, 0, 105, 0, 110, 0, 111, 0, -61, -73 };
	byte[] firmwareInfo = new byte[] { -61, -80, 121, 2, 5, 115, 0, 107, 0, 101, 0, 116, 0, 99, 0, 104, 0, 46, 0, 105,
			0, 110, 0, 111, 0, -61, -73 };
	byte[] capabilities = new byte[] { -61, -80, 108, 127, 127, 0, 1, 11, 1, 1, 1, 4, 14, 127, 0, 1, 11, 1, 1, 1, 3, 8,
			4, 14, 127, 0, 1, 11, 1, 1, 1, 4, 14, 127, 0, 1, 11, 1, 1, 1, 3, 8, 4, 14, 127, 0, 1, 11, 1, 1, 1, 3, 8, 4,
			14, 127, 0, 1, 11, 1, 1, 1, 4, 14, 127, 0, 1, 11, 1, 1, 1, 4, 14, 127, 0, 1, 11, 1, 1, 1, 3, 8, 4, 14, 127,
			0, 1, 11, 1, 1, 1, 3, 8, 4, 14, 127, 0, 1, 11, 1, 1, 1, 3, 8, 4, 14, 127, 0, 1, 11, 1, 1, 1, 4, 14, 127, 0,
			1, 11, 1, 1, 1, 4, 14, 127, 0, 1, 11, 1, 1, 1, 2, 10, 4, 14, 127, 0, 1, 11, 1, 1, 1, 2, 10, 4, 14, 127, 0,
			1, 11, 1, 1, 1, 2, 10, 4, 14, 127, 0, 1, 11, 1, 1, 1, 2, 10, 4, 14, 127, 0, 1, 11, 1, 1, 1, 2, 10, 4, 14, 6,
			1, 127, 0, 1, 11, 1, 1, 1, 2, 10, 4, 14, 6, 1, 127, -61, -73 };

	SerialConnection serialConnection;
	SerialConnectionAwait awaiter;

	@BeforeEach
	void setup() throws IOException {
		serialConnection = virtualAvrContainer.serialConnection().clearReceived();
		awaiter = awaiter(serialConnection).withTimeout(Duration.of(20, SECONDS));
	}

	@AfterEach
	void tearDown(TestInfo testInfo) {
		byte[] bytes = serialConnection.receivedBytes();
		if (bytes.length > 0) {
			System.out.println(testInfo.getDisplayName() + ", remaining bytes (" + new String(bytes) + "): ");
			printBytes(bytes);
		}
	}

	private void printBytes(byte[] bytes) {
		for (int i = 0; i < bytes.length; i++) {
			byte b = bytes[i];
			System.out.print(String.format("%04d", i));
			System.out.print(": ");
			System.out.print(String.format("%08d", parseInt(toBinaryString(b & 0xFF))));
			System.out.print(" ");
			System.out.print(String.format("%02x", b));
			System.out.print(" ");
			System.out.println(b);
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

	byte[] sysex(byte... message) {
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
