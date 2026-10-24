package com.github.pfichtner.testcontainers.virtualavr.tests;

import static com.github.pfichtner.testcontainers.virtualavr.IOUtil.withSketchFromClasspath;
import static com.github.pfichtner.testcontainers.virtualavr.SerialConnectionAwait.awaiter;
import static com.github.pfichtner.testcontainers.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.testcontainers.virtualavr.SerialConnectionAwait;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrContainer;

/**
 * Covers the debug mode of the host side bridge: the socat on the host writes to
 * a log file instead of the console and runs verbosely.
 */
@Testcontainers
@EnabledIf("com.github.pfichtner.testcontainers.virtualavr.TcpSerialBridge#isSocatAvailable")
class TcpSerialBridgeWithDebugIT {

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer( //
			withSketchFromClasspath("/byteecho/byteecho.ino")) //
			// force the host serial bridge for test even on linux hosts
			.withTcpSerialBridge() //
			.withDebug() //
	;

	@Test
	void bridgeIsConnectedAndSerialEchoWorks() throws Exception {
		// the socat in the container logs verbosely because of the debug mode
		assertThat(virtualAvrContainer.getLogs()) //
				.contains("Using TCP serial listen mode") //
				.contains("N accepting connection from");

		SerialConnectionAwait awaiter = awaiter(virtualAvrContainer.serialConnection());
		byte[] arr = new byte[] { 42 };
		awaiter.sendAwait(arr, b -> Arrays.equals(b, arr));
	}

}
