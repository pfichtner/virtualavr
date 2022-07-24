package com.ardulink.sketch;

import static jssc.SerialPort.BAUDRATE_115200;
import static jssc.SerialPort.DATABITS_8;
import static jssc.SerialPort.PARITY_NONE;
import static jssc.SerialPort.STOPBITS_1;
import static org.awaitility.Awaitility.await;

import java.util.function.Predicate;

import jssc.SerialPort;
import jssc.SerialPortEvent;
import jssc.SerialPortException;

public class SerialConnection implements AutoCloseable {

	private final SerialPort port;
	private final StringBuilder received = new StringBuilder();

	public SerialConnection(String name) throws SerialPortException {
		port = new SerialPort(name);
		port.openPort();
		port.setParams(BAUDRATE_115200, DATABITS_8, STOPBITS_1, PARITY_NONE);
		port.addEventListener(this::eventReceived);
	}

	private void eventReceived(SerialPortEvent event) {
		if (event.isRXCHAR() && event.getEventValue() > 0) {
			try {
				received.append(port.readString(event.getEventValue()));
			} catch (SerialPortException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public void send(String string) throws SerialPortException, InterruptedException {
		port.writeString(string);
	}

	public String received() {
		return received.toString();
	}

	public void clearReceived() {
		received.setLength(0);
	}

	@Override
	public void close() throws Exception {
		port.closePort();
	}

	SerialConnection sendAwait(String send, String awaitResponse) throws Exception {
		sendAwait(send, r -> r.contains(awaitResponse));
		return this;
	}

	void sendAwait(String send, Predicate<String> callable) throws Exception {
		send(send);
		await().until(() -> callable.test(received()));
		clearReceived();
	}

	SerialConnection waitReceivedAnything() throws Exception {
		sendAwait("", r -> !r.isEmpty());
		return this;
	}

}
