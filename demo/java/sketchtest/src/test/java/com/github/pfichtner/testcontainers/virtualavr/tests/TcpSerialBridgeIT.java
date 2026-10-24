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

@Testcontainers
@EnabledIf("com.github.pfichtner.testcontainers.virtualavr.TcpSerialBridge#isSocatAvailable")
class TcpSerialBridgeIT {

	private static final String SOME_NOT_USED_TTY_NAME = "someUnusedTTYName";

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer( //
			withSketchFromClasspath("/byteecho/byteecho.ino")) //
			.withDeviceName(SOME_NOT_USED_TTY_NAME) //
			// force the host serial bridge for test even on linux hosts
			.withTcpSerialBridge() //
	;

	@Test
	void startRemovesDevBind() {
		assertThat(virtualAvrContainer.getBinds().stream().map(Object::toString))
				.doesNotContain(VirtualAvrContainer.containerDev);
	}

	@Test
	void simulatorIsUpWhenTheContainerIsStarted() {
		// the bridge triggers the start of the simulator, so the WebSocket must be
		// usable right after the container was started
		assertThat(virtualAvrContainer.avr().isConnected()).isTrue();
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

}
