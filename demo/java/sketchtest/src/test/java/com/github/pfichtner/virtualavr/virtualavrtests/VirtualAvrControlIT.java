package com.github.pfichtner.virtualavr.virtualavrtests;

import static com.github.pfichtner.virtualavr.IOUtil.withSketchFromClasspath;
import static com.github.pfichtner.virtualavr.SerialConnectionAwait.awaiter;
import static com.github.pfichtner.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.SerialConnection;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;

/**
 * Integration test for virtualavr. Fires up the docker container and runs
 * integration tests against it. Features checked are:
 * <ul>
 * <li>Serial communication: Can the AVR read data sent from serial line and can
 * data being received sent by the avr via serial line?
 * <li>Websocket communication: Are the pin state changes propagated via
 * websockets? Can the pin states of the avr being controlled via websockets?
 * </ul>
 * 
 * @author Peter Fichtner
 */
@Testcontainers
class VirtualAvrControlIT {

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer(withSketchFromClasspath("/integrationtest.ino"))
			.withPausedStartup();

	@Test
	void withoutExecutionNoStartupMessageGetsSent() throws Exception {
		SerialConnection serialConnection = virtualAvrContainer.serialConnection();
		TimeUnit.SECONDS.sleep(10);
		assertThat(serialConnection.received()).isEmpty();

		virtualAvrContainer.avr().unpause();
		awaiter(serialConnection).awaitReceived(r -> r.contains("Welcome virtualavr!"));
	}

}
