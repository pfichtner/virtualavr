package com.ardulink.sketch;

import static com.github.pfichtner.virtualavr.VirtualAvrConnection.connectionToVirtualAvr;
import static java.lang.Boolean.TRUE;
import static org.awaitility.Awaitility.await;
import static org.testcontainers.containers.BindMode.READ_ONLY;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.VirtualAvrConnection;

@Testcontainers
class TrafficLightTest {

	private static final String VALUE_PIN = "A1";
	private static final String REF_PIN = "A0";

	private static final String RED_LED = "D12";
	private static final String YELLOW_LED = "D11";
	private static final String GREEN_LED = "D10";

	private static final int WEBSOCKET_PORT = 8080;

	static String hostDev = "/dev";
	static String containerDev = "/dev";
	static String ttyDevice = "ttyUSB0";

	@Container
	static GenericContainer<?> virtualavr = new GenericContainer<>("pfichtner/virtualavr")
			.withEnv("VIRTUALDEVICE", containerDev + "/" + ttyDevice) //
			.withFileSystemBind(hostDev, containerDev) //
			.withClasspathResourceMapping("trafficlight.ino", "/sketch/sketch.ino", READ_ONLY) //
			.withExposedPorts(WEBSOCKET_PORT) //
	;
	static VirtualAvrConnection avr;

	@BeforeAll
	static void beforeAll() {
		avr = connectionToVirtualAvr(virtualavr);
	}

	@BeforeEach
	void beforeEach() {
		avr.clearStates();
	}

	@Test
	void valueEqualsRef_GreenLedIsIOn() {
		int someValue = 1022;
		avr.setPinState(REF_PIN, someValue).setPinState(VALUE_PIN, someValue);
		await().until(() -> TRUE.equals(avr.lastStates().get(GREEN_LED)));
	}

	@Test
	void valueGreaterThenRef_RedLedIsIOn() {
		int someValue = 1022;
		avr.setPinState(REF_PIN, someValue).setPinState(VALUE_PIN, someValue + 1);
		await().until(() -> TRUE.equals(avr.lastStates().get(RED_LED)));
	}

	@Test
	void valueGreaterWithin90Percent_YellowLedIsIOn() {
		int ref = 100;
		avr.setPinState(REF_PIN, ref).setPinState(VALUE_PIN, ref * 90 / 100 + 1);
		await().until(() -> TRUE.equals(avr.lastStates().get(YELLOW_LED)));
	}

}
