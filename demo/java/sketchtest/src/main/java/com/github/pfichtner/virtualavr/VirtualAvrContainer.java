package com.github.pfichtner.virtualavr;

import static com.github.pfichtner.virtualavr.VirtualAvrConnection.connectionToVirtualAvr;
import static org.testcontainers.containers.BindMode.READ_ONLY;

import java.io.File;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import jssc.SerialPortException;

public class VirtualAvrContainer<SELF extends VirtualAvrContainer<SELF>> extends GenericContainer<SELF> {

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
		withDeviceName(containerDev + "/" + ttyDevice) //
				.withFileSystemBind(hostDev, containerDev) //
				.addExposedPort(WEBSOCKET_PORT);
	}

	public VirtualAvrContainer<?> withDeviceName(String ttyDevice) {
		this.ttyDevice = ttyDevice;
		withEnv("VIRTUALDEVICE", containerDev + "/" + ttyDevice);
		return self();
	}

	public VirtualAvrContainer<SELF> withSketchFile(File sketchFile) {
		withEnv("FILENAME", sketchFile.getName()) //
				.withFileSystemBind(sketchFile.getParent(), "/sketch/", READ_ONLY);
		return self();
	}

	public synchronized VirtualAvrConnection avr() {
		if (avr == null) {
			avr = connectionToVirtualAvr(this);
		}
		return avr;
	}

	public synchronized SerialConnection serialConnection() throws SerialPortException {
		if (serialConnection == null) {
			serialConnection = new SerialConnection(hostDev + "/" + ttyDevice);
		}
		return serialConnection;
	}

}
