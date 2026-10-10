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

	Predicate<String> isFreshState = r -> r.contains("state=fresh virgin=255") //
			&& r.contains("put=ok") //
			&& r.contains("done");
	Predicate<String> isRestoredState = r -> r.contains("state=restored value=4711") //
			&& r.contains("done");

	@Test
	void virginEepromIsFilledWith0xffAndPutGetRoundtripWorks() throws Exception {
		VirtualAvrContainer<?> virtualAvrContainer = start(newContainer());
		try (SerialConnection serialConnection = virtualAvrContainer.serialConnection()) {
			awaiter(serialConnection).awaitReceived(isFreshState);
		} finally {
			virtualAvrContainer.stop();
		}
	}

	@Test
	void eepromContentIsPersistedAcrossContainerRestart(@TempDir File tmpDir) throws Exception {
		for (Predicate<String> predicate : List.of(isFreshState, isRestoredState)) {
			VirtualAvrContainer<?> virtualAvrContainer = start(
					newContainer().withEepromFile(new File(tmpDir, "eeprom.bin")));
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

	private static VirtualAvrContainer<?> start(VirtualAvrContainer<?> virtualAvrContainer) {
		virtualAvrContainer.start();
		return virtualAvrContainer;
	}

}
