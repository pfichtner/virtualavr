package com.ardulink.sketch;

import static com.github.pfichtner.virtualavr.VirtualAvrConnection.connectionToVirtualAvr;
import static org.testcontainers.containers.BindMode.READ_ONLY;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.NumberFormat;
import java.text.ParseException;

import org.testcontainers.containers.GenericContainer;

import com.github.pfichtner.virtualavr.VirtualAvrConnection;

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
			VirtualAvrConnection connectionToVirtualAvr = connectionToVirtualAvr(virtualAvrContainer);

			try {
				BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
				while (true) {
					System.out.print("$ ");
					String input = reader.readLine();

					String[] split = input.split("=");
					if (split.length != 2) {
						System.err.println("Unable to split " + input);
					} else {
						String pin = split[0];
						try {
							Number state = NumberFormat.getNumberInstance().parse(split[1]);
							System.out.println("Setting " + pin + " to " + state);
							connectionToVirtualAvr.setPinState(pin, state.intValue());
						} catch (ParseException e) {
							boolean state = Boolean.parseBoolean(split[1]);
							System.out.println("Setting " + pin + " to " + state);
							connectionToVirtualAvr.setPinState(pin, state);
						}
						
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			}

		}
	}

	public static void main(String... args) throws InterruptedException {
		new ArdulinkArduinoSimulator(args);
	}

}
