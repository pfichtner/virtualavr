package com.github.pfichtner.testcontainers.virtualavr.tests;

import static com.github.pfichtner.testcontainers.virtualavr.IOUtil.withSketchFromClasspath;
import static com.github.pfichtner.testcontainers.virtualavr.SerialConnectionAwait.awaiter;
import static com.github.pfichtner.testcontainers.virtualavr.TestcontainerSupport.virtualAvrContainer;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.github.pfichtner.testcontainers.virtualavr.SerialConnection;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrContainer;

class EepromIT {

	private static final String SKETCH = "/eeprom/eeprom.ino";

	@Test
	void virginEepromIsFilledWith0xffAndPutGetRoundtripWorks() throws Exception {
		VirtualAvrContainer<?> virtualAvrContainer = newContainer();
		virtualAvrContainer.start();
		try (SerialConnection serialConnection = virtualAvrContainer.serialConnection()) {
			virtualAvrContainer.avr().unpause();
			awaiter(serialConnection).awaitReceived(r -> r.contains("state=fresh virgin=255") //
					&& r.contains("put=ok") //
					&& r.contains("done"));
		} finally {
			virtualAvrContainer.stop();
		}
	}

	@Test
	void eepromContentIsPersistedAcrossContainerRestart() throws Exception {
		File eepromFile = File.createTempFile("virtualavr-eeprom-", ".bin");
		try {
			VirtualAvrContainer<?> first = newContainer().withEepromFile(eepromFile);
			first.start();
			try (SerialConnection serialConnection = first.serialConnection()) {
				first.avr().unpause();
				awaiter(serialConnection).awaitReceived(r -> r.contains("state=fresh virgin=255") //
						&& r.contains("put=ok") //
						&& r.contains("done"));
			} finally {
				first.stop();
			}

			VirtualAvrContainer<?> second = newContainer().withEepromFile(eepromFile);
			second.start();
			try (SerialConnection serialConnection = second.serialConnection()) {
				second.avr().unpause();
				awaiter(serialConnection).awaitReceived(r -> r.contains("state=restored value=4711") //
						&& r.contains("done"));
			} finally {
				second.stop();
			}
		} finally {
			eepromFile.delete();
		}
	}

	private static VirtualAvrContainer<?> newContainer() {
		return virtualAvrContainer(withSketchFromClasspath(SKETCH)).withPausedStartup();
	}

}
