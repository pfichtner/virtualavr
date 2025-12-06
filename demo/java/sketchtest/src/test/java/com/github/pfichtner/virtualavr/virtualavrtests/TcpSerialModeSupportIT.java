package com.github.pfichtner.virtualavr.virtualavrtests;

import static com.github.pfichtner.virtualavr.IOUtil.withSketchFromClasspath;
import static com.github.pfichtner.virtualavr.SerialConnectionAwait.awaiter;
import static com.github.pfichtner.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.SerialConnection;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;

@Testcontainers
@EnabledIf("isSocatAvailable")
class TcpSerialModeSupportIT {

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer(
			withSketchFromClasspath("/integrationtest/integrationtest.ino")) //
			.withPausedStartup() //
			// force tcp serial mode for test even on linux hosts
			.withTcpSerialMode() //
			// on linux, host.docker.internal is not available by default
			.withExtraHost("host.docker.internal", "host-gateway") //
	;

	@Test
	void startRemovesDevBind() throws Exception {
		assertThat(virtualAvrContainer.getBinds().stream().map(Object::toString))
				.doesNotContain(VirtualAvrContainer.containerDev);
	}

	@Test
	void serialWorksViaSocatWrapperOnHost() throws Exception {
		SerialConnection serialConnection = virtualAvrContainer.serialConnection();
		TimeUnit.SECONDS.sleep(10);
		assertThat(serialConnection.received()).isEmpty();

		virtualAvrContainer.avr().unpause();
		awaiter(serialConnection).awaitReceived(r -> r.contains("Welcome virtualavr!"));
	}

	static boolean isSocatAvailable() {
		try {
			Process process = new ProcessBuilder("socat", "-h").redirectErrorStream(true).start();
			process.waitFor(2, SECONDS);
			return process.exitValue() == 0 || process.exitValue() == 1;
		} catch (IOException | InterruptedException e) {
			return false;
		}
	}

}