package com.github.pfichtner.testcontainers.virtualavr.demo;

import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinReportMode.DIGITAL;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinState.stateIsOff;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinState.stateIsOn;
import static java.lang.Math.abs;
import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.isEqual;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinState;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrContainer;

@Testcontainers
class VirtualAvrTest {

	private static final String INTERNAL_LED = "13";

	@SuppressWarnings("resource")
	@Container
	VirtualAvrContainer<?> virtualavr = new VirtualAvrContainer<>().withSketchFile(loadClasspath("/blink/blink.ino"));

	static File loadClasspath(String name) {
		try {
			return new File(requireNonNull(VirtualAvrTest.class.getResource(name)).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void awaitHasBlinkedAtLeastFiveTimesAndCpuTimesAreOk() {
		VirtualAvrConnection virtualAvr = virtualavr.avr();
		virtualAvr.pinReportMode(INTERNAL_LED, DIGITAL);
		await().until(() -> count(virtualAvr.pinStates().stream(), isEqual(stateIsOn(INTERNAL_LED))) >= 5
				&& count(virtualAvr.pinStates().stream(), isEqual(stateIsOff(INTERNAL_LED))) >= 5);
		checkCpuTimes(virtualAvr.pinStates(), 0.250);
	}

	static void checkCpuTimes(Iterable<PinState> states, double expected) {
		Iterator<PinState> it = states.iterator();
		if (!it.hasNext()) {
			return;
		}

		PinState prev = it.next();
		while (it.hasNext()) {
			PinState curr = it.next();
			double diff = curr.getCpuTime() - prev.getCpuTime();
			assertThat(abs(diff - expected)).isLessThanOrEqualTo(0.1);
			prev = curr;
		}
	}

	static long count(Stream<PinState> pinStates, Predicate<PinState> pinState) {
		return pinStates.filter(pinState).count();
	}

}
