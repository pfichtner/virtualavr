package com.github.pfichtner.testcontainers.virtualavr;

import static java.lang.String.format;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isSymbolicLink;
import static java.nio.file.Files.readSymbolicLink;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private static final String SOCAT_BINARY_NAME = "socat";

	private static final Logger logger = LoggerFactory.getLogger(TcpSerialModeSupport.class);

	private final VirtualAvrContainer<?> delegate;
	private boolean debug;
	private Path tcpSerialDevicePath;
	private Process socatProcess;

	private int tcpSerialPort;

	TcpSerialModeSupport(VirtualAvrContainer<?> delegate) {
		this.delegate = delegate;
	}

	TcpSerialModeSupport withDebug(boolean debug) {
		this.debug = debug;
		return this;
	}

	public void prepareStart() {
		startHostSocat();
		// Remove the /dev bind mount since we don't need it in TCP mode
		delegate.getBinds().removeIf(b -> b.getVolume().getPath().equals(VirtualAvrContainer.containerDev));
	}

	private void startHostSocat() {
		try {
			if (socatProcess != null && socatProcess.isAlive()) {
				if (tcpSerialDevicePathExists()) {
					logger.info("TCP Serial Mode: Socat already running (PID {}) with PTY at {}, skipping start",
							socatProcess.pid(), tcpSerialDevicePath);
					return;
				}
				logger.warn("TCP Serial Mode: Socat process running (PID {}) but PTY missing, killing and restarting",
						socatProcess.pid());
				stopSocat(socatProcess);
			}

			// Find a free port (only on first start, reuse port on restart for container
			// compatibility)
			if (tcpSerialPort == 0) {
				tcpSerialPort = findFreePort();
			} else {
				waitForPortAvailable(tcpSerialPort, 5, SECONDS);
			}
			logger.info("TCP Serial Mode: Using port {}", tcpSerialPort);

			tcpSerialDevicePath = Files.createTempFile("virtualavr-", ".tmp");

			// Start socat on the host: create PTY and listen on TCP
			// Use fork to allow the container to reconnect if needed
			socatProcess = new ProcessBuilder(socatArgs(tcpSerialPort)).inheritIO().start();

			// Give socat time to create the PTY and start listening
			int intervalMs = 50; // polling interval
			int waited = 0;
			while (!tcpSerialDevicePathExists()) {
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

	/**
	 * Wait for a port to become available (not in use).
	 *
	 * @param port    the port to check
	 * @param timeout the maximum time to wait
	 * @param unit    the unit of the timeout
	 */
	private static void waitForPortAvailable(int port, long timeout, TimeUnit unit) {
		Instant deadline = Instant.now().plusMillis(unit.toMillis(timeout));
		while (Instant.now().isBefore(deadline)) {
			try (ServerSocket ignored = new ServerSocket(port)) {
				logger.info("TCP Serial Mode: Port {} is now available", port);
				return;
			} catch (IOException __) {
				try {
					MILLISECONDS.sleep(100);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
		logger.warn("TCP Serial Mode: Timeout waiting for port {} to become available", port);
	}

	private boolean tcpSerialDevicePathExists() {
		return tcpSerialDevicePath != null && exists(tcpSerialDevicePath);
	}

	private List<String> socatArgs(int tcpSerialPort) {
		return Stream.of( //
				Stream.of(SOCAT_BINARY_NAME), //
				debug ? Stream.of("-d", "-d") : Stream.<String>empty(), //
				Stream.of(format("pty,raw,echo=0,link=%s", tcpSerialDevicePath)), //
				Stream.of(format("tcp-listen:%d,reuseaddr,fork", tcpSerialPort)) //
		).reduce(Stream::concat).orElseGet(Stream::empty).collect(toList()); //
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	public void finalizeStop() {
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
