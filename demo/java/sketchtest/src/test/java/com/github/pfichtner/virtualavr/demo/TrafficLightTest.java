package com.github.pfichtner.virtualavr.demo;

import static com.github.pfichtner.virtualavr.demo.TrafficLightTest.State.state;
import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;

@Testcontainers
class TrafficLightTest {

	static class State {

		private final String led;
		private final Boolean state;

		public State(String led, Boolean state) {
			this.led = led;
			this.state = state;
		}

		public static State state(String led, Boolean state) {
			return new State(led, state);
		}
	}

	private static final String VALUE_PIN = "A1";
	private static final String REF_PIN = "A0";

	private static final String GREEN_LED = "D10";
	private static final String YELLOW_LED = "D11";
	private static final String RED_LED = "D12";

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

	@BeforeEach
	void beforeEach() {
		avr = virtualavr.avr();
		avr.clearStates();
	}

	@Test
	void valueEqualsRef_GreenLedIsIOn() {
		int someValue = 1022;
		avr.pinState(REF_PIN, someValue).pinState(VALUE_PIN, someValue);
		await().until(() -> statesAre(state(GREEN_LED, TRUE), state(YELLOW_LED, FALSE), state(RED_LED, FALSE)));
	}

	@Test
	void valueGreaterThenRef_RedLedIsIOn() {
		int someValue = 1022;
		avr.pinState(REF_PIN, someValue).pinState(VALUE_PIN, someValue + 1);
		await().until(() -> statesAre(state(GREEN_LED, FALSE), state(YELLOW_LED, FALSE), state(RED_LED, TRUE)));
	}

	@Test
	void valueGreaterWithin90Percent_YellowLedIsIOn() {
		int ref = 100;
		avr.pinState(REF_PIN, ref).pinState(VALUE_PIN, ref * 90 / 100 + 1);
		await().until(() -> statesAre(state(GREEN_LED, FALSE), state(YELLOW_LED, TRUE), state(RED_LED, FALSE)));
	}

	private boolean statesAre(State... states) {
		Map<String, Object> lastStates = avr.lastStates();
		return Arrays.stream(states).allMatch(s -> s.state.equals(lastStates.getOrDefault(s.led, FALSE)));
	}

}
