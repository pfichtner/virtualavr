package com.github.pfichtner.virtualavr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.pfichtner.virtualavr.VirtualAvrConnection.connectionToVirtualAvr;
import static java.lang.String.format;
import static java.util.stream.Collectors.joining;
import static org.testcontainers.containers.BindMode.READ_ONLY;
import static org.testcontainers.containers.BindMode.READ_WRITE;

public class VirtualAvrContainer<SELF extends VirtualAvrContainer<SELF>> extends GenericContainer<SELF> {

	private static final Logger LOG = LoggerFactory.getLogger(VirtualAvrContainer.class);

	private static final String BAUDRATE = "BAUDRATE";
	private static final int DEFAULT_BAUDRATE = 115200;

	public static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("pfichtner/virtualavr");
	public static final String DEFAULT_TAG = "dev";

	private static final String hostDev = "/dev";
	private static final String containerDev = "/dev";
	private static final int WEBSOCKET_PORT = 8080;

	private String ttyDevice = "ttyUSB0";
	private boolean tcpSerialMode = false;
	private String tcpSerialDevicePath;
	private int tcpSerialPort;
	private Process socatProcess;

	private VirtualAvrConnection avr;
    private SerialConnection serialConnection;

	public VirtualAvrContainer() {
		this(DEFAULT_IMAGE_NAME.withTag(DEFAULT_TAG));
	}

	public VirtualAvrContainer(DockerImageName dockerImageName) {
		super(dockerImageName);
		dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);
		withDeviceName(ttyDevice) //
				.withFileSystemBind(hostDev, containerDev, READ_WRITE) //
				.addExposedPort(WEBSOCKET_PORT);
	}

	/**
	 * Enables TCP serial mode for use on macOS/Windows with Docker Desktop.
	 * In this mode, a socat process is started on the host that creates a PTY
	 * and listens on TCP. The container connects to this TCP port instead of
	 * creating a local PTY device.
	 *
	 * @return this container instance
	 */
	public VirtualAvrContainer<?> withTcpSerialMode() {
		this.tcpSerialMode = true;
		// Remove the /dev bind mount since we don't need it in TCP mode
		getBinds().removeIf(b -> b.getVolume().getPath().equals(containerDev));
		return self();
	}

	public VirtualAvrContainer<?> withDeviceName(String ttyDevice) {
		this.ttyDevice = ttyDevice;
		withEnv("VIRTUALDEVICE", containerDev + "/" + ttyDevice);
		return self();
	}

	public VirtualAvrContainer<?> withBaudrate(int baudrate) {
		withEnv(BAUDRATE, String.valueOf(baudrate));
		return self();
	}

	public VirtualAvrContainer<?> withDeviceUser(String user) {
		withEnv("DEVICEUSER", user);
		return self();
	}

	public VirtualAvrContainer<?> withDeviceGroup(String group) {
		withEnv("DEVICEGROUP", group);
		return self();
	}

	public VirtualAvrContainer<?> withDeviceMode(int deviceMode) {
		withEnv("DEVICEMODE", String.valueOf(deviceMode));
		return self();
	}

	public VirtualAvrContainer<?> withPausedStartup() {
		withEnv("PAUSE_ON_START", String.valueOf(true));
		return self();
	}

	public VirtualAvrContainer<?> withBuildExtraFlags(Map<String, Object> cFlags) {
		return withBuildExtraFlags(cFlags.entrySet() //
				.stream() //
				.map(e -> createDefine(e.getKey(), e.getValue())) //
				.collect(joining(" ")));
	}

	private static String createDefine(String key, Object value) {
		return format("-D%s=%s", key, value instanceof String ? format("\"%s\"", value) : value);
	}

	public VirtualAvrContainer<?> withBuildExtraFlags(String cFlags) {
		withEnv("BUILD_EXTRA_FLAGS", cFlags);
		return self();
	}

	public VirtualAvrContainer<SELF> withSketchFile(File sketchFile) {
		withEnv("FILENAME", sketchFile.getName()) //
				.withFileSystemBind(sketchFile.getParent(), "/sketch/", READ_ONLY);
		return self();
	}

	public VirtualAvrContainer<?> withPublishMillis(int millis) {
		withEnv("PUBLISH_MILLIS", String.valueOf(millis));
		return self();
	}

	@Override
	public void start() {
		// Prevent multiple starts (idempotent)
		if (isRunning()) {
			LOG.info("VirtualAVR container already running, skipping start");
			return;
		}

		if (tcpSerialMode) {
			LOG.info("Starting VirtualAVR container in TCP serial mode");
			startHostSocat();
		} else {
			LOG.info("Starting VirtualAVR container in standard PTY mode");
		}
		super.start();

		// Log container info after start
		LOG.info("VirtualAVR container started: ID={}", getContainerId());
		LOG.info("Container environment variables:");
		getEnvMap().forEach((k, v) -> LOG.info("  {}={}", k, v));

		// Wait a moment for the container's entrypoint to establish connections
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// Log container logs to help debug
		LOG.info("Container logs (first 50 lines):");
		String logs = getLogs();
		String[] lines = logs.split("\n");
		for (int i = 0; i < Math.min(50, lines.length); i++) {
			LOG.info("  [container] {}", lines[i]);
		}

		// Check container health
		LOG.info("Container state: isRunning={}, isHealthy={}", isRunning(), isHealthy());
	}

	@Override
	public void stop() {
		LOG.info("Stopping VirtualAVR container");

		// Close the WebSocket connection first (before stopping the container)
		if (avr != null) {
			try {
				avr.closeGracefully();
			} catch (Exception e) {
				LOG.warn("Error closing VirtualAvrConnection", e);
			}
			avr = null;
		}

		// Kill socat BEFORE stopping the container to ensure clean shutdown
		if (socatProcess != null) {
			LOG.info("Killing socat process (PID {})", socatProcess.pid());
			socatProcess.destroy();
			try {
				// Wait for socat to actually terminate
				if (!socatProcess.waitFor(5, TimeUnit.SECONDS)) {
					LOG.warn("Socat did not terminate in time, forcing kill");
					socatProcess.destroyForcibly();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				socatProcess.destroyForcibly();
			}
			socatProcess = null;
		}

		// Clean up the PTY symlink
		if (tcpSerialDevicePath != null) {
			new File(tcpSerialDevicePath).delete();
		}

		super.stop();
		LOG.info("VirtualAVR container stopped");
	}

	private void startHostSocat() {
		startHostSocatInternal(true);
	}

	/**
	 * Internal method to start socat.
	 *
	 * @param setContainerEnv whether to set container environment variables (only on first start)
	 */
	private void startHostSocatInternal(boolean setContainerEnv) {
		// Check if socat is already running and healthy
		if (socatProcess != null && socatProcess.isAlive()) {
			// Verify the PTY symlink exists (socat is actually working)
			if (tcpSerialDevicePath != null && new File(tcpSerialDevicePath).exists()) {
				LOG.info("TCP Serial Mode: Socat already running (PID {}) with PTY at {}, skipping start",
					socatProcess.pid(), tcpSerialDevicePath);
				return;
			}
			// Socat is running but PTY doesn't exist - kill it and restart
			LOG.warn("TCP Serial Mode: Socat process running (PID {}) but PTY missing, killing and restarting",
				socatProcess.pid());
			socatProcess.destroyForcibly();
			try {
				socatProcess.waitFor(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			socatProcess = null;
		}

		try {
			// Find a free port (only on first start, reuse port on restart for container compatibility)
			if (tcpSerialPort == 0) {
				tcpSerialPort = findFreePort();
			} else {
				// Wait for port to be released if we're restarting
				waitForPortAvailable(tcpSerialPort, 5000);
			}
			LOG.info("TCP Serial Mode: Using port {}", tcpSerialPort);

			// Create a unique device path in /tmp (only on first start)
			if (tcpSerialDevicePath == null) {
				tcpSerialDevicePath = "/tmp/virtualavr-" + UUID.randomUUID();
			} else {
				// Clean up old symlink before recreating
				new File(tcpSerialDevicePath).delete();
			}
			LOG.info("TCP Serial Mode: PTY symlink path will be {}", tcpSerialDevicePath);

			// Start socat on the host: create PTY and listen on TCP
			// Use fork to allow multiple reconnections from the container
			String socatCmd = format("socat -d -d pty,raw,echo=0,link=%s tcp-listen:%d,reuseaddr,fork",
				tcpSerialDevicePath, tcpSerialPort);
			LOG.info("TCP Serial Mode: Starting socat with command: {}", socatCmd);

			ProcessBuilder pb = new ProcessBuilder(
				"socat",
				"-d", "-d",
				format("pty,raw,echo=0,link=%s", tcpSerialDevicePath),
				format("tcp-listen:%d,reuseaddr,fork", tcpSerialPort)
			);
			// Redirect socat output to log files for debugging
			File socatLog = new File("/tmp/virtualavr-socat-" + tcpSerialPort + ".log");
			pb.redirectErrorStream(true);
			pb.redirectOutput(ProcessBuilder.Redirect.appendTo(socatLog));
			LOG.info("TCP Serial Mode: Socat output will be logged to {}", socatLog.getAbsolutePath());

			socatProcess = pb.start();
			LOG.info("TCP Serial Mode: Socat process started with PID {}", socatProcess.pid());

			// Give socat time to create the PTY and start listening
			Thread.sleep(1000);

			// Verify the PTY was created
			File ptyFile = new File(tcpSerialDevicePath);
			if (!ptyFile.exists()) {
				// Check socat log for errors
				LOG.error("TCP Serial Mode: PTY symlink not created at {}. Check socat log at {}",
					tcpSerialDevicePath, socatLog.getAbsolutePath());
				throw new RuntimeException("Failed to create host PTY at " + tcpSerialDevicePath);
			}

			// Resolve the symlink to see the actual device
			Path symlinkPath = Paths.get(tcpSerialDevicePath);
			if (Files.isSymbolicLink(symlinkPath)) {
				Path realPath = Files.readSymbolicLink(symlinkPath);
				LOG.info("TCP Serial Mode: PTY symlink {} -> {}", tcpSerialDevicePath, realPath);
			}

			// Configure the container to connect to the host (only on first start)
			if (setContainerEnv) {
				withEnv("SERIAL_TCP_HOST", "host.docker.internal");
				withEnv("SERIAL_TCP_PORT", String.valueOf(tcpSerialPort));
				// Enable verbose socat logging in the container
				withEnv("VERBOSITY", "-d -d -v");
				LOG.info("TCP Serial Mode: Container will connect to host.docker.internal:{}", tcpSerialPort);
			}

			// Verify port is listening
			try {
				ProcessBuilder lsofPb = new ProcessBuilder("lsof", "-i", ":" + tcpSerialPort);
				Process lsofProc = lsofPb.start();
				BufferedReader reader = new BufferedReader(new InputStreamReader(lsofProc.getInputStream()));
				String line;
				LOG.info("TCP Serial Mode: Checking if port {} is listening:", tcpSerialPort);
				while ((line = reader.readLine()) != null) {
					LOG.info("  {}", line);
				}
				lsofProc.waitFor(5, TimeUnit.SECONDS);
			} catch (Exception e) {
				LOG.warn("TCP Serial Mode: Could not verify port listening status: {}", e.getMessage());
			}

		} catch (IOException | InterruptedException e) {
			LOG.error("TCP Serial Mode: Failed to start host socat process", e);
			throw new RuntimeException("Failed to start host socat process", e);
		}
	}

	/**
	 * Resets the serial connection by restarting the socat process.
	 * This clears any buffered data and allows a clean reconnection.
	 * The container will automatically reconnect to the same TCP port.
	 * <p>
	 * Call this method between tests to ensure a clean serial connection state.
	 */
	public void resetSerialConnection() {
		if (!tcpSerialMode) {
			LOG.info("resetSerialConnection: Not in TCP serial mode, nothing to do");
			return;
		}

		LOG.info("resetSerialConnection: Restarting socat to clear connection state");

		// Kill the current socat process
		if (socatProcess != null) {
			socatProcess.destroy();
			try {
				socatProcess.waitFor(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			socatProcess = null;
		}

		// Give the container a moment to notice the disconnection
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// Start a new socat process (reusing the same port so container can reconnect)
		startHostSocatInternal(false);

		// Give the container time to reconnect
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		LOG.info("resetSerialConnection: Socat restarted, container should reconnect automatically");
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	/**
	 * Wait for a port to become available (not in use).
	 *
	 * @param port the port to check
	 * @param timeoutMs maximum time to wait in milliseconds
	 */
	private static void waitForPortAvailable(int port, long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			try (ServerSocket socket = new ServerSocket(port)) {
				// Port is available
				LOG.info("TCP Serial Mode: Port {} is now available", port);
				return;
			} catch (IOException e) {
				// Port still in use, wait and retry
				try {
					Thread.sleep(100);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
		LOG.warn("TCP Serial Mode: Timeout waiting for port {} to become available", port);
	}

	public synchronized VirtualAvrConnection avr() {
		if (avr == null) {
			int wsPort = getFirstMappedPort();
			LOG.info("WebSocket: Connecting to ws://localhost:{}", wsPort);
			avr = connectionToVirtualAvr(this);
			LOG.info("WebSocket: Connection established: isOpen={}", avr.isOpen());
		}
		return avr;
	}

    public String getSerialDevicePath() throws IOException {
        if (tcpSerialMode) {
            // Resolve the symlink to get the actual PTY device path
            // jSerialComm on macOS can open /dev/ttysXXX directly
            Path symlinkPath = Paths.get(tcpSerialDevicePath);
            if (Files.isSymbolicLink(symlinkPath)) {
                return Files.readSymbolicLink(symlinkPath).toString();
            } else {
                return tcpSerialDevicePath;
            }
        } else {
            return hostDev + "/" + ttyDevice;
        }
    }

    public synchronized SerialConnection serialConnection() throws IOException {
        // TODO a shared connection that can be closed is not very smart
        if (serialConnection == null || serialConnection.isClosed()) {
            serialConnection = newSerialConnection();
        }
        return serialConnection;
    }

    public SerialConnection newSerialConnection() throws IOException {
        String devicePath;
        if (tcpSerialMode) {
            // Resolve the symlink to get the actual PTY device path
            // jSerialComm on macOS can open /dev/ttysXXX directly
            Path symlinkPath = Paths.get(tcpSerialDevicePath);
            if (Files.isSymbolicLink(symlinkPath)) {
                devicePath = Files.readSymbolicLink(symlinkPath).toString();
            } else {
                devicePath = tcpSerialDevicePath;
            }
        } else {
            devicePath = hostDev + "/" + ttyDevice;
        }
        return new SerialConnection(devicePath, baudrate());
    }

    private int baudrate() {
        return Optional.ofNullable(getEnvMap().get(BAUDRATE)).map(Integer::parseInt).orElse(DEFAULT_BAUDRATE);
    }

}
