package com.github.pfichtner.virtualavr.demo;

import static com.github.pfichtner.virtualavr.SerialConnectionAwait.awaiter;
import static java.lang.String.format;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.VirtualAvrContainer;

@Testcontainers
class VirtualAvrDefinesTest {

	private static final String SUCCESS_OVERWRITING_DEFINE_REPLACEMENT = "success overwriting define";

	@Container
	VirtualAvrContainer<?> virtualavr = new VirtualAvrContainer<>()
			.withBuildExtraFlags(format("-DTRY_ME_TO_OVERWRITE=\"%s\"", SUCCESS_OVERWRITING_DEFINE_REPLACEMENT)) //
			.withSketchFile(new File("../../../test-artifacts/definetest/definetest.ino")) //
			.withBaudrate(115200);

	@Test
	void canOverwriteDefine() throws Exception {
		awaiter(virtualavr.serialConnection()).awaitReceived(s -> s.contains(SUCCESS_OVERWRITING_DEFINE_REPLACEMENT));
	}

}
