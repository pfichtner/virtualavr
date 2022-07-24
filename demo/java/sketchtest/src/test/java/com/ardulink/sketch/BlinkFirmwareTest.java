package com.ardulink.sketch;

import static com.ardulink.sketch.VirtualAvrConnection.connectionToVirtualAvr;
import static com.ardulink.sketch.VirtualAvrConnection.PinState.switchedOff;
import static com.ardulink.sketch.VirtualAvrConnection.PinState.switchedOn;
import static org.awaitility.Awaitility.await;
import static org.testcontainers.containers.BindMode.READ_ONLY;

import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ardulink.sketch.VirtualAvrConnection.PinState;

@Testcontainers
class BlinkFirmwareTest {

	private static final int INTERNAL_LED = 13;

	String hostDev = "/dev";
	String containerDev = "/dev";
	String ttyDevice = "ttyUSB0";

	String firmware = "blink.ino";

	@Container
	GenericContainer<?> virtualavr = new GenericContainer<>("pfichtner/virtualavr")
			.withEnv("VIRTUALDEVICE", containerDev + "/" + ttyDevice) //
			.withFileSystemBind(hostDev, containerDev) //
			.withClasspathResourceMapping(firmware, "/sketch/sketch.ino", READ_ONLY) //
			.withExposedPorts(8080) //
	;

	@Test
	void awaitHasBlinkedAtLeastThreeTimes() {
		try (VirtualAvrConnection virtualAvrCon = connectionToVirtualAvr(virtualavr)) {
			await().until(() -> count(virtualAvrCon.pinStates(), switchedOn(INTERNAL_LED)) >= 3
					&& count(virtualAvrCon.pinStates(), switchedOff(INTERNAL_LED)) >= 3);
		}
	}

	long count(List<PinState> pinStates, Predicate<PinState> pinState) {
		return pinStates.stream().filter(pinState).count();
	}

}
