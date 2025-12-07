package com.github.pfichtner.testcontainers.virtualavr;

import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import java.io.IOException;
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

	public SerialConnectionAwait sendAwait(byte[] data, byte[] awaitResponse) throws IOException {
		sendAwait(data, r -> new String(r).contains(new String(awaitResponse)));
		return this;
	}

	public SerialConnectionAwait sendAwait(String data, String awaitResponse) throws IOException {
		sendAwait(data, r -> r.contains(awaitResponse));
		return this;
	}

	public SerialConnectionAwait sendAwait(byte[] data, Predicate<byte[]> predicate) throws IOException {
		return send(data).awaitReceivedBytes(predicate);
	}

	public SerialConnectionAwait send(byte[] data) throws IOException {
		connection.send(data);
		return this;
	}

	public SerialConnectionAwait sendAwait(String data, Predicate<String> predicate) throws IOException {
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

	public SerialConnectionAwait waitReceivedAnything() {
		return awaitReceived(r -> !r.isEmpty());
	}

}
