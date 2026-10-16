package com.github.pfichtner.testcontainers.virtualavr.tests;

import static com.github.pfichtner.testcontainers.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinReportMode.DIGITAL;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection;
import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrContainer;

/**
 * Integration test for the container's WATCH mode: changing the sketch on the
 * host must recompile and restart the simulation. The simulator process (and
 * with it the WebSocket connection) is killed on reload, so this also verifies
 * that the {@link VirtualAvrConnection} transparently reconnects and restores
 * its subscriptions.
 */
class WatchReloadIT {

	// The sketch drives a single pin. The pin is a #define so the test can change
	// just the define (and with it the running firmware) instead of rewriting the
	// whole sketch. Initially it drives pin 8, the reload switches it to pin 9.
	private static final String SKETCH = "#define PIN 8\n" //
			+ "void setup() { pinMode(PIN, OUTPUT); }\n" //
			+ "void loop() { digitalWrite(PIN, HIGH); delay(200); digitalWrite(PIN, LOW); delay(200); }\n";

	private static final Pattern PIN_DEFINE = Pattern.compile("#define PIN \\d+");

	private static void setPin(File sketchFile, int pin) throws IOException {
		String content = new String(Files.readAllBytes(sketchFile.toPath()), UTF_8);
		String updated = PIN_DEFINE.matcher(content).replaceFirst("#define PIN " + pin);
		Files.write(sketchFile.toPath(), updated.getBytes(UTF_8));
	}

	@Test
	void sketchChangeIsRecompiledAndReloaded(@TempDir File tmpDir) throws Exception {
		File sketchFile = new File(tmpDir, "sketch.ino");
		Files.write(sketchFile.toPath(), SKETCH.getBytes(UTF_8));

		VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer(sketchFile).withWatch(true, 1);
		virtualAvrContainer.start();
		try {
			VirtualAvrConnection avr = virtualAvrContainer.avr();

			// Subscribe to both pins up front: pin 8 proves the initial firmware
			// runs, pin 9 proves the reloaded firmware runs.
			avr.pinReportMode("8", DIGITAL).pinReportMode("9", DIGITAL);
			await().atMost(60, SECONDS).until(() -> Boolean.TRUE.equals(avr.pinStates().last("8")));

			// Ensure the modification time changes before switching the pin.
			Thread.sleep(1100);
			setPin(sketchFile, 9);

			// The connection restores its subscriptions on reconnect (see
			// DefaultVirtualAvrConnection#onOpen), so no re-subscription is needed
			// here. The old simulator never drives pin 9, so this can only pass
			// once the reloaded firmware runs.
			await().atMost(120, SECONDS).pollInterval(1, SECONDS).ignoreExceptions()
					.untilAsserted(() -> assertThat(avr.pinStates().last("9")).isEqualTo(true));
			assertThat(avr.isConnected()).isTrue();
		} finally {
			virtualAvrContainer.stop();
		}
	}

}
