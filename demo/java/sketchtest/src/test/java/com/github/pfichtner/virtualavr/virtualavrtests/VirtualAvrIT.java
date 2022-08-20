package com.github.pfichtner.virtualavr.virtualavrtests;

import static com.github.pfichtner.virtualavr.SerialConnectionAwait.awaiter;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinReportMode.ANALOG;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinReportMode.DIGITAL;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinReportMode.NONE;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.off;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.on;
import static com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState.stateOfPinIs;
import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.github.pfichtner.virtualavr.SerialConnectionAwait;
import com.github.pfichtner.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.virtualavr.VirtualAvrConnection.PinState;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;

/**
 * Integration test for virtualavr. Fires up the docker container and runs
 * integration tests against it. Features checked are:
 * <ul>
 * <li>Serial communication: Can the AVR read data sent from serial line and can
 * data being received sent by the avr via serial line?
 * <li>Websocket communication: Are the pin state changes propagated via
 * websockets? Can the pin states of the avr beeing controlled via websockets?
 * </ul>
 * 
 * @author Peter Fichtner
 */
@Testcontainers
class VirtualAvrIT {

	private static final String VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME = "virtualavr.docker.tag";

	private static final String INTERNAL_LED = "D13";
	private static final String PWM_PIN = "D10";

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = new VirtualAvrContainer<>(imageName()) //
			.withSketchFile(loadClasspath("/integrationtest.ino")) //
			.withDeviceName("virtualavr" + UUID.randomUUID()) //
			.withDeviceGroup("root") //
			.withDeviceMode(666);

	/**
	 * If you want the version from dockerhub, you have to use <code>latest</code>
	 * as value for {@value #VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME}. <br>
	 * To prevent that we test accidentally the image pulled from dockerhub when
	 * running our integration tests there is <b>NO</b> default value!
	 * 
	 * @return the image name including tag
	 */
	static DockerImageName imageName() {
		String dockerTagName = System.getProperty(VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME);
		if (dockerTagName == null) {
			throw new IllegalStateException("\"" + VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME + "\" property not set!");
		}
		return VirtualAvrContainer.DEFAULT_IMAGE_NAME.withTag(dockerTagName);
	}

	static File loadClasspath(String name) {
		try {
			return new File(VirtualAvrIT.class.getResource(name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void canReadSerial() throws Exception {
		awaiter(virtualAvrContainer.serialConnection()).awaitReceived(r -> r.contains("Welcome virtualavr!"));
	}

	@Test
	void canWriteSerial() throws Exception {
		String send = "Echo Test!";
		awaiter(virtualAvrContainer.serialConnection()).sendAwait(send, r -> r.contains("Echo response: " + send));
	}

	@Test
	void canReadDigitalAndDoesPublishStateChangesViaWebsocket() {
		VirtualAvrConnection virtualAvr = virtualAvrContainer.avr();
		virtualAvr.pinReportMode(INTERNAL_LED, DIGITAL);
		waitForToggles(INTERNAL_LED, 3);
	}

	@Test
	void canReadAnalogAndDoesPublishStateChangesViaWebsocket() {
		VirtualAvrConnection virtualAvr = virtualAvrContainer.avr();
		virtualAvr.pinReportMode(PWM_PIN, ANALOG);
		await().until(() -> virtualAvr.pinStates().stream().anyMatch(stateOfPinIs(PWM_PIN, 42)));
	}

	@Test
	void canSwitchOffListening() throws InterruptedException {
		VirtualAvrConnection virtualAvr = virtualAvrContainer.avr();
		virtualAvr.pinReportMode(INTERNAL_LED, DIGITAL);
		long timeToTogglePinThreeTimes = waitForToggles(INTERNAL_LED, 3);

		virtualAvr.pinReportMode(INTERNAL_LED, NONE);
		// Test could fail because "pinReportMode" is async and there are some messages
		// sent even after #clearStates call. To prevent we should implement an
		// non-async "pause" command to halt/pause the simulator
		await().until(() -> {
			virtualAvr.clearStates();
			MILLISECONDS.sleep(500);
			return virtualAvr.pinStates().isEmpty();
		});

		MILLISECONDS.sleep(timeToTogglePinThreeTimes * 2);
		assertTrue(String.valueOf(virtualAvr.pinStates()),
				virtualAvr.pinStates().stream().noneMatch(s -> INTERNAL_LED.equals(s.getPin())));
	}

	private long waitForToggles(String pin, int times) {
		VirtualAvrConnection virtualAvr = virtualAvrContainer.avr();
		long start = currentTimeMillis();
		await().until(() -> count(virtualAvr.pinStates(), on(pin)) >= times
				&& count(virtualAvr.pinStates(), off(pin)) >= times);
		return currentTimeMillis() - start;
	}

	@Test
	void canSetDigitalPinStateViaWebsocket() throws IOException {
		VirtualAvrConnection virtualAvr = virtualAvrContainer.avr();
		SerialConnectionAwait awaiter = awaiter(virtualAvrContainer.serialConnection());

		// TODO make side-affect-free
		awaiter.awaitReceived(s -> {
			virtualAvr.pinState("D11", true);
			return s.contains("State-Change-D11: ON");
		});

		// TODO make side-affect-free
		awaiter.awaitReceived(s -> {
			virtualAvr.pinState("D11", false);
			return s.contains("State-Change-D11: OFF");
		});
	}

	@Test
	void canSetAnalogPinStateViaWebsocket() throws IOException {
		VirtualAvrConnection virtualAvr = virtualAvrContainer.avr();
		SerialConnectionAwait awaiter = awaiter(virtualAvrContainer.serialConnection());

		// TODO make side-affect-free
		awaiter.awaitReceived(s -> {
			virtualAvr.pinState("A0", 42);
			return s.contains("State-Change-A0: 42");
		});

		// TODO make side-affect-free
		awaiter.awaitReceived(s -> {
			virtualAvr.pinState("A0", 84);
			return s.contains("State-Change-A0: 84");
		});

		// TODO make side-affect-free
		awaiter.awaitReceived(s -> {
			virtualAvr.pinState("A0", 0);
			return s.contains("State-Change-A0: 0");
		});
	}

	long count(List<PinState> pinStates, Predicate<PinState> pinState) {
		return pinStates.stream().filter(pinState).count();
	}

}
