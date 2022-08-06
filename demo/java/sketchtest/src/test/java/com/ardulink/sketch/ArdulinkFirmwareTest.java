package com.ardulink.sketch;

import static com.github.pfichtner.virtualavr.demo.SerialConnectionAwait.awaiter;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.SerialConnection;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;

@Testcontainers
class ArdulinkFirmwareTest {

	@Container
	VirtualAvrContainer<?> virtualavr = new VirtualAvrContainer<>()
			.withSketchFile(new File("/tmp/ArdulinkProtocol.ino"));

	@Test
	void canDetectArduinoThatSendInitialMessage() throws Exception {
		try (SerialConnection connection = virtualavr.serialConnection()) {
			awaiter(connection).waitReceivedAnything();
		}
	}

	@Test
	void sendsReplyIfReplyRequested() throws Exception {
		String id = "42";
		try (SerialConnection connection = virtualavr.serialConnection()) {
			awaiter(connection).waitReceivedAnything().sendAwait("alp://notn/?id=" + id + "\n",
					"alp://rply/ok?id=" + id + "\n");
		}
	}

}
