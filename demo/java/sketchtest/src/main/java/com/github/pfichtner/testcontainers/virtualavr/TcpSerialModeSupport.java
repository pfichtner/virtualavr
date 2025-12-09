package com.github.pfichtner.testcontainers.virtualavr;

import static java.lang.ProcessBuilder.Redirect.appendTo;
import static java.lang.String.format;
import static java.nio.file.Files.isSymbolicLink;
import static java.nio.file.Files.readSymbolicLink;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.empty;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.shaded.org.awaitility.core.ConditionTimeoutException;

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
	private Path tcpSerialDevicePath;
	private Process socatProcess;

	private int tcpSerialPort;

	TcpSerialModeSupport(VirtualAvrContainer<?> delegate) {
		this.delegate = delegate;
	}

	void prepareStart() {
		startHostSocat();
		// Remove the /dev bind mount since we don't need it in TCP mode
		delegate.getBinds().removeIf(b -> b.getVolume().getPath().equals(VirtualAvrContainer.containerDev));
	}

	private void startHostSocat() {
		try {
			if (Optional.ofNullable(socatProcess).filter(Process::isAlive).isPresent()) {
				if (tcpSerialDevicePathExists()) {
					logger.info("TCP Serial Mode: Socat already running (PID {}) with PTY at {}, skipping start",
							socatProcess.pid(), tcpSerialDevicePath);
					return;
				}
				logger.warn("TCP Serial Mode: Socat process running (PID {}) but PTY missing, killing and restarting",
						socatProcess.pid());
				stopSocat(socatProcess);
			}

			tcpSerialDevicePath = Files.createTempFile("virtualavr-", ".tmp");
			tcpSerialPort = tcpSerialPort();
			socatProcess = socatProcessBuilder(tcpSerialDevicePath, tcpSerialPort).start();
			try {
				await().atMost(5, SECONDS).pollInterval(50, MILLISECONDS).until(this::tcpSerialDevicePathExists);
			} catch (ConditionTimeoutException __) {
				throw new RuntimeException(format("Timeout waiting for PTY creation: %s", tcpSerialDevicePath));
			}

			// Configure the container to connect to the host
			String serialTcp = format("%s:%d", "host.docker.internal", tcpSerialPort);
			delegate.withEnv("SERIAL_TCP", serialTcp);
			logger.info("TCP Serial Mode: Container will connect to {}", serialTcp);
		} catch (IOException e) {
			throw new RuntimeException("Failed to start host socat process", e);
		}
	}

	private int tcpSerialPort() {
		if (tcpSerialPort == 0) {
			return findFreePort();
		}
		// reuse port on restart for container compatibility
		try {
			await().atMost(5, SECONDS).pollInterval(100, MILLISECONDS).until(() -> canAccess(tcpSerialPort));
			logger.info("TCP Serial Mode: Port {} is now available" //
					, tcpSerialPort);
		} catch (ConditionTimeoutException __) {
			logger.info("TCP Serial Mode: Timeout waiting for port {} to become available" //
					, tcpSerialPort);
		}
		return tcpSerialPort;
	}

	private ProcessBuilder socatProcessBuilder(Path tcpSerialDevicePath, int tcpSerialPort) throws IOException {
		ProcessBuilder pb = new ProcessBuilder(socatArgs(tcpSerialDevicePath, tcpSerialPort));
		if (delegate.debug().filter(Boolean.TRUE::equals).isEmpty()) {
			return pb.inheritIO();
		}
		Path socatLog = Files.createTempFile("virtualavr-socat-" + tcpSerialPort, ".log");
		logger.info("TCP Serial Mode: Socat output will be logged to {}", socatLog);
		return pb.redirectErrorStream(true).redirectOutput(appendTo(socatLog.toFile()));
	}

	private boolean tcpSerialDevicePathExists() {
		return Optional.ofNullable(tcpSerialDevicePath).filter(Files::exists).isPresent();
	}

	private List<String> socatArgs(Path tcpSerialDevicePath, int tcpSerialPort) {
		return Stream.of( //
				Stream.of(SOCAT_BINARY_NAME), //
				delegate.socatVerbosity().map(s -> s.split(" ")).map(Stream::of).orElse(empty()), //
				Stream.of(format("pty,raw,echo=0,link=%s", tcpSerialDevicePath)), //
				// Use fork to allow the container to reconnect if needed
				Stream.of(format("tcp-listen:%d,reuseaddr,fork", tcpSerialPort)) //
		).reduce(Stream::concat).orElseGet(Stream::empty).collect(toList()); //
	}

	private static int findFreePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to find free TCP port", e);
		}
	}

	private static boolean canAccess(int port) {
		try (ServerSocket __ = new ServerSocket(port)) {
			return true;
		} catch (IOException __) {
			return false;
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
			process.destroyForcibly();
		}
	}

	protected Path devicePath() {
		try {
			Path resolved = resolveSymLink(tcpSerialDevicePath);
			logger.info("TCP Serial Mode: PTY symlink {} -> {}", tcpSerialDevicePath, resolved);
			return resolved;
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to resolve TCP serial device path", e);
		}
	}

	static boolean isSocatAvailable() {
		try {
			Process process = new ProcessBuilder(SOCAT_BINARY_NAME, "-h").redirectErrorStream(true).start();
			process.waitFor(2, SECONDS);
			return process.exitValue() == 0 || process.exitValue() == 1;
		} catch (IOException | InterruptedException e) {
			return false;
		}
	}

	private static Path resolveSymLink(Path path) throws IOException {
		return isSymbolicLink(path) ? readSymbolicLink(path) : path;
	}

}
