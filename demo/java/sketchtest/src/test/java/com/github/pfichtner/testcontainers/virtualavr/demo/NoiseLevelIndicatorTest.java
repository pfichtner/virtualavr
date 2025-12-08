package com.github.pfichtner.testcontainers.virtualavr.demo;

import static com.github.pfichtner.testcontainers.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinReportMode.DIGITAL;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinState.stateIsOff;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinState.stateIsOn;
import static java.lang.String.format;
import static org.awaitility.Awaitility.await;
import static org.testcontainers.shaded.com.google.common.base.Objects.equal;

import java.io.File;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrContainer;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinState;

@Testcontainers
class NoiseLevelIndicatorTest {

	private static final String REF_PIN = "A0";
	private static final String VALUE_PIN = "A1";

	private static final String GREEN_LED = "10";
	private static final String YELLOW_LED = "11";
	private static final String RED_LED = "12";

	@Container
	static VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer(property("virtualavr.sketchfile"));

	private static File property(String name) {
		return Optional.ofNullable(System.getProperty(name)) //
				.map(File::new) //
				.orElseThrow(() -> new IllegalStateException(format("env var %s not set", name)));
	}

	static VirtualAvrConnection avr;

	@BeforeEach
	void beforeEach() {
		avr = virtualAvrContainer.avr() //
				.pinReportMode(GREEN_LED, DIGITAL) //
				.pinReportMode(YELLOW_LED, DIGITAL) //
				.pinReportMode(RED_LED, DIGITAL) //
		;
	}

	@Test
	void whenTheNoiseLevelIsWithin90PercentOfTheReferenceThenTheGreenLedIsOn() {
		int someValue = 1000;
		avr.pinState(REF_PIN, someValue).pinState(VALUE_PIN, someValue * 90 / 100);
		awaitUntil(stateIsOn(GREEN_LED), stateIsOff(YELLOW_LED), stateIsOff(RED_LED));
	}

	@Test
	void whenTheNoiseLevelIsSlightlyAbove90PercentOfTheReferenceThenTheYellowLedIsOn() {
		int ref = 1000;
		avr.pinState(REF_PIN, ref).pinState(VALUE_PIN, ref * 90 / 100 + 1);
		awaitUntil(stateIsOff(GREEN_LED), stateIsOn(YELLOW_LED), stateIsOff(RED_LED));
	}

	@Test
	void whenThenNoiseLevelExceedsTheReferenceThenTheRedLedIsOn() {
		int someValue = 1023;
		avr.pinState(REF_PIN, someValue - 1).pinState(VALUE_PIN, someValue);
		awaitUntil(stateIsOff(GREEN_LED), stateIsOff(YELLOW_LED), stateIsOn(RED_LED));
	}

	void awaitUntil(PinState... states) {
		await().until(() -> statesAre(states));
	}

	boolean statesAre(PinState... states) {
		Map<String, Object> lastStates = avr.lastStates();
		return Arrays.stream(states).allMatch(s -> equal(s.getState(), lastStates.get(s.getPin())));
	}

}
