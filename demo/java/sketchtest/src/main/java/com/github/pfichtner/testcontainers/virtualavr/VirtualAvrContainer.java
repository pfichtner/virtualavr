package com.github.pfichtner.testcontainers.virtualavr;

import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.connectionToVirtualAvr;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;
import static org.testcontainers.containers.BindMode.READ_ONLY;
import static org.testcontainers.containers.BindMode.READ_WRITE;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public class VirtualAvrContainer<SELF extends VirtualAvrContainer<SELF>> extends GenericContainer<SELF> {

	private static final String VIRTUAL_AVR = "VirtualAVR";

	private static final Logger logger = LoggerFactory.getLogger(VirtualAvrConnection.class);

	private static final String BAUDRATE = "BAUDRATE";
	private static final String DEBUG = "DEBUG";
	private static final int DEFAULT_BAUDRATE = 115200;

	public static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("pfichtner/virtualavr");
	public static final String DEFAULT_TAG = "latest";

	public static final String hostDev = "/dev";
	public static final String containerDev = "/dev";
	protected static final int WEBSOCKET_PORT = 8080;

	private String ttyDevice = "ttyUSB0";

	private VirtualAvrConnection avr;
	private SerialConnection serialConnection;
	private TcpSerialModeSupport tcpSerialModeSupport;

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
	 * Enables TCP serial mode for use on macOS/Windows with Docker Desktop. In this
	 * mode, a socat process is started on the host that creates a PTY and listens
	 * on TCP. The container connects to this TCP port instead of creating a local
	 * PTY device.
	 *
	 * @return this container instance
	 */
	public VirtualAvrContainer<?> withTcpSerialMode() {
		tcpSerialModeSupport = new TcpSerialModeSupport(this);
		return self();
	}

	public VirtualAvrContainer<?> withDeviceName(String ttyDevice) {
		this.ttyDevice = ttyDevice;
		withEnv("VIRTUALDEVICE", format("%s/%s", containerDev, ttyDevice));
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

	public VirtualAvrContainer<?> withDebug() {
		return withDebug(true);
	}

	private VirtualAvrContainer<?> withDebug(boolean debug) {
		withEnv("DEBUG", String.valueOf(debug));
		return self();
	}

	public synchronized VirtualAvrConnection avr() {
		if (avr == null) {
			logger.info("WebSocket: Connecting to ws://localhost:{}", getFirstMappedPort());
			avr = connectionToVirtualAvr(this);
			logger.info("WebSocket: Connection established: isOpen={}", avr.isOpen());
		}
		return avr;
	}

	public synchronized SerialConnection serialConnection() throws IOException {
		// TODO a shared connection that can be closed is not very smart
		if (serialConnection == null || serialConnection.isClosed()) {
			serialConnection = new SerialConnection(serialPortDescriptor(), baudrate());
		}
		return serialConnection;
	}

	public String serialPortDescriptor() {
		return Optional.ofNullable(tcpSerialModeSupport) //
				.map(TcpSerialModeSupport::devicePath) //
				.map(Object::toString) //
				.orElseGet(() -> format("%s/%s", hostDev, ttyDevice));
	}

	private int baudrate() {
		return Optional.ofNullable(getEnvMap().get(BAUDRATE)).map(Integer::parseInt).orElse(DEFAULT_BAUDRATE);
	}

	private boolean debug() {
		return Optional.ofNullable(getEnvMap().get(DEBUG)).map(Boolean::parseBoolean).orElse(Boolean.FALSE);
	}

	@Override
	public void start() {
		logger.info("Starting VirtualAVR container in {} mode",
				tcpSerialModeSupport == null ? "standard PTY" : "TCP serial");
		Optional.ofNullable(tcpSerialModeSupport).ifPresent(t -> t.withDebug(debug()).prepareStart());
		super.start();
		if (debug()) {
			debugStartOut();
		}
	}

	private void debugStartOut() {
		// Log container info after start
		logger.info("{} container started: ID={}", VIRTUAL_AVR, getContainerId());
		logger.info("Container environment variables:");
		getEnvMap().forEach((k, v) -> logger.info("\t{}={}", k, v));

		// Wait a moment for the container's entrypoint to establish connections
		try {
			SECONDS.sleep(2);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// Log container logs to help debug
		logger.info("Container logs (first 50 lines):");
		Stream.of(getLogs().split("\\R")).limit(50).forEach(l -> logger.info("\t[container] {}", l));

		// Check container health
		logger.info("Container state: isRunning={}, isHealthy={}", isRunning(), isHealthy());
	}

	@Override
	public void stop() {
		super.stop();
		Optional.ofNullable(avr).ifPresent(VirtualAvrConnection::close);
		Optional.ofNullable(tcpSerialModeSupport).ifPresent(TcpSerialModeSupport::finalizeStop);
		avr = null;
		logger.info("{} container stopped", VIRTUAL_AVR);
	}

}
