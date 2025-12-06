package com.github.pfichtner.testcontainers.virtualavr.main;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.NumberFormat;
import java.text.ParseException;

import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrContainer;

/**
 * Creates a virtual arduino that exposes a serial device whose port states can
 * be controlled via stdin.
 */
public class ArduinoSimulator {

	public ArduinoSimulator(String... args) throws InterruptedException {
		if (args.length == 0) {
			throw new IllegalArgumentException("Pass .ino/.hex/.zip file as argument");
		}
		File firmware = new File(args[0]);

		try (VirtualAvrContainer<?> virtualAvrContainer = new VirtualAvrContainer<>()) {
			virtualAvrContainer.withSketchFile(firmware).start();

			// do sysouts
			VirtualAvrConnection connectionToVirtualAvr = virtualAvrContainer.avr();

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
							connectionToVirtualAvr.pinState(pin, state.intValue());
						} catch (ParseException e) {
							boolean state = Boolean.parseBoolean(value);
							System.out.println("Setting " + pin + " to " + state);
							connectionToVirtualAvr.pinState(pin, state);
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
