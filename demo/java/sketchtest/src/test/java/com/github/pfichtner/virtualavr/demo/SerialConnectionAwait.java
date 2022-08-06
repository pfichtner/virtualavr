package com.github.pfichtner.virtualavr.demo;

import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import java.util.function.Predicate;

import com.github.pfichtner.virtualavr.SerialConnection;

public class SerialConnectionAwait {

	private final SerialConnection connection;

	private SerialConnectionAwait(SerialConnection connection) {
		this.connection = connection;
	}

	public static SerialConnectionAwait awaiter(SerialConnection connection) {
		return new SerialConnectionAwait(connection);
	}

	public SerialConnectionAwait sendAwait(String send, String awaitResponse) throws Exception {
		sendAwait(send, r -> r.contains(awaitResponse));
		return this;
	}

	public void sendAwait(String send, Predicate<String> callable) throws Exception {
		connection.send(send);
		await().until(() -> callable.test(connection.received()));
		connection.clearReceived();
	}

	public SerialConnectionAwait waitReceivedAnything() throws Exception {
		sendAwait("", r -> !r.isEmpty());
		return this;
	}

}
