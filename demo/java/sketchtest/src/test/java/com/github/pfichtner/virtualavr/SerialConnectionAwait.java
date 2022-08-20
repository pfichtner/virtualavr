package com.github.pfichtner.virtualavr;

import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.function.Predicate;

import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

public class SerialConnectionAwait {

	private final SerialConnection connection;
	private Duration duration;

	private SerialConnectionAwait(SerialConnection connection) {
		this.connection = connection;
	}

	public static SerialConnectionAwait awaiter(SerialConnection connection) {
		return new SerialConnectionAwait(connection);
	}

	public SerialConnectionAwait withTimeout(Duration duration) {
		this.duration = duration;
		return this;
	}

	public SerialConnectionAwait sendAwait(byte[] data, byte[] awaitResponse) throws Exception {
		sendAwait(data, r -> new String(r).contains(new String(awaitResponse)));
		return this;
	}

	public SerialConnectionAwait sendAwait(String data, String awaitResponse) throws Exception {
		sendAwait(data, r -> r.contains(awaitResponse));
		return this;
	}

	public SerialConnectionAwait sendAwait(byte[] data, Predicate<byte[]> predicate) throws Exception {
		connection.send(data);
		return awaitReceivedBytes(predicate);
	}

	public SerialConnectionAwait sendAwait(String data, Predicate<String> predicate) throws Exception {
		connection.send(data);
		return awaitReceived(predicate);
	}

	public SerialConnectionAwait awaitReceived(Predicate<String> predicate) {
		return awaitReceivedBytes(bytes -> predicate.test(new String(bytes)));
	}

	public SerialConnectionAwait awaitReceivedBytes(Predicate<byte[]> predicate) {
		conditionFactory().until(() -> predicate.test(connection.receivedBytes()));
		connection.clearReceived();
		return this;
	}

	private ConditionFactory conditionFactory() {
		return this.duration == null ? await() : await().timeout(duration);
	}

	public SerialConnectionAwait waitReceivedAnything() throws Exception {
		return awaitReceived(r -> !r.isEmpty());
	}

}
