package com.github.pfichtner.virtualavr;

import static com.github.pfichtner.virtualavr.VirtualAvrConnection.connectionToVirtualAvr;
import static jssc.SerialPort.BAUDRATE_115200;
import static org.testcontainers.containers.BindMode.READ_ONLY;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public class VirtualAvrContainer<SELF extends VirtualAvrContainer<SELF>> extends GenericContainer<SELF> {

	private static final String BAUDRATE = "BAUDRATE";

	public static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("pfichtner/virtualavr");
	public static final String DEFAULT_TAG = "latest";

	private static final String hostDev = "/dev";
	private static final String containerDev = "/dev";
	private static final int WEBSOCKET_PORT = 8080;

	private String ttyDevice = "ttyUSB0";

	private VirtualAvrConnection avr;
	private SerialConnection serialConnection;

	public VirtualAvrContainer() {
		this(DEFAULT_IMAGE_NAME.withTag(DEFAULT_TAG));
	}

	public VirtualAvrContainer(DockerImageName dockerImageName) {
		super(dockerImageName);
		dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);
		withDeviceName(ttyDevice) //
				.withFileSystemBind(hostDev, containerDev) //
				.addExposedPort(WEBSOCKET_PORT);
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

	public VirtualAvrContainer<?> withDeviceGroup(String group) {
		withEnv("DEVICEGROUP", group);
		return self();
	}

	public VirtualAvrContainer<?> withDeviceMode(int deviceMode) {
		withEnv("DEVICEMODE", String.valueOf(deviceMode));
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
		return new SerialConnection(hostDev + "/" + ttyDevice, baudrate());
	}

	private int baudrate() {
		return Optional.ofNullable(getEnvMap().get(BAUDRATE)).map(Integer::parseInt).orElse(BAUDRATE_115200);
	}


}
