package com.github.pfichtner.virtualavr.demo;

import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.off;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.on;
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

	static File loadClasspath(String name) {
		try {
			return new File(BlinkFirmwareTest.class.getResource(name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void awaitHasBlinkedAtLeastThreeTimes() {
		VirtualAvrConnection virtualAvr = virtualavr.avr();
		await().until(() -> count(virtualAvr.pinStates(), on(INTERNAL_LED)) >= 3
				&& count(virtualAvr.pinStates(), off(INTERNAL_LED)) >= 3);
	}

	long count(List<PinState> pinStates, Predicate<PinState> pinState) {
		return pinStates.stream().filter(pinState).count();
	}

}
