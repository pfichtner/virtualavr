package com.github.pfichtner.virtualavr;

import static com.github.pfichtner.virtualavr.VirtualAvrConnection.connectionToVirtualAvr;
import static org.testcontainers.containers.BindMode.READ_ONLY;

import java.io.File;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import jssc.SerialPortException;

public class VirtualAvrContainer<SELF extends VirtualAvrContainer<SELF>> extends GenericContainer<SELF> {

	private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("pfichtner/virtualavr");
	private static final String DEFAULT_TAG = "latest";

	private static final String hostDev = "/dev";
	private static final String containerDev = "/dev";
	private static final String ttyDevice = "ttyUSB0";

	private static final int WEBSOCKET_PORT = 8080;

	private VirtualAvrConnection avr;
	private SerialConnection serialConnection;

	public VirtualAvrContainer() {
		this(DEFAULT_IMAGE_NAME.withTag(DEFAULT_TAG));
	}

	public VirtualAvrContainer(DockerImageName dockerImageName) {
		super(dockerImageName);
		dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);
		withEnv("VIRTUALDEVICE", containerDev + "/" + ttyDevice) //
				.withFileSystemBind(hostDev, containerDev) //
				.addExposedPort(WEBSOCKET_PORT);
	}

	public VirtualAvrContainer<SELF> withSketchFile(File sketchFile) {
		withEnv("FILENAME", sketchFile.getName()) //
				.withFileSystemBind(sketchFile.getParent(), "/sketch/", READ_ONLY);
		return self();
	}

	public synchronized VirtualAvrConnection getAvr() {
		if (avr == null) {
			avr = connectionToVirtualAvr(this);
		}
		return avr;
	}

	public synchronized SerialConnection getSerialConnection() throws SerialPortException {
		if (serialConnection == null) {
			serialConnection = new SerialConnection(hostDev + "/" + ttyDevice);
		}
		return serialConnection;
	}

}
