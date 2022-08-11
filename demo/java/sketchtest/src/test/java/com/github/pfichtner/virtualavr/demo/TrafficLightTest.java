package com.github.pfichtner.virtualavr.demo;

import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.off;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.on;
import static java.lang.Boolean.FALSE;
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
		avr = virtualAvrContainer.avr().clearStates();
	}

	@Test
	void valueEqualsRef_GreenLedIsIOn() {
		int someValue = 1022;
		avr.pinState(REF_PIN, someValue).pinState(VALUE_PIN, someValue);
		awaitUntil(on(GREEN_LED), off(YELLOW_LED), off(RED_LED));
	}

	@Test
	void valueGreaterThenRef_RedLedIsIOn() {
		int someValue = 1022;
		avr.pinState(REF_PIN, someValue).pinState(VALUE_PIN, someValue + 1);
		awaitUntil(off(GREEN_LED), off(YELLOW_LED), on(RED_LED));
	}

	@Test
	void valueGreaterWithin90Percent_YellowLedIsIOn() {
		int ref = 1000;
		avr.pinState(REF_PIN, ref).pinState(VALUE_PIN, ref * 90 / 100 + 1);
		awaitUntil(off(GREEN_LED), on(YELLOW_LED), off(RED_LED));
	}

	void awaitUntil(PinState... states) {
		await().until(() -> statesAre(states));
	}

	boolean statesAre(PinState... states) {
		Map<String, Object> lastStates = avr.lastStates();
		return Arrays.stream(states).allMatch(s -> equal(s.getState(), lastStates.getOrDefault(s.getPin(), FALSE)));
	}

}
