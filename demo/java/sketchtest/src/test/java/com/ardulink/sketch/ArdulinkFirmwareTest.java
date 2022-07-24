package com.ardulink.sketch;

import static org.testcontainers.containers.BindMode.READ_ONLY;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.SerialConnection;

@Testcontainers
class ArdulinkFirmwareTest {

	String hostDev = "/dev";
	String containerDev = "/dev";
	String ttyDevice = "ttyUSB0";

	String firmware = "/tmp/ArdulinkProtocol.ino";

	@Container
	GenericContainer<?> virtualavr = new GenericContainer<>("pfichtner/virtualavr")
			.withEnv("VIRTUALDEVICE", containerDev + "/" + ttyDevice).withFileSystemBind(hostDev, containerDev)
			.withFileSystemBind(firmware, "/sketch/sketch.ino", READ_ONLY);

	@Test
	void canDetectArduinoThatSendInitialMessage() throws Exception {
		try (SerialConnection connection = new SerialConnection(hostDev + "/" + ttyDevice)) {
			connection.waitReceivedAnything();
		}
	}

	@Test
	void sendsReplyIfReplyRequested() throws Exception {
		String id = "42";
		try (SerialConnection connection = new SerialConnection(hostDev + "/" + ttyDevice)) {
			connection.waitReceivedAnything().sendAwait("alp://notn/?id=" + id + "\n", "alp://rply/ok?id=" + id + "\n");
		}
	}

}
