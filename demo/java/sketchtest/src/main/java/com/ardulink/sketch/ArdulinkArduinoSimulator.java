package com.ardulink.sketch;

import static com.github.pfichtner.virtualavr.VirtualAvrConnection.connectionToVirtualAvr;
import static org.testcontainers.containers.BindMode.READ_ONLY;

import org.testcontainers.containers.GenericContainer;

public class ArdulinkArduinoSimulator {

	String hostDev = "/dev";
	String containerDev = "/dev";
	String ttyDevice = "ttyUSB0";

	String firmware = "/tmp/ArdulinkProtocol.ino";

	public ArdulinkArduinoSimulator(String... args) throws InterruptedException {
		try (GenericContainer<?> virtualAvrContainer = new GenericContainer<>("pfichtner/virtualavr")) {
			virtualAvrContainer.withEnv("VIRTUALDEVICE", containerDev + "/" + ttyDevice)
					.withFileSystemBind(hostDev, containerDev)
					.withFileSystemBind(args.length > 0 ? args[0] : firmware, "/sketch/sketch.ino", READ_ONLY)
					.withExposedPorts(8080).start();

			// do sysouts
			connectionToVirtualAvr(virtualAvrContainer);

			Object object = new Object();
			synchronized (object) {
				object.wait();
			}
		}
	}

	public static void main(String... args) throws InterruptedException {
		new ArdulinkArduinoSimulator(args);
	}

}
