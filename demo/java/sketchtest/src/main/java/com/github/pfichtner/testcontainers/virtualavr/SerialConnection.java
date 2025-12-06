package com.github.pfichtner.testcontainers.virtualavr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import com.fazecast.jSerialComm.SerialPortInvalidPortException;

public class SerialConnection implements AutoCloseable {

	private final SerialPort port;
	private final ByteArrayOutputStream received = new ByteArrayOutputStream();

	public SerialConnection(String name, int baudrate) throws IOException {
		try {
			port = SerialPort.getCommPort(name);
		} catch (SerialPortInvalidPortException e) {
			throw new IOException("Failed to open port (port may not exist): " + name, e);
		}
		port.setBaudRate(baudrate);
		port.setNumDataBits(8);
		port.setNumStopBits(SerialPort.ONE_STOP_BIT);
		port.setParity(SerialPort.NO_PARITY);
		if (!port.openPort()) {
			throw new IOException("Failed to open port: " + name);
		}
		port.addDataListener(new SerialPortDataListener() {
			@Override
			public int getListeningEvents() {
				return SerialPort.LISTENING_EVENT_DATA_RECEIVED;
			}

			@Override
			public void serialEvent(SerialPortEvent event) {
				if (event.getEventType() == SerialPort.LISTENING_EVENT_DATA_RECEIVED) {
					byte[] data = event.getReceivedData();
					if (data != null && data.length > 0) {
						synchronized (received) {
							try {
								received.write(data);
							} catch (IOException e) {
								throw new RuntimeException(e);
							}
						}
					}
				}
			}
		});
	}

	public String portDescription() {
		return port.getPortDescription();
	}

	public void send(String string) throws IOException {
		byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
		int written = port.writeBytes(bytes, bytes.length);
		if (written < 0) {
			throw new IOException("Failed to write to serial port");
		}
	}

	public void send(byte[] bytes) throws IOException {
		int written = port.writeBytes(bytes, bytes.length);
		if (written < 0) {
			throw new IOException("Failed to write to serial port");
		}
	}

	public String received() {
		return new String(receivedBytes());
	}

	public byte[] receivedBytes() {
		synchronized (received) {
			return received.toByteArray();
		}
	}

	public SerialConnection clearReceived() {
		synchronized (received) {
			received.reset();
		}
		return this;
	}

	public boolean isClosed() {
		return !port.isOpen();
	}

	@Override
	public void close() throws Exception {
		port.closePort();
	}

}
