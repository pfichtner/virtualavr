package com.github.pfichtner.testcontainers.virtualavr.tests;

import static com.github.pfichtner.testcontainers.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinReportMode.DIGITAL;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleBinaryOperator;
import java.util.regex.Pattern;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrContainer;

/**
 * Integration test for the container's WATCH mode: changing the sketch on the
 * host must recompile the sketch and hot-reload the firmware inside the running
 * simulator. The reload happens in place, so the WebSocket connection and its
 * subscriptions survive it - this verifies both the reload and the connection
 * continuity.
 */
class WatchReloadIT {

	private static final String PIN = "8";
	private static final String PIN_CHANGED = "9";

	private static final Pattern PIN_DEFINE = Pattern.compile("#define PIN \\d+");

	// The sketch drives a single pin. The pin is a #define so the test can change
	// just the define (and with it the running firmware) instead of rewriting the
	// whole sketch. Initially it drives pin 8, the reload switches it to pin 9.
	private static final String SKETCH = Stream.of( //
			"#define PIN " + PIN, //
			"void setup() { pinMode(PIN, OUTPUT); }", //
			"void loop() { digitalWrite(PIN, HIGH); delay(200); digitalWrite(PIN, LOW); delay(200); }" //
	).collect(joining("\n"));

	@Test
	void sketchChangeIsRecompiledAndReloaded(@TempDir File tmpDir) throws Exception {
		File sketchFile = new File(tmpDir, "sketch.ino");
		Files.write(sketchFile.toPath(), SKETCH.getBytes(UTF_8));
		long initialWriteNanos = System.nanoTime();

		VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer(sketchFile).withWatchInterval(1);
		virtualAvrContainer.start();
		try {
			VirtualAvrConnection avr = virtualAvrContainer.avr();

			// Subscribe to both pins up front: pin 8 proves the initial firmware
			// runs, pin 9 proves the reloaded firmware runs.
			avr.pinReportMode(PIN, DIGITAL).pinReportMode(PIN_CHANGED, DIGITAL);
			AtomicBoolean reloaded = new AtomicBoolean();
			avr.addFirmwareReloadListener(__r -> reloaded.set(true));
			awaitPinGetsHigh(avr, PIN);

			waitRemainingMillis(initialWriteNanos);
			// cpuTime is derived from the CPU cycle counter; a reload creates a fresh
			// CPU, so the reloaded firmware's cpuTime must restart instead of
			// continuing the previous firmware's one.
			double lastCpuTimeOfOldFirmware = cpuTimes(avr, PIN, (__, last) -> last);
			// Drop the states collected so far (including the state each subscription
			// reported immediately) so the first pin 9 state below is produced by the
			// reloaded firmware.
			avr.pinStates().clear();
			changePinDefine(sketchFile, PIN_CHANGED);

			awaitPinGetsHigh(avr, PIN_CHANGED);
			await().atMost(60, SECONDS).untilTrue(reloaded);
			assertThat(avr.isConnected()).isTrue();
			assertThat(cpuTimes(avr, PIN_CHANGED, (first, __) -> first)).isLessThan(lastCpuTimeOfOldFirmware);
		} finally {
			virtualAvrContainer.stop();
		}
	}

	private void waitRemainingMillis(long initialWriteNanos) throws InterruptedException {
		// Ensure the modification time changes before switching the pin; the
		// container startup and the wait above already consumed part of the
		// required 1100ms, so only sleep what is left.
		long remainingMillis = 1100 - NANOSECONDS.toMillis(System.nanoTime() - initialWriteNanos);
		if (remainingMillis > 0) {
			Thread.sleep(remainingMillis);
		}
	}

	private static void changePinDefine(File sketchFile, String pin) throws IOException {
		String content = new String(Files.readAllBytes(sketchFile.toPath()), UTF_8);
		String updated = PIN_DEFINE.matcher(content).replaceFirst("#define PIN " + pin);
		Files.write(sketchFile.toPath(), updated.getBytes(UTF_8));
	}

	private void awaitPinGetsHigh(VirtualAvrConnection avr, String pin) {
		await().atMost(60, SECONDS).until(() -> Boolean.TRUE.equals(avr.pinStates().last(pin)));
	}

	private static double cpuTimes(VirtualAvrConnection avr, String pin, DoubleBinaryOperator op) {
		return cpuTimes(avr, pin) //
				.reduce(op) //
				.orElseThrow(() -> new IllegalStateException("No pin state reported for pin " + pin));
	}

	private static DoubleStream cpuTimes(VirtualAvrConnection avr, String pin) {
		return avr.pinStates().stream() //
				.filter(p -> p.getPin().equals(pin)) //
				.mapToDouble(p -> p.getCpuTime());
	}

}
