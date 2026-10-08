package com.github.pfichtner.testcontainers.virtualavr.tests;

import static com.github.pfichtner.testcontainers.virtualavr.IOUtil.withSketchFromClasspath;
import static com.github.pfichtner.testcontainers.virtualavr.SerialConnectionAwait.awaiter;
import static com.github.pfichtner.testcontainers.virtualavr.TestcontainerSupport.virtualAvrContainer;

import java.io.File;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.pfichtner.testcontainers.virtualavr.SerialConnection;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrContainer;

class EepromIT {

	private static final String SKETCH = "/eeprom/eeprom.ino";

	@Test
	void virginEepromIsFilledWith0xffAndPutGetRoundtripWorks() throws Exception {
		VirtualAvrContainer<?> virtualAvrContainer = newContainer();
		virtualAvrContainer.start();
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
		Predicate<String> predicateRun1 = r -> r.contains("state=fresh virgin=255") //
				&& r.contains("put=ok") //
				&& r.contains("done");
		Predicate<String> predicateRun2 = r -> r.contains("state=restored value=4711") //
				&& r.contains("done");

		for (Predicate<String> predicate : List.of(predicateRun1, predicateRun2)) {
			VirtualAvrContainer<?> virtualAvrContainer = newContainer().withEepromFile(new File(tmpDir, "eeprom.bin"));
			virtualAvrContainer.start();
			try (SerialConnection serialConnection = virtualAvrContainer.serialConnection()) {
				awaiter(serialConnection).awaitReceived(predicate);
			} finally {
				virtualAvrContainer.stop();
			}
		}
	}

	private static VirtualAvrContainer<?> newContainer() {
		return virtualAvrContainer(withSketchFromClasspath(SKETCH));
	}

}
