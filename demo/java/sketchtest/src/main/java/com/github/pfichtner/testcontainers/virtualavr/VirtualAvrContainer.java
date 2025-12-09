package com.github.pfichtner.testcontainers.virtualavr;

import static com.github.pfichtner.testcontainers.virtualavr.DefaultVirtualAvrConnection.connectionToVirtualAvr;
import static com.github.pfichtner.testcontainers.virtualavr.util.GracefulCloseProxy.wrapWithGracefulClose;
import static java.lang.Boolean.TRUE;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.function.Predicate.not;
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

	enum EnvVars {
		VIRTUALDEVICE, DEBUG, VERBOSITY, BAUDRATE, DEVICEUSER, DEVICEGROUP, DEVICEMODE, PAUSE_ON_START,
		BUILD_EXTRA_FLAGS, FILENAME, PUBLISH_MILLIS, SERIAL_TCP
	}

	private static final String VIRTUAL_AVR = "VirtualAVR";

	private static final Logger logger = LoggerFactory.getLogger(VirtualAvrContainer.class);

	public static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("pfichtner/virtualavr");
	public static final String DEFAULT_TAG = "latest";

	private static final int DEFAULT_BAUDRATE = 115_200;
	protected static final int WEBSOCKET_PORT = 8080;
	private static final String SOCAT_VERBOSE = "-d -d -v";

	public static final String hostDev = "/dev";
	public static final String containerDev = "/dev";

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
		return withEnv(EnvVars.VIRTUALDEVICE, format("%s/%s", containerDev, ttyDevice));
	}

	public VirtualAvrContainer<?> withBaudrate(int baudrate) {
		return withEnv(EnvVars.BAUDRATE, baudrate);
	}

	public VirtualAvrContainer<?> withDeviceUser(String user) {
		return withEnv(EnvVars.DEVICEUSER, user);
	}

	public VirtualAvrContainer<?> withDeviceGroup(String group) {
		return withEnv(EnvVars.DEVICEGROUP, group);
	}

	public VirtualAvrContainer<?> withDeviceMode(int deviceMode) {
		return withEnv(EnvVars.DEVICEMODE, deviceMode);
	}

	public VirtualAvrContainer<?> withPausedStartup() {
		return withEnv(EnvVars.PAUSE_ON_START, true);
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
		return withEnv(EnvVars.BUILD_EXTRA_FLAGS, cFlags);
	}

	public VirtualAvrContainer<?> withSketchFile(File sketchFile) {
		return withEnv(EnvVars.FILENAME, sketchFile.getName()) //
				.withFileSystemBind(sketchFile.getParent(), "/sketch/", READ_ONLY);
	}

	public VirtualAvrContainer<?> withPublishMillis(int millis) {
		return withEnv(EnvVars.PUBLISH_MILLIS, millis);
	}

	public VirtualAvrContainer<?> withDebug() {
		return withDebug(true);
	}

	private VirtualAvrContainer<?> withDebug(boolean debug) {
		VirtualAvrContainer<?> self = withEnv(EnvVars.DEBUG, debug);
		return debug && getEnv(EnvVars.VERBOSITY) == null //
				? self.withEnv(EnvVars.VERBOSITY, SOCAT_VERBOSE) //
				: self;
	}

	VirtualAvrContainer<?> withEnv(EnvVars envVar, Object value) {
		return withEnv(envVar.name(), value == null ? null : String.valueOf(value));
	}

	String getEnv(EnvVars envVar) {
		return getEnvMap().get(envVar.name());
	}

	public synchronized VirtualAvrConnection avr() {
		if (avr == null) {
			logger.info("WebSocket: Connecting to ws://localhost:{}", getFirstMappedPort());
			avr = wrapWithGracefulClose(connectionToVirtualAvr(this), VirtualAvrConnection.class);
			logger.info("WebSocket: Connection established: isConnected={}", avr.isConnected());
		}
		return avr;
	}

	public synchronized SerialConnection serialConnection() throws IOException {
		// TODO a shared connection that can be closed is not very smart
		if (serialConnection == null || serialConnection.isClosed()) {
			serialConnection = new SerialConnection(serialPortDescriptor(), baudrate().orElse(DEFAULT_BAUDRATE));
		}
		return serialConnection;
	}

	public String serialPortDescriptor() {
		return Optional.ofNullable(tcpSerialModeSupport) //
				.map(TcpSerialModeSupport::devicePath) //
				.map(Object::toString) //
				.orElseGet(() -> format("%s/%s", hostDev, ttyDevice));
	}

	protected Optional<Boolean> debug() {
		return Optional.ofNullable(getEnv(EnvVars.DEBUG)).map(Boolean::parseBoolean);
	}

	protected Optional<String> socatVerbosity() {
		return Optional.ofNullable(getEnv(EnvVars.VERBOSITY)).filter(not(String::isEmpty));
	}

	protected Optional<Integer> baudrate() {
		return Optional.ofNullable(getEnv(EnvVars.BAUDRATE)).map(Integer::parseInt);
	}

	@Override
	public void start() {
		logger.info("Starting VirtualAVR container in {} mode",
				tcpSerialModeSupport == null ? "standard PTY" : "TCP serial");
		Optional.ofNullable(tcpSerialModeSupport).ifPresent(TcpSerialModeSupport::prepareStart);
		super.start();
		debug().filter(TRUE::equals).ifPresent(b -> debugStartOut());
	}

	private void debugStartOut() {
		logger.info("{} container started: ID={}", VIRTUAL_AVR, getContainerId());
		logger.info("Container environment variables:");
		getEnvMap().forEach((k, v) -> logger.info("\t{}={}", k, v));

		// Wait a moment for the container's entrypoint to establish connections
		try {
			SECONDS.sleep(2);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		int lines = 50;
		logger.info("Container logs (first {} lines):", lines);
		Stream.of(getLogs().split("\\R")).limit(lines).forEach(l -> logger.info("\t[container] {}", l));

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
