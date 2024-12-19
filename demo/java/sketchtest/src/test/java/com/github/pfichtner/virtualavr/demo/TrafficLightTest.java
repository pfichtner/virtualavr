package com.github.pfichtner.virtualavr.demo;

import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinReportMode.DIGITAL;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.stateIsOff;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.stateIsOn;
import static org.awaitility.Awaitility.await;
import static org.testcontainers.shaded.com.google.common.base.Objects.equal;

import java.io.File;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;

@Testcontainers
class TrafficLightTest {

	private static final String REF_PIN = "A0";
	private static final String VALUE_PIN = "A1";

	private static final String GREEN_LED = "D10";
	private static final String YELLOW_LED = "D11";
	private static final String RED_LED = "D12";

	@Container
	static VirtualAvrContainer<?> virtualAvrContainer = new VirtualAvrContainer<>()
			.withSketchFile(loadClasspath("/trafficlight.ino"));
	static VirtualAvrConnection avr;

	static File loadClasspath(String name) {
		try {
			return new File(TrafficLightTest.class.getResource(name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	@BeforeEach
	void beforeEach() {
		avr = virtualAvrContainer.avr() //
				.pinReportMode(GREEN_LED, DIGITAL) //
				.pinReportMode(YELLOW_LED, DIGITAL) //
				.pinReportMode(RED_LED, DIGITAL) //
				.clearStates();
	}

	@Test
	void valueEqualsIs90PercentOfRef_GreenLedIsOn() {
		int someValue = 1000;
		avr.pinState(REF_PIN, someValue).pinState(VALUE_PIN, someValue * 90 / 100);
		awaitUntil(stateIsOn(GREEN_LED), stateIsOff(YELLOW_LED), stateIsOff(RED_LED));
	}

	@Test
	void valueGreaterThenRef_RedLedIsOn() {
		int someValue = 1023;
		avr.pinState(REF_PIN, someValue - 1).pinState(VALUE_PIN, someValue);
		awaitUntil(stateIsOff(GREEN_LED), stateIsOff(YELLOW_LED), stateIsOn(RED_LED));
	}

	@Test
	void valueGreaterWithin90Percent_YellowLedIsOn() {
		int ref = 1000;
		avr.pinState(REF_PIN, ref).pinState(VALUE_PIN, ref * 90 / 100 + 1);
		awaitUntil(stateIsOff(GREEN_LED), stateIsOn(YELLOW_LED), stateIsOff(RED_LED));
	}

	void awaitUntil(PinState... states) {
		await().until(() -> statesAre(states));
	}

	boolean statesAre(PinState... states) {
		Map<String, Object> lastStates = avr.lastStates();
		return Arrays.stream(states).allMatch(s -> equal(s.getState(), lastStates.get(s.getPin())));
	}

}
