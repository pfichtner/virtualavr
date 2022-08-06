package com.github.pfichtner.virtualavr.demo;

import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.switchedOff;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.switchedOn;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.net.URISyntaxException;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;
import com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState;

@Testcontainers
class BlinkFirmwareTest {

	private static final int INTERNAL_LED = 13;

	@Container
	VirtualAvrContainer<?> virtualavr = new VirtualAvrContainer<>().withSketchFile(loadClasspath("/blink.ino"));

	private static File loadClasspath(String name) {
		try {
			return new File(BlinkFirmwareTest.class.getResource(name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void awaitHasBlinkedAtLeastThreeTimes() {
		try (VirtualAvrConnection virtualAvrCon = virtualavr.avr()) {
			await().until(() -> count(virtualAvrCon.pinStates(), switchedOn(INTERNAL_LED)) >= 3
					&& count(virtualAvrCon.pinStates(), switchedOff(INTERNAL_LED)) >= 3);
		}
	}

	long count(List<PinState> pinStates, Predicate<PinState> pinState) {
		return pinStates.stream().filter(pinState).count();
	}

}
