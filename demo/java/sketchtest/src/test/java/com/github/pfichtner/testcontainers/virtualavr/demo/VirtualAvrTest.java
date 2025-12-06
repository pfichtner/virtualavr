package com.github.pfichtner.testcontainers.virtualavr.demo;

import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinReportMode.DIGITAL;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinState.stateIsOff;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinState.stateIsOn;
import static java.lang.Math.abs;
import static java.util.function.Predicate.isEqual;
import static java.util.stream.IntStream.range;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.net.URISyntaxException;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrContainer;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinState;

@Testcontainers
class VirtualAvrTest {

	private static final String INTERNAL_LED = "13";

	@Container
	VirtualAvrContainer<?> virtualavr = new VirtualAvrContainer<>().withSketchFile(loadClasspath("/blink/blink.ino"));

	static File loadClasspath(String name) {
		try {
			return new File(VirtualAvrTest.class.getResource(name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void awaitHasBlinkedAtLeastFiveTimesAndCpuTimesAreOk() {
		VirtualAvrConnection virtualAvr = virtualavr.avr();
		virtualAvr.pinReportMode(INTERNAL_LED, DIGITAL);
		await().until(() -> count(virtualAvr.pinStates(), isEqual(stateIsOn(INTERNAL_LED))) >= 5
				&& count(virtualAvr.pinStates(), isEqual(stateIsOff(INTERNAL_LED))) >= 5);
		checkCpuTimes(virtualAvr.pinStates(), 0.250);
	}

	private static void checkCpuTimes(List<PinState> states, double expected) {
		assertThat(range(1, states.size()) //
				.mapToDouble(i -> states.get(i).getCpuTime() - states.get(i - 1).getCpuTime()))
				.allMatch(diff -> abs(diff - expected) <= 0.1);
	}

	long count(List<PinState> pinStates, Predicate<PinState> pinState) {
		return pinStates.stream().filter(pinState).count();
	}

}
