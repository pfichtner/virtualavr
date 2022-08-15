package com.ardulink.sketch;

import static com.github.pfichtner.virtualavr.SerialConnectionAwait.awaiter;

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

}
