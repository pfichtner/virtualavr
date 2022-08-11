package com.github.pfichtner.virtualavr;

import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import java.util.function.Predicate;

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

	public SerialConnectionAwait sendAwait(String send, Predicate<String> predicate) throws Exception {
		connection.send(send);
		return awaitReceived(predicate);
	}

	public SerialConnectionAwait awaitReceived(Predicate<String> predicate) {
		await().until(() -> predicate.test(connection.received()));
		connection.clearReceived();
		return this;
	}

	public SerialConnectionAwait waitReceivedAnything() throws Exception {
		return awaitReceived(r -> !r.isEmpty());
	}

}
