package com.github.pfichtner.virtualavr;

import static java.lang.String.format;
import static java.nio.file.Files.isSymbolicLink;
import static java.nio.file.Files.readSymbolicLink;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Helper class that enables TCP-based serial mode for a
 * {@link VirtualAvrContainer}.
 * <p>
 * In TCP serial mode (typically used on macOS or Windows with Docker Desktop),
 * a {@code socat} process is started on the host system. This process creates a
 * pseudo-terminal (PTY) and listens on a TCP port. The container then connects
 * to this TCP port instead of using a local PTY device.
 * </p>
 * <p>
 * This class encapsulates all TCP-mode-specific logic, including:
 * <ul>
 * <li>Starting the host-side socat process</li>
 * <li>Creating a unique PTY device path</li>
 * <li>Configuring environment variables in the container to connect via
 * TCP</li>
 * <li>Providing a {@link SerialConnection} to the virtual AVR device</li>
 * <li>Stopping the socat process and cleaning up the temporary PTY path</li>
 * </ul>
 * </p>
 * <p>
 * <b>Design Notes:</b>
 * <ul>
 * <li>This class maintains a reference back to the {@link VirtualAvrContainer}
 * instance (called <em>delegate</em>), creating a circular dependency:
 * {@code VirtualAvrContainer → TcpSerialModeSupport → VirtualAvrContainer}.</li>
 * <li>This circular reference is <em>safe</em> in Java because the garbage
 * collector handles circular references correctly.</li>
 * <li>The support <b>must not</b> call {@code delegate.start()} or
 * {@code delegate.stop()}. The container lifecycle is controlled only by
 * {@code VirtualAvrContainer.start()} and {@code .stop()}. The support only
 * prepares configuration before start and cleans up resources after stop.</li>
 * <li>This pattern avoids subclassing the container and keeps TCP-mode logic
 * encapsulated and optional.</li>
 * </ul>
 */
class TcpSerialModeSupport {

	private final VirtualAvrContainer<?> delegate;
	private Path tcpSerialDevicePath;
	private Process socatProcess;

	TcpSerialModeSupport(VirtualAvrContainer<?> delegate) {
		this.delegate = delegate;
	}

	public void prepareStart() {
		startHostSocat();
		// Remove the /dev bind mount since we don't need it in TCP mode
		delegate.getBinds().removeIf(b -> b.getVolume().getPath().equals(VirtualAvrContainer.containerDev));
	}

	private void startHostSocat() {
		try {
			int tcpSerialPort = findFreePort();
			this.tcpSerialDevicePath = Files.createTempFile("virtualavr-", ".tmp");

			// Start socat on the host: create PTY and listen on TCP
			// Use fork to allow the container to reconnect if needed
			ProcessBuilder processBuilder = new ProcessBuilder("socat", //
					format("pty,raw,echo=0,link=%s", tcpSerialDevicePath), //
					format("tcp-listen:%d,reuseaddr,fork", tcpSerialPort)) //
			;
			processBuilder.inheritIO();
			socatProcess = processBuilder.start();

			// Give socat time to create the PTY and start listening
			int intervalMs = 50; // polling interval
			int waited = 0;
			while (!Files.exists(tcpSerialDevicePath)) {
				if (waited >= SECONDS.toMillis(5)) {
					throw new RuntimeException(format("Timeout waiting for PTY creation: %s", tcpSerialDevicePath));
				}
				MILLISECONDS.sleep(intervalMs);
				waited += intervalMs;
			}

			// Configure the container to connect to the host
			delegate.withEnv("SERIAL_TCP", format("host.docker.internal:%d", tcpSerialPort));
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException("Failed to start host socat process", e);
		}
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	public void prepareStop() {
		Optional.ofNullable(socatProcess).ifPresent(this::stopSocat);
		Optional.ofNullable(tcpSerialDevicePath).ifPresent(this::delete);
		socatProcess = null;
		tcpSerialDevicePath = null;
	}

	private void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void stopSocat(Process process) {
		process.destroy();
		try {
			if (!process.waitFor(5, SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	protected Path devicePath() {
		try {
			return isSymbolicLink(tcpSerialDevicePath) //
					? readSymbolicLink(tcpSerialDevicePath) //
					: tcpSerialDevicePath;
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to resolve TCP serial device path", e);
		}
	}

}
