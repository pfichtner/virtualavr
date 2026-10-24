package com.github.pfichtner.testcontainers.virtualavr.tests;

import static com.github.pfichtner.testcontainers.virtualavr.IOUtil.withSketchFromClasspath;
import static com.github.pfichtner.testcontainers.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinReportMode.DIGITAL;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinState.stateIsOff;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinState.stateIsOn;
import static java.lang.System.currentTimeMillis;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinState;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrContainer;

/**
 * Verifies that the WebSocket on port 8080 can still be used for pin control
 * and pin reporting when the serial connection is a plain TCP socket
 * ({@code SERIAL_TCP_LISTEN}), not a PTY.
 */
@Testcontainers
class TcpSerialListenModeWebSocketIT {

	private static final String INTERNAL_LED = "13";
	private static final String DIGITAL_INPUT = "11";
	private static final int PUBLISH_MILLIS = 100 / 3;
	private static final int CONNECT_TIMEOUT_MILLIS = 1_000;
	private static final int WELCOME_TIMEOUT_MILLIS = 2_000;
	private static final int READ_TIMEOUT_MILLIS = 20_000;

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer( //
			withSketchFromClasspath("/integrationtest/integrationtest.ino")) //
			.withPublishMillis(PUBLISH_MILLIS) //
			.withTcpSerialListenMode();

	@Test
	void websocketPinControlAndReportingWorkWhileSerialIsConnectedViaTcp() throws Exception {
		try (Socket socket = connectWhenWelcomeReceived()) {
			VirtualAvrConnection avr = virtualAvrContainer.avr();
			await().atMost(10, SECONDS).until(avr::isConnected);

			avr.pinReportMode(INTERNAL_LED, DIGITAL);
			await().atMost(10, SECONDS).until(() -> reported(avr, stateIsOn(INTERNAL_LED))
					&& reported(avr, stateIsOff(INTERNAL_LED)));

			InputStream in = socket.getInputStream();
			avr.pinState(DIGITAL_INPUT, true);
			assertThat(containsWithin(in, "State-Change-11: ON", READ_TIMEOUT_MILLIS)).isTrue();
			avr.pinState(DIGITAL_INPUT, false);
			assertThat(containsWithin(in, "State-Change-11: OFF", READ_TIMEOUT_MILLIS)).isTrue();
		}
	}

	private static boolean reported(VirtualAvrConnection avr, PinState state) {
		return avr.pinStates().stream().anyMatch(state::equals);
	}

	private Socket connectWhenWelcomeReceived() {
		AtomicReference<Socket> connected = new AtomicReference<>();
		await().atMost(60, SECONDS).pollInterval(500, MILLISECONDS).until(() -> {
			Socket socket = new Socket();
			try {
				socket.connect(new InetSocketAddress(virtualAvrContainer.getHost(),
						virtualAvrContainer.serialTcpListenPort()), CONNECT_TIMEOUT_MILLIS);
				socket.setSoTimeout(WELCOME_TIMEOUT_MILLIS);
				if (containsWithin(socket.getInputStream(), "Welcome virtualavr!", WELCOME_TIMEOUT_MILLIS)) {
					socket.setSoTimeout(READ_TIMEOUT_MILLIS);
					connected.set(socket);
					return true;
				}
			} catch (IOException e) {
				// not ready yet, fall through and retry
			}
			socket.close();
			return false;
		});
		return connected.get();
	}

	private static boolean containsWithin(InputStream in, String expected, long timeoutMillis) throws IOException {
		ByteArrayOutputStream received = new ByteArrayOutputStream();
		long deadline = currentTimeMillis() + timeoutMillis;
		while (currentTimeMillis() < deadline) {
			int b;
			try {
				b = in.read();
			} catch (SocketTimeoutException e) {
				return false;
			}
			if (b < 0) {
				return false;
			}
			received.write(b);
			if (received.toString(UTF_8).contains(expected)) {
				return true;
			}
		}
		return false;
	}

}
