package com.github.pfichtner.testcontainers.virtualavr.tests;

import static com.github.pfichtner.testcontainers.virtualavr.IOUtil.withSketchFromClasspath;
import static com.github.pfichtner.testcontainers.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrContainer;

@Testcontainers
class TcpSerialListenModeIT {

	private static final int CONNECT_TIMEOUT_MILLIS = 1_000;
	private static final int ECHO_TIMEOUT_MILLIS = 2_000;
	private static final int READ_TIMEOUT_MILLIS = 20_000;

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer( //
			withSketchFromClasspath("/byteecho/byteecho.ino")) //
			.withTcpSerialListenMode();

	@Test
	void startRemovesDevBind() {
		assertThat(virtualAvrContainer.getBinds().stream().map(Object::toString))
				.doesNotContain(VirtualAvrContainer.containerDev);
	}

	@Test
	void canEchoViaTcpSocket() throws Exception {
		// byte 0 is already echoed by the connect probe, see connectWhenEchoing
		try (Socket socket = connectWhenEchoing(0)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			for (int i = 1; i < 255; i++) {
				out.write((byte) i);
				out.flush();
				assertThat(in.read()).isEqualTo(i);
			}
			out.write((byte) 255);
			out.flush();
			assertThat(in.read()).isEqualTo(255);
			assertThat(in.read()).isEqualTo(0);
		}
	}

	@Test
	void canReconnectAfterClientDisconnects() throws Exception {
		try (Socket first = connectWhenEchoing(0)) {
			// closing the connection makes socat (and the simulator) exit
		}
		// the entrypoint listens again, a fresh simulator answers the next client
		try (Socket second = connectWhenEchoing(0)) {
			second.getOutputStream().write(1);
			second.getOutputStream().flush();
			assertThat(second.getInputStream().read()).isEqualTo(1);
		}
	}

	/**
	 * Connects to the container's TCP serial listener and verifies the link by an
	 * echo round-trip. The sketch is still being compiled when the container
	 * reports started, and connecting (not the wait strategy) triggers the
	 * simulator to start because socat runs without {@code fork}, so connect
	 * attempts are retried until one gets an echo.
	 */
	private Socket connectWhenEchoing(int probeByte) {
		AtomicReference<Socket> connected = new AtomicReference<>();
		await().atMost(60, SECONDS).pollInterval(500, MILLISECONDS).until(() -> {
			Socket socket = new Socket();
			try {
				socket.connect(new InetSocketAddress(virtualAvrContainer.getHost(),
						virtualAvrContainer.serialTcpListenPort()), CONNECT_TIMEOUT_MILLIS);
				socket.setSoTimeout(ECHO_TIMEOUT_MILLIS);
				socket.getOutputStream().write(probeByte);
				socket.getOutputStream().flush();
				if (socket.getInputStream().read() == probeByte) {
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

}
