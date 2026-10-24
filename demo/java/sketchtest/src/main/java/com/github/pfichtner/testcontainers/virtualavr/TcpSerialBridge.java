package com.github.pfichtner.testcontainers.virtualavr;

import static java.lang.ProcessBuilder.Redirect.appendTo;
import static java.lang.String.format;
import static java.nio.file.Files.isSymbolicLink;
import static java.nio.file.Files.readSymbolicLink;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.shaded.org.awaitility.core.ConditionTimeoutException;

/**
 * Helper class that provides a host side serial device for a
 * {@link VirtualAvrContainer} running in TCP serial listen mode.
 * <p>
 * The container listens on a TCP port (see
 * {@link VirtualAvrContainer#withTcpSerialListenMode()}) and a {@code socat}
 * process on the host connects to it. That socat process creates a pseudo
 * terminal (PTY) which is the serial device the test uses via
 * {@link VirtualAvrContainer#serialConnection()}. This is needed on macOS and
 * Windows with Docker Desktop, where the host {@code /dev} cannot be
 * bind-mounted into the container.
 * </p>
 * <p>
 * This class encapsulates all bridge-specific logic, including:
 * <ul>
 * <li>Starting the host-side socat process</li>
 * <li>Creating a unique PTY device path</li>
 * <li>Providing a {@link SerialConnection} to the virtual AVR device</li>
 * <li>Stopping the socat process and cleaning up the temporary PTY path</li>
 * </ul>
 * </p>
 * <p>
 * <b>Design Notes:</b>
 * <ul>
 * <li>This class maintains a reference back to the {@link VirtualAvrContainer}
 * instance (called <em>delegate</em>), creating a circular dependency:
 * {@code VirtualAvrContainer → TcpSerialBridge → VirtualAvrContainer}.</li>
 * <li>This circular reference is <em>safe</em> in Java because the garbage
 * collector handles circular references correctly.</li>
 * <li>The support <b>must not</b> call {@code delegate.start()} or
 * {@code delegate.stop()}. The container lifecycle is controlled only by
 * {@code VirtualAvrContainer.start()} and {@code .stop()}. The support only
 * starts the host side bridge after the container is up and cleans up its
 * resources after the container is stopped.</li>
 * <li>This pattern avoids subclassing the container and keeps the bridge logic
 * encapsulated and optional.</li>
 * </ul>
 */
class TcpSerialBridge {

	private static final String SOCAT_BINARY_NAME = "socat";

	/** how often the bridge is established before giving up */
	private static final int BRIDGE_ATTEMPTS = 5;
	/** how long to wait for socat to create the PTY link */
	private static final Duration PTY_LINK_TIMEOUT = Duration.ofSeconds(2);
	/** how long to wait for the simulator (and with it the WebSocket) to come up */
	private static final Duration SIMULATOR_TIMEOUT = Duration.ofSeconds(10);
	/** the healthcheck script of the image, it opens a real WebSocket connection */
	private static final String HEALTHCHECK_SCRIPT = "/app/healthcheck.js";

	private static final Logger logger = LoggerFactory.getLogger(TcpSerialBridge.class);

	private final VirtualAvrContainer<?> delegate;
	private Path tcpSerialDevicePath;
	private Process socatProcess;

	TcpSerialBridge(VirtualAvrContainer<?> delegate) {
		this.delegate = delegate;
	}

	/**
	 * Starts the host side bridge to the container's TCP serial listener. Must be
	 * called after the container has been started, because only then the mapped
	 * port of the listener is known.
	 * <p>
	 * The bridge is the trigger for the simulator: the container's listener only
	 * starts the simulator when the first client connects. Therefore establishing
	 * the bridge and waiting for the simulator to come up is one operation - if the
	 * bridge cannot be established, it is retried.
	 * </p>
	 * <p>
	 * shortcut: no automatic bridge reconnect, upgrade if serialConnection() fails
	 * after a client disconnect cycle
	 * </p>
	 *
	 * @param mappedPort the host port the container's TCP serial listener is mapped
	 *                   to
	 */
	void startBridge(int mappedPort) {
		try {
			tcpSerialDevicePath = Files.createTempFile("virtualavr-", ".tmp");
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to create the host PTY path", e);
		}

		RuntimeException failure = null;
		for (int attempt = 1; attempt <= BRIDGE_ATTEMPTS; attempt++) {
			Process process = startSocat(mappedPort);
			try {
				awaitBridge(process, PTY_LINK_TIMEOUT, () -> isSymbolicLink(tcpSerialDevicePath),
						format("Timeout waiting for the host PTY link %s", tcpSerialDevicePath));
				awaitBridge(process, SIMULATOR_TIMEOUT, this::simulatorIsUp,
						"Timeout waiting for the simulator to come up");
				socatProcess = process;
				logger.info("TCP serial bridge: established, PTY at {}", devicePath());
				return;
			} catch (RuntimeException e) {
				failure = e;
				stopSocat(process);
				logger.warn("TCP serial bridge: attempt {}/{} failed: {}", attempt, BRIDGE_ATTEMPTS, e.getMessage());
			}
		}
		throw new IllegalStateException(format(
				"Failed to establish the host serial bridge to the container's TCP serial listener on port %d", mappedPort),
				failure);
	}

	private Process startSocat(int mappedPort) {
		try {
			// a link left over by a previous attempt would make the PTY check pass prematurely
			Files.deleteIfExists(tcpSerialDevicePath);
			return socatProcessBuilder(tcpSerialDevicePath, mappedPort).start();
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to start host socat process", e);
		}
	}

	/**
	 * Waits for a condition of the host bridge. If the host socat process died
	 * (e.g. because the container's listener was not reachable yet) the wait is
	 * aborted immediately instead of running into the timeout.
	 */
	private void awaitBridge(Process bridgeProcess, Duration timeout, BooleanSupplier condition, String timeoutMessage) {
		try {
			await().atMost(timeout.toMillis(), MILLISECONDS).pollInterval(100, MILLISECONDS).until(() -> {
				if (!bridgeProcess.isAlive()) {
					throw new IllegalStateException("the host socat process exited");
				}
				return condition.getAsBoolean();
			});
		} catch (ConditionTimeoutException e) {
			throw new IllegalStateException(timeoutMessage, e);
		}
	}

	/**
	 * Checks whether the simulator is up. The healthcheck script of the image is
	 * used on purpose: it opens a real WebSocket connection (including the token)
	 * from within the container. A plain TCP connect to the published WebSocket
	 * port would not tell anything, because Docker's port proxy accepts that
	 * connection even when nothing listens inside the container yet.
	 */
	private boolean simulatorIsUp() {
		try {
			return delegate.execInContainer("node", HEALTHCHECK_SCRIPT).getExitCode() == 0;
		} catch (IOException e) {
			logger.debug("TCP serial bridge: healthcheck failed: {}", e.getMessage());
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for the simulator", e);
		}
	}

	private ProcessBuilder socatProcessBuilder(Path tcpSerialDevicePath, int mappedPort) throws IOException {
		ProcessBuilder pb = new ProcessBuilder(socatArgs(tcpSerialDevicePath, mappedPort));
		if (delegate.debug().filter(Boolean.TRUE::equals).isEmpty()) {
			return pb.inheritIO();
		}
		Path socatLog = Files.createTempFile("virtualavr-socat-" + mappedPort, ".log");
		logger.info("TCP serial bridge: socat output will be logged to {}", socatLog);
		return pb.redirectErrorStream(true).redirectOutput(appendTo(socatLog.toFile()));
	}

	private List<String> socatArgs(Path tcpSerialDevicePath, int mappedPort) {
		List<String> args = new ArrayList<>();
		args.add(SOCAT_BINARY_NAME);
		delegate.socatVerbosity().ifPresent(verbosity -> args.addAll(List.of(verbosity.split(" "))));
		args.add(format("pty,raw,echo=0,link=%s", tcpSerialDevicePath));
		// the host connects to the container's listener, 127.0.0.1 works on every OS
		args.add(format("tcp:127.0.0.1:%d", mappedPort));
		return args;
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
			logger.info("TCP serial bridge: PTY symlink {} -> {}", tcpSerialDevicePath, resolved);
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
