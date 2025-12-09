package com.github.pfichtner.testcontainers.virtualavr.tests;

import static com.github.pfichtner.testcontainers.virtualavr.IOUtil.withSketchFromClasspath;
import static com.github.pfichtner.testcontainers.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrContainer;

@Testcontainers
@EnabledIf("com.github.pfichtner.testcontainers.virtualavr.TcpSerialModeSupport#isSocatAvailable")
class TcpSerialModeSupportWithDebugIT {

	private static final String SOME_NOT_USED_TTY_NAME = "someUnusedTTYName";

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer( //
			withSketchFromClasspath("/byteecho/byteecho.ino")) //
			.withDeviceName(SOME_NOT_USED_TTY_NAME) //
			// force tcp serial mode for test even on linux hosts
			.withTcpSerialMode() //
			// on linux, host.docker.internal is not available by default
			.withExtraHost("host.docker.internal", "host-gateway") //
			.withDebug() //
	;

	@Test
	void containsBothSocatLogs() throws IOException {
//		assertSoftly(s -> {
//			for (String fragment : List.of( //
//					"Container environment variables:", //
//					"\tFILENAME=byteecho.ino", //
//					"\tDEBUG=true", //
//					"N opening connection to host.docker.internal:", //
//					"N successfully connected to host.docker.internal:", //
//					"N starting data transfer loop with FDs [5,5] and [6,6]")) {
//				s.assertThat(events.all()).as("Expected fragment: %s", fragment) //
//						.map(ILoggingEvent::getMessage) //
//						.anyMatch(m -> m.contains(fragment));
//			}
//		});

		String[] logLines = virtualAvrContainer.getLogs().split("\\R");
		assertSoftly(s -> {
			for (String fragment : List.of( //
					"Using TCP serial mode: connecting to host.docker.internal:", //
					"N opening connection to host.docker.internal:", //
					"N successfully connected to host.docker.internal:", //
					"N forking off child, using pty for reading and writing", //
					"N starting data transfer loop with FDs [5,5] and [6,6]")) {
				s.assertThat(logLines).as("Expected fragment: %s", fragment).anyMatch(l -> l.contains(fragment));
			}
		});

//		assertThat(logLines) //
//				.filteredOn(l -> l.contains("TCP Serial Mode: Socat output will be logged to ")) //
//				.singleElement() //
//				.satisfies(l -> {
//					String[] parts = l.split(" ");
//					String logFilePath = parts[parts.length - 1];
//					String socatLogs = readString(Path.of(logFilePath));
//					assertThat(socatLogs.split("\\R")).anyMatch(it -> it.contains("N PTY is /dev/pts/"));
//				});
	}

}
