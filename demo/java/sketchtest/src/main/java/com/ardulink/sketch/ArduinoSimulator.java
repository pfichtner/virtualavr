package com.ardulink.sketch;

import static com.github.pfichtner.virtualavr.VirtualAvrConnection.connectionToVirtualAvr;
import static org.testcontainers.containers.BindMode.READ_ONLY;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.NumberFormat;
import java.text.ParseException;

import org.testcontainers.containers.GenericContainer;

import com.github.pfichtner.virtualavr.VirtualAvrConnection;

public class ArduinoSimulator {

	private static final int WEBSOCKET_PORT = 8080;

	String hostDev = "/dev";
	String containerDev = "/dev";
	String ttyDevice = "ttyUSB0";

	public ArduinoSimulator(String... args) throws InterruptedException {
		if (args.length == 0) {
			throw new IllegalArgumentException("Pass .ino/.hex/.zip file as argument");
		}
		File firmware = new File(args[0]);
		try (GenericContainer<?> virtualAvrContainer = new GenericContainer<>("pfichtner/virtualavr")) {
			virtualAvrContainer //
					.withEnv("VIRTUALDEVICE", containerDev + "/" + ttyDevice) //
					.withEnv("FILENAME", firmware.getName()) //
					.withFileSystemBind(hostDev, containerDev) //
					.withFileSystemBind(firmware.getParent(), "/sketch/", READ_ONLY) //
					.withExposedPorts(WEBSOCKET_PORT) //
					.start() //
			;

			// do sysouts
			VirtualAvrConnection connectionToVirtualAvr = connectionToVirtualAvr(virtualAvrContainer);

			try {
				BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
				NumberFormat numberParser = NumberFormat.getNumberInstance();
				System.out.println("Waiting for command of format <pin>=<value>, e.g. D13=true or A0=512");
				while (true) {
					System.out.print("$ ");
					String input = reader.readLine();

					String[] split = input.split("=");
					if (split.length != 2) {
						System.err.println("Unable to split " + input);
					} else {
						String pin = split[0].trim();
						String value = split[1].trim();
						try {
							Number state = numberParser.parse(value);
							System.out.println("Setting " + pin + " to " + state);
							connectionToVirtualAvr.setPinState(pin, state.intValue());
						} catch (ParseException e) {
							boolean state = Boolean.parseBoolean(value);
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
		new ArduinoSimulator(args);
	}

}
