package com.github.pfichtner.testcontainers.virtualavr.util;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class Waiter {

	private final long timeout;
	private final TimeUnit unit;
	private long pollIntervalMillis = 100;

	public Waiter(long timeout, TimeUnit unit) {
		this.timeout = timeout;
		this.unit = unit;
	}

	public Waiter withPollInterval(long pollInterval, TimeUnit timeUnit) {
		this.pollIntervalMillis = timeUnit.toMillis(pollInterval);
		return this;
	}

	/**
	 * Wait until the given condition returns true or timeout expires.
	 */
	public boolean waitUntil(BooleanSupplier condition) {
		Instant deadline = Instant.now().plusMillis(unit.toMillis(timeout));
		while (Instant.now().isBefore(deadline)) {
			if (condition.getAsBoolean()) {
				return true;
			}
			try {
				MILLISECONDS.sleep(pollIntervalMillis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

}
