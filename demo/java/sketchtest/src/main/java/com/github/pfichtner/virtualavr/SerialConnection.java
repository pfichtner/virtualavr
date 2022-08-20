package com.github.pfichtner.virtualavr;

import static jssc.SerialPort.DATABITS_8;
import static jssc.SerialPort.PARITY_NONE;
import static jssc.SerialPort.STOPBITS_1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import jssc.SerialPort;
import jssc.SerialPortEvent;
import jssc.SerialPortException;

public class SerialConnection implements AutoCloseable {

	private final SerialPort port;
	private final ByteArrayOutputStream received = new ByteArrayOutputStream();

	public SerialConnection(String name, int baudrate) throws IOException {
		port = new SerialPort(name);
		try {
			port.openPort();
			port.setParams(baudrate, DATABITS_8, STOPBITS_1, PARITY_NONE);
			// port.setEventsMask(SerialPort.MASK_RXCHAR);
			port.addEventListener(this::eventReceived);
		} catch (SerialPortException e) {
			throw new IOException(e);
		}
	}

	private void eventReceived(SerialPortEvent event) {
		if (event.isRXCHAR() && event.getEventValue() > 0) {
			try {
				received.write(port.readBytes(event.getEventValue()));
			} catch (SerialPortException e) {
				throw new RuntimeException(e);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public void send(String string) throws IOException {
		try {
			port.writeString(string);
		} catch (SerialPortException e) {
			throw new IOException(e);
		}
	}

	public void send(byte[] bytes) throws IOException {
		try {
			port.writeBytes(bytes);
		} catch (SerialPortException e) {
			throw new IOException(e);
		}
	}

	public String received() {
		return new String(receivedBytes());
	}

	public byte[] receivedBytes() {
		return received.toByteArray();
	}

	public SerialConnection clearReceived() {
		received.reset();
		return this;
	}

	@Override
	public void close() throws Exception {
		port.closePort();
	}

}
