package com.github.pfichtner.testcontainers.virtualavr;

import static com.github.pfichtner.testcontainers.virtualavr.DefaultVirtualAvrConnection.connectionToVirtualAvr;
import static com.github.pfichtner.testcontainers.virtualavr.util.GracefulCloseProxy.wrapWithGracefulClose;
import static java.lang.Boolean.TRUE;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;
import static org.testcontainers.containers.BindMode.READ_ONLY;
import static org.testcontainers.containers.BindMode.READ_WRITE;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public class VirtualAvrContainer<SELF extends VirtualAvrContainer<SELF>> extends GenericContainer<SELF> {

	enum EnvVars {
		VIRTUALDEVICE, DEBUG, VERBOSITY, BAUDRATE, DEVICEUSER, DEVICEGROUP, DEVICEMODE, PAUSE_ON_START,
		BUILD_EXTRA_FLAGS, FILENAME, PUBLISH_MILLIS, SERIAL_TCP_LISTEN, REALTIME, EEPROM_FILE, EEPROM_SIZE, WS_TOKEN,
		WATCH, WATCH_INTERVAL
	}

	private static final String VIRTUAL_AVR = "VirtualAVR";

	private static final Logger logger = LoggerFactory.getLogger(VirtualAvrContainer.class);
	private static final Set<String> doMask = Set.of(EnvVars.WS_TOKEN.name());

	public static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("pfichtner/virtualavr");
	public static final String DEFAULT_TAG = "latest";

	private static final int DEFAULT_BAUDRATE = 115_200;
	protected static final int WEBSOCKET_PORT = 8080;
	static final int SERIAL_TCP_LISTEN_PORT = 3030;
	private static final String SOCAT_VERBOSE = "-d -d -v";

	/**
	 * Path of the file baked into the image that lists the names of the environment
	 * variables the image supports, one per line.
	 */
	static final String SUPPORTED_ENV_FILE = "/usr/local/share/virtualavr/supported-env";

	public static final String hostDev = "/dev";
	public static final String containerDev = "/dev";

	private String ttyDevice = "ttyUSB0";

	private VirtualAvrConnection avr;
	private SerialConnection serialConnection;
	private TcpSerialBridge tcpSerialBridge;
	private boolean tcpSerialBridgeMode;
	private final Set<String> explicitlySet = new HashSet<>();
	private final Set<String> requiredFeatures = new HashSet<>();
	private boolean strictFeatureCheck = true;

	public VirtualAvrContainer() {
		this(DEFAULT_IMAGE_NAME.withTag(DEFAULT_TAG));
	}

	public VirtualAvrContainer(DockerImageName dockerImageName) {
		super(dockerImageName);
		dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);
		withDeviceName(ttyDevice) //
				.withToken(UUID.randomUUID().toString()) //
				.withFileSystemBind(hostDev, containerDev, READ_WRITE) //
				.addExposedPort(WEBSOCKET_PORT);
		explicitlySet.clear();
	}

	/**
	 * Enables the TCP serial bridge: the container listens on a TCP port (see
	 * {@link #withTcpSerialListenMode()}) and a socat process on the host connects
	 * to that port and creates a PTY. That host PTY is the serial device and can be
	 * used via {@link #serialConnection()}.
	 * <p>
	 * This is needed on macOS/Windows with Docker Desktop, where the host
	 * {@code /dev} cannot be bind-mounted into the container.
	 * <p>
	 * The container serves one client at a time, so the bridge and a plain TCP
	 * client cannot be used at the same time - the last of
	 * {@link #withTcpSerialBridge()} and {@link #withTcpSerialListenMode()} wins.
	 *
	 * @return this container instance
	 */
	public VirtualAvrContainer<?> withTcpSerialBridge() {
		withEnv(EnvVars.SERIAL_TCP_LISTEN, SERIAL_TCP_LISTEN_PORT);
		tcpSerialBridgeMode = true;
		return self();
	}

	/**
	 * @return this container instance
	 * @deprecated the TCP connect mode does not exist anymore, use
	 *             {@link #withTcpSerialBridge()} for a host side serial device or
	 *             {@link #withTcpSerialListenMode()} for a plain TCP client
	 */
	@Deprecated
	public VirtualAvrContainer<?> withTcpSerialMode() {
		return withTcpSerialBridge();
	}

	/**
	 * Enables TCP serial listen mode: the container listens on a TCP port and the
	 * test connects to it with a plain TCP socket. No host-side socat and no
	 * {@code /dev} bind mount are needed, so this works on every OS (including
	 * macOS/Windows with Docker Desktop). The mode-specific container configuration
	 * (exposed port, wait strategy, no {@code /dev} bind) is applied in
	 * {@link #start()}.
	 * <p>
	 * The container serves one client at a time, so this mode cannot be combined
	 * with {@link #withTcpSerialBridge()} (its host bridge is a client too) - the
	 * last of both calls wins.
	 *
	 * @return this container instance
	 */
	public VirtualAvrContainer<?> withTcpSerialListenMode() {
		tcpSerialBridgeMode = false;
		return withEnv(EnvVars.SERIAL_TCP_LISTEN, SERIAL_TCP_LISTEN_PORT);
	}

	/**
	 * Returns the host port the container's TCP serial listener is mapped to. Only
	 * meaningful in TCP serial listen mode (see
	 * {@link #withTcpSerialListenMode()}).
	 *
	 * @return the host port to connect to with a plain TCP socket
	 */
	public int serialTcpListenPort() {
		return getMappedPort(SERIAL_TCP_LISTEN_PORT);
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

	public VirtualAvrContainer<?> withEepromFile(File eepromFile, int eepromSize) {
		return withEepromFile(eepromFile).withEnv(EnvVars.EEPROM_SIZE, eepromSize);
	}

	public VirtualAvrContainer<?> withEepromFile(File eepromFile) {
		try {
			if (!eepromFile.exists() && !eepromFile.createNewFile()) {
				throw new IOException("Could not create " + eepromFile);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		String containerPath = format("/eeprom/%s", eepromFile.getName());
		return withEnv(EnvVars.EEPROM_FILE, containerPath) //
				.withFileSystemBind(eepromFile.getAbsolutePath(), containerPath, READ_WRITE);
	}

	public VirtualAvrContainer<?> withRealtime(boolean realtime) {
		return withEnv(EnvVars.REALTIME, realtime);
	}

	public VirtualAvrContainer<?> withPublishMillis(int millis) {
		return withEnv(EnvVars.PUBLISH_MILLIS, millis);
	}

	/**
	 * Enables watch mode: monitors the sketch file(s) in {@code /sketch} for
	 * changes and automatically recompiles and reloads the firmware.
	 *
	 * @return this container instance
	 */
	public VirtualAvrContainer<?> withWatch() {
		return withWatch(true);
	}

	/**
	 * Enables watch mode with the given polling interval for file change detection:
	 * monitors the sketch file(s) in {@code /sketch} for changes and automatically
	 * recompiles and reloads the firmware.
	 *
	 * @param seconds the polling interval in seconds
	 * @return this container instance
	 */
	public VirtualAvrContainer<?> withWatchInterval(int seconds) {
		return withWatch().withEnv(EnvVars.WATCH_INTERVAL, seconds);
	}

	/**
	 * Enables or disables watch mode.
	 *
	 * @param watch {@code true} to monitor the sketch file(s) for changes and
	 *              automatically recompile and reload the firmware
	 * @return this container instance
	 */
	public VirtualAvrContainer<?> withWatch(boolean watch) {
		return withEnv(EnvVars.WATCH, watch);
	}

	/**
	 * Enables or disables strict validation of the requested features against the
	 * env vars reported as supported by the used docker image. If the image does
	 * not report its supported env vars (e.g. an image predating the capability
	 * list), a requested feature cannot be validated: by default this
	 * undeterminable state fails fast by throwing an {@link IllegalStateException}
	 * from {@link #start()}. Disabling this only logs a warning instead. A feature
	 * the image is known to not support always fails, regardless of this setting.
	 *
	 * @param strict {@code true} (the default) to fail if the supported features
	 *               cannot be determined; {@code false} to only log a warning
	 * @return this container instance
	 */
	public VirtualAvrContainer<?> withStrictFeatureCheck(boolean strict) {
		this.strictFeatureCheck = strict;
		return self();
	}

	/**
	 * Sets the token the container requires for WebSocket connections. By default a
	 * random token is generated when the container is created, so authentication is
	 * enabled unless it is disabled by passing {@code ""} or {@code null}.
	 *
	 * @param token the token to use, or {@code ""}/{@code null} to disable
	 *              authentication
	 * @return this container instance
	 */
	public VirtualAvrContainer<?> withToken(String token) {
		return withEnv(EnvVars.WS_TOKEN, token);
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

	@Override
	public SELF withEnv(String key, String value) {
		explicitlySet.add(key);
		requiredFeatures.add(key);
		return super.withEnv(key, value);
	}

	String getEnv(EnvVars envVar) {
		return getEnvMap().get(envVar.name());
	}

	Optional<String> token() {
		return Optional.ofNullable(getEnv(EnvVars.WS_TOKEN)).filter(not(String::isEmpty));
	}

	public synchronized VirtualAvrConnection avr() {
		if (avr == null) {
			avr = wrapWithGracefulClose(connectionToVirtualAvr(this), VirtualAvrConnection.class);
		}
		return avr;
	}

	protected int getWebsocketPort() {
		return getMappedPort(WEBSOCKET_PORT);
	}

	public synchronized SerialConnection serialConnection() throws IOException {
		// TODO a shared connection that can be closed is not very smart
		if (serialConnection == null || serialConnection.isClosed()) {
			serialConnection = new SerialConnection(serialPortDescriptor(), baudrate().orElse(DEFAULT_BAUDRATE));
		}
		return serialConnection;
	}

	public String serialPortDescriptor() {
		if (tcpSerialBridge != null) {
			// the host bridge provides the serial device
			return tcpSerialBridge.devicePath().toString();
		}
		if (getEnv(EnvVars.SERIAL_TCP_LISTEN) != null) {
			throw new IllegalStateException(
					"SERIAL_TCP_LISTEN mode has no local serial device, connect with a plain TCP socket to serialTcpListenPort() instead");
		}
		return format("%s/%s", hostDev, ttyDevice);
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
		boolean tcpSerialListenMode = getEnv(EnvVars.SERIAL_TCP_LISTEN) != null;
		if (tcpSerialListenMode) {
			prepareTcpSerialListenMode();
		}
		logger.info("Starting VirtualAVR container in {} mode",
				tcpSerialListenMode ? "TCP serial listen" : "standard PTY");
		super.start();
		try {
			verifyImageFeaturesSupportsRequestedFeatures();
			if (tcpSerialBridgeMode) {
				tcpSerialBridge = new TcpSerialBridge(this);
				tcpSerialBridge.startBridge(getMappedPort(SERIAL_TCP_LISTEN_PORT));
			}
		} catch (RuntimeException e) {
			try {
				stop();
			} catch (RuntimeException stopFailure) {
				e.addSuppressed(stopFailure);
			}
			throw e;
		}
		debug().filter(TRUE::equals).ifPresent(b -> debugStartOut());
	}

	private void prepareTcpSerialListenMode() {
		addExposedPort(SERIAL_TCP_LISTEN_PORT);
		// Remove the /dev bind mount since there is no PTY in this mode
		getBinds().removeIf(b -> b.getVolume().getPath().equals(containerDev));
		// The default wait strategy waits for the first exposed port (the
		// WebSocket), which in this mode only comes up when the first TCP client
		// connects (socat has no fork). Wait for the entrypoint to finish
		// compiling instead; a TCP connect as wait probe would itself trigger
		// and then kill the simulator.
		waitingFor(Wait.forLogMessage(".*Starting simulator.*", 1));
	}

	/**
	 * Verifies that the used image supports all requested features. The image
	 * reports the env vars it supports in a file (see
	 * {@value #SUPPORTED_ENV_FILE}). If the image does not carry such a file, the
	 * supported features cannot be determined and strict checking
	 * ({@link #withStrictFeatureCheck(boolean)}) is enabled by default; pass
	 * {@code false} to only log a warning instead.
	 *
	 * @throws IllegalStateException if the image is known to not support a
	 *                               requested feature, or if strict checking is
	 *                               enabled and the supported features cannot be
	 *                               determined
	 */
	void verifyImageFeaturesSupportsRequestedFeatures() {
		Set<String> supported = imageSupportedEnvVars();
		String missing = requiredFeatures.stream().filter(not(supported::contains)).collect(joining(", "));
		if (!missing.isEmpty()) {
			String message = format("Image '%s' does not support the requested feature(s): %s", getDockerImageName(),
					missing);
			if (strictFeatureCheck) {
				throw new IllegalStateException(message);
			}
			logger.warn(message);
		}
	}

	/**
	 * Reads the env vars the used image reports as supported. Returns an empty set
	 * if the image does not carry the file (e.g. an image predating it) or if it
	 * cannot be read.
	 *
	 * @return the set of env var names supported by the image or empty if unknown
	 */
	Set<String> imageSupportedEnvVars() {
		try {
			ExecResult result = execInContainer("cat", SUPPORTED_ENV_FILE);
			if (result.getExitCode() == 0) {
				return parseSupportedEnvVars(result.getStdout());
			}
			logger.warn("Could not read '%s' from image '%s': %s", SUPPORTED_ENV_FILE, getDockerImageName(),
					result.getStderr());
			return Set.of();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Parses the content of the supported-env file. Blank lines and lines starting
	 * with {@code #} are ignored.
	 *
	 * @param content the file content
	 * @return the set of env var names contained in the content
	 */
	static Set<String> parseSupportedEnvVars(String content) {
		return content.lines() //
				.map(String::trim) //
				.filter(not(String::isEmpty)) //
				.filter(not(line -> line.startsWith("#"))) //
				.collect(toSet());
	}

	private void debugStartOut() {
		logger.info("{} container started: ID={}", VIRTUAL_AVR, getContainerId());
		logger.info("Container environment variables:");
		getEnvMap().forEach((k, v) -> logger.info("\t{}={}", k, doMask(k) ? "***" : v));

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

	private boolean doMask(String key) {
		return doMask.contains(key) && explicitlySet.contains(key);
	}

	@Override
	public void stop() {
		super.stop();
		Optional.ofNullable(avr).ifPresent(VirtualAvrConnection::close);
		Optional.ofNullable(tcpSerialBridge).ifPresent(TcpSerialBridge::finalizeStop);
		avr = null;
		logger.info("{} container stopped", VIRTUAL_AVR);
	}

}
