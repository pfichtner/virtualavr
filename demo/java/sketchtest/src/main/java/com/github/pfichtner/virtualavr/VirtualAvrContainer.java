package com.github.pfichtner.virtualavr;

import static com.github.pfichtner.virtualavr.VirtualAvrConnection.connectionToVirtualAvr;
import static java.lang.String.format;
import static java.util.stream.Collectors.joining;
import static org.testcontainers.containers.BindMode.READ_ONLY;
import static org.testcontainers.containers.BindMode.READ_WRITE;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public class VirtualAvrContainer<SELF extends VirtualAvrContainer<SELF>> extends GenericContainer<SELF> {

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
		if (tcpSerialMode) {
			startHostSocat();
		}
		super.start();
	}

	@Override
	public void stop() {
		super.stop();
		if (socatProcess != null) {
			socatProcess.destroy();
			socatProcess = null;
		}
		// Clean up the PTY symlink
		if (tcpSerialDevicePath != null) {
			new File(tcpSerialDevicePath).delete();
		}
	}

	private void startHostSocat() {
		try {
			// Find a free port
			tcpSerialPort = findFreePort();

			// Create a unique device path in /tmp
			tcpSerialDevicePath = "/tmp/virtualavr-" + UUID.randomUUID();

			// Start socat on the host: create PTY and listen on TCP
			// Use fork to allow the container to reconnect if needed
			ProcessBuilder pb = new ProcessBuilder(
				"socat",
				"-d", "-d",
				format("pty,raw,echo=0,link=%s", tcpSerialDevicePath),
				format("tcp-listen:%d,reuseaddr,fork", tcpSerialPort)
			);
			pb.inheritIO();
			socatProcess = pb.start();

			// Give socat time to create the PTY and start listening
			Thread.sleep(500);

			// Verify the PTY was created
			if (!new File(tcpSerialDevicePath).exists()) {
				throw new RuntimeException("Failed to create host PTY at " + tcpSerialDevicePath);
			}

			// Configure the container to connect to the host
			withEnv("SERIAL_TCP_HOST", "host.docker.internal");
			withEnv("SERIAL_TCP_PORT", String.valueOf(tcpSerialPort));

		} catch (IOException | InterruptedException e) {
			throw new RuntimeException("Failed to start host socat process", e);
		}
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	public synchronized VirtualAvrConnection avr() {
		if (avr == null) {
			avr = connectionToVirtualAvr(this);
		}
		return avr;
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
