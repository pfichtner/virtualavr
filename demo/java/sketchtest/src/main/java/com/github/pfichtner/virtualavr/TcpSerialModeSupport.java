package com.github.pfichtner.virtualavr;

import static java.lang.String.format;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
public class TcpSerialModeSupport {

	private final VirtualAvrContainer<?> delegate;
	private String tcpSerialDevicePath;
	private int tcpSerialPort;
	private Process socatProcess;

	TcpSerialModeSupport(VirtualAvrContainer<?> delegate) {
		this.delegate = delegate;
	}

	public void start() {
		startHostSocat();
		// Remove the /dev bind mount since we don't need it in TCP mode
		delegate.getBinds().removeIf(b -> b.getVolume().getPath().equals(VirtualAvrContainer.containerDev));
	}

	private void startHostSocat() {
		try {
			// Find a free port
			tcpSerialPort = findFreePort();

			// Create a unique device path in /tmp
			tcpSerialDevicePath = format("/tmp/virtualavr-%s", UUID.randomUUID());

			// Start socat on the host: create PTY and listen on TCP
			// Use fork to allow the container to reconnect if needed
			ProcessBuilder processBuilder = new ProcessBuilder("socat", "-d", "-d", //
					format("pty,raw,echo=0,link=%s", tcpSerialDevicePath), //
					format("tcp-listen:%d,reuseaddr,fork", tcpSerialPort)) //
			;
			processBuilder.inheritIO();
			socatProcess = processBuilder.start();

			// Give socat time to create the PTY and start listening
			int intervalMs = 50; // polling interval
			int waited = 0;
			while (!new File(tcpSerialDevicePath).exists()) {
				if (waited >= 2000) {
					throw new RuntimeException(format("Timeout waiting for PTY creation: %s", tcpSerialDevicePath));
				}
				TimeUnit.MILLISECONDS.sleep(intervalMs);
				waited += intervalMs;
			}

			// Configure the container to connect to the host
			delegate //
					.withEnv("SERIAL_TCP_HOST", "host.docker.internal") //
					.withEnv("SERIAL_TCP_PORT", String.valueOf(tcpSerialPort));
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException("Failed to start host socat process", e);
		}
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	public void stop() {
		Optional.ofNullable(socatProcess).ifPresent(Process::destroy);
		Optional.ofNullable(tcpSerialDevicePath).map(File::new).ifPresent(File::delete);
		socatProcess = null;
		tcpSerialDevicePath = null;
	}

	public SerialConnection newSerialConnection() throws IOException {
		return new SerialConnection(devicePath(), delegate.baudrate());
	}

	private String devicePath() throws IOException {
		// Resolve the symlink to get the actual PTY device path
		// jSerialComm on macOS can open /dev/ttysXXX directly
		Path symlinkPath = Paths.get(tcpSerialDevicePath);
		return Files.isSymbolicLink(symlinkPath) //
				? Files.readSymbolicLink(symlinkPath).toString() //
				: tcpSerialDevicePath;
	}

}
