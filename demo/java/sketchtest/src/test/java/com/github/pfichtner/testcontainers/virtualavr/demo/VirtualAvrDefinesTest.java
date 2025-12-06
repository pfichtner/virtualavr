package com.github.pfichtner.testcontainers.virtualavr.demo;

import static com.github.pfichtner.testcontainers.virtualavr.SerialConnectionAwait.awaiter;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.testcontainers.virtualavr.VirtualAvrContainer;

@Testcontainers
class VirtualAvrDefinesTest {

	private static final String SUCCESS_OVERWRITING_DEFINE_REPLACEMENT = "success overwriting define";

	private static final Map<String, Object> buildExtraFlags = Map.of(
			"SLEEP_MILLIS_NOT_DEFINED_IN_SKETCH_SO_THEY_HAVE_TO_GET_PASSED_TO_MAKE_SKETCH_COMPILEABLE_AT_ALL", 1000, //
			"TRY_ME_TO_OVERWRITE", SUCCESS_OVERWRITING_DEFINE_REPLACEMENT //
	);

	@Container
	VirtualAvrContainer<?> virtualavr = new VirtualAvrContainer<>().withBuildExtraFlags(buildExtraFlags) //
			.withSketchFile(new File("../../../test-artifacts/definetest/definetest.ino")) //
			.withBaudrate(115200);

	@Test
	void canSetAndOverwriteDefine() throws IOException {
		awaiter(virtualavr.serialConnection()).awaitReceived(s -> s.contains(SUCCESS_OVERWRITING_DEFINE_REPLACEMENT));
	}

}
