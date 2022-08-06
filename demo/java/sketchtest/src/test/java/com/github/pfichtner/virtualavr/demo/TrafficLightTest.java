package com.github.pfichtner.virtualavr.demo;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.net.URISyntaxException;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;

@Testcontainers
class TrafficLightTest {

	private static final String VALUE_PIN = "A1";
	private static final String REF_PIN = "A0";

	private static final String RED_LED = "D12";
	private static final String YELLOW_LED = "D11";
	private static final String GREEN_LED = "D10";

	@Container
	static VirtualAvrContainer<?> virtualavr = new VirtualAvrContainer<>()
			.withSketchFile(loadClasspath("/trafficlight.ino"));
	static VirtualAvrConnection avr;

	private static File loadClasspath(String name) {
		try {
			return new File(TrafficLightTest.class.getResource(name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	@BeforeAll
	static void beforeAll() {
		avr = virtualavr.avr();
	}

	@BeforeEach
	void beforeEach() {
		avr.clearStates();
	}

	@Test
	void valueEqualsRef_GreenLedIsIOn() {
		int someValue = 1022;
		avr.pinState(REF_PIN, someValue).pinState(VALUE_PIN, someValue);
		await().until(() -> statesAre(GREEN_LED, TRUE, YELLOW_LED, FALSE, RED_LED, FALSE));
	}

	@Test
	void valueGreaterThenRef_RedLedIsIOn() {
		int someValue = 1022;
		avr.pinState(REF_PIN, someValue).pinState(VALUE_PIN, someValue + 1);
		await().until(() -> statesAre(GREEN_LED, FALSE, YELLOW_LED, FALSE, RED_LED, TRUE));
	}

	@Test
	void valueGreaterWithin90Percent_YellowLedIsIOn() {
		int ref = 100;
		avr.pinState(REF_PIN, ref).pinState(VALUE_PIN, ref * 90 / 100 + 1);
		await().until(() -> statesAre(GREEN_LED, FALSE, YELLOW_LED, TRUE, RED_LED, FALSE));
	}

	private boolean statesAre(String led0, Boolean state0, String led1, Boolean state1, String led2, Boolean state2) {
		Map<String, Object> lastStates = avr.lastStates();
		return state0.equals(lastStates.getOrDefault(led0, FALSE)) //
				&& state1.equals(lastStates.getOrDefault(led1, FALSE)) //
				&& state2.equals(lastStates.getOrDefault(led2, FALSE)) //
		;
	}

}
