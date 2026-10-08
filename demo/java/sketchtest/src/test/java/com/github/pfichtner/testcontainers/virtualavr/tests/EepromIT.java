package com.github.pfichtner.testcontainers.virtualavr.tests;

import static com.github.pfichtner.testcontainers.virtualavr.IOUtil.withSketchFromClasspath;
import static com.github.pfichtner.testcontainers.virtualavr.SerialConnectionAwait.awaiter;
import static com.github.pfichtner.testcontainers.virtualavr.TestcontainerSupport.virtualAvrContainer;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.pfichtner.testcontainers.virtualavr.SerialConnection;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrContainer;

class EepromIT {

	private static final String SKETCH = "/eeprom/eeprom.ino";

	@Test
	void virginEepromIsFilledWith0xffAndPutGetRoundtripWorks() throws Exception {
		VirtualAvrContainer<?> virtualAvrContainer = newContainer();
		try (SerialConnection serialConnection = virtualAvrContainer.serialConnection()) {
			awaiter(serialConnection).awaitReceived(r -> r.contains("state=fresh virgin=255") //
					&& r.contains("put=ok") //
					&& r.contains("done"));
		} finally {
			virtualAvrContainer.stop();
		}
	}

	@Test
	void eepromContentIsPersistedAcrossContainerRestart(@TempDir File tmpDir) throws Exception {
		File eepromFile = new File(tmpDir, "eeprom.bin");
		VirtualAvrContainer<?> first = newContainer().withEepromFile(eepromFile);
		try (SerialConnection serialConnection = first.serialConnection()) {
			awaiter(serialConnection).awaitReceived(r -> r.contains("state=fresh virgin=255") //
					&& r.contains("put=ok") //
					&& r.contains("done"));
		} finally {
			first.stop();
		}

		VirtualAvrContainer<?> second = newContainer().withEepromFile(eepromFile);
		try (SerialConnection serialConnection = second.serialConnection()) {
			awaiter(serialConnection).awaitReceived(r -> r.contains("state=restored value=4711") //
					&& r.contains("done"));
		} finally {
			second.stop();
		}
	}

	private static VirtualAvrContainer<?> newContainer() {
		return virtualAvrContainer(withSketchFromClasspath(SKETCH));
	}

}
