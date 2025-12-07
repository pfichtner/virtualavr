package com.github.pfichtner.testcontainers.virtualavr.tests;

import static com.github.pfichtner.testcontainers.virtualavr.IOUtil.withSketchFromClasspath;
import static com.github.pfichtner.testcontainers.virtualavr.SerialConnectionAwait.awaiter;
import static com.github.pfichtner.testcontainers.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.testcontainers.virtualavr.SerialConnectionAwait;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrContainer;

@Testcontainers
@EnabledIf("isSocatAvailable")
class TcpSerialModeSupportIT {

	private static final String SOME_NOT_USED_TTY_NAME = "someUnusedTTYName";

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer( //
			withSketchFromClasspath("/byteecho/byteecho.ino")) //
			.withDeviceName(SOME_NOT_USED_TTY_NAME) //
			// force tcp serial mode for test even on linux hosts
			.withTcpSerialMode() //
			// on linux, host.docker.internal is not available by default
			.withExtraHost("host.docker.internal", "host-gateway") //
	;

	@Test
	void startRemovesDevBind() {
		assertThat(virtualAvrContainer.getBinds().stream().map(Object::toString))
				.doesNotContain(VirtualAvrContainer.containerDev);
	}

	@Test
	void canReadAndWriteViaSocatBridge() throws Exception {
		assertThat(virtualAvrContainer.serialPortDescriptor()).doesNotContain(SOME_NOT_USED_TTY_NAME);
		SerialConnectionAwait awaiter = awaiter(virtualAvrContainer.serialConnection());
		for (int i = 0; i < 255; i++) {
			byte[] arr = new byte[] { (byte) i };
			awaiter.sendAwait(arr, b -> Arrays.equals(b, arr));
		}
		awaiter.sendAwait(new byte[] { (byte) 255 }, b -> Arrays.equals(b, new byte[] { (byte) 255, 0 }));
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
