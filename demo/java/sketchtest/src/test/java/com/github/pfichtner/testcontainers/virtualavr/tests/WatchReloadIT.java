package com.github.pfichtner.testcontainers.virtualavr.tests;

import static com.github.pfichtner.testcontainers.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static com.github.pfichtner.testcontainers.virtualavr.VirtualAvrConnection.PinReportMode.DIGITAL;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.nio.file.Files;

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

	// v1 drives pin 8, v2 drives pin 9. The driven pin proves which firmware runs.
	private static final String SKETCH_V1 = "void setup() { pinMode(8, OUTPUT); }" //
			+ "void loop() { digitalWrite(8, HIGH); delay(200); digitalWrite(8, LOW); delay(200); }";

	private static final String SKETCH_V2 = "void setup() { pinMode(9, OUTPUT); }" //
			+ "void loop() { digitalWrite(9, HIGH); delay(200); digitalWrite(9, LOW); delay(200); }";

	@Test
	void sketchChangeIsRecompiledAndReloaded(@TempDir File tmpDir) throws Exception {
		File sketchFile = new File(tmpDir, "sketch.ino");
		Files.write(sketchFile.toPath(), SKETCH_V1.getBytes(UTF_8));

		VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer(sketchFile).withWatch(true, 1);
		virtualAvrContainer.start();
		try {
			VirtualAvrConnection avr = virtualAvrContainer.avr();

			// Initial firmware (v1) drives pin 8.
			avr.pinReportMode("8", DIGITAL);
			await().atMost(60, SECONDS).until(() -> Boolean.TRUE.equals(avr.pinStates().last("8")));

			// Ensure the modification time changes before rewriting the sketch.
			Thread.sleep(1100);
			Files.write(sketchFile.toPath(), SKETCH_V2.getBytes(UTF_8));

			// The reloaded firmware (v2) drives pin 9. Subscribe again on each
			// attempt: the old simulator does not drive pin 9 and during the
			// recompile/reconnect gap the connection may reject the command.
			await().atMost(120, SECONDS).pollInterval(1, SECONDS).ignoreExceptions().untilAsserted(() -> {
				avr.pinReportMode("9", DIGITAL);
				assertThat(avr.pinStates().last("9")).isEqualTo(true);
			});
			assertThat(avr.isConnected()).isTrue();
		} finally {
			virtualAvrContainer.stop();
		}
	}

}
