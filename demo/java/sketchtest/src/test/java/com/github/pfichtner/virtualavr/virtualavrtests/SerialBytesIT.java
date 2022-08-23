package com.github.pfichtner.virtualavr.virtualavrtests;

import static com.github.pfichtner.virtualavr.SerialConnectionAwait.awaiter;
import static com.github.pfichtner.virtualavr.TestcontainerSupport.virtualAvrContainer;
import static com.github.pfichtner.virtualavr.TestcontainerSupport.withSketchFromClasspath;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.pfichtner.virtualavr.SerialConnectionAwait;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;

@Testcontainers
class SerialBytesIT {

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = virtualAvrContainer(withSketchFromClasspath("/byteecho.ino")) //
			.withBaudrate(115200);

	@Test
	void doesReceiveAllPossibleByteValues() throws Exception {
		SerialConnectionAwait awaiter = awaiter(virtualAvrContainer.serialConnection());
		for (int i = 1; i < 255; i++) {
			byte[] arr = new byte[] { (byte) i };
			awaiter.sendAwait(arr, b -> Arrays.equals(b, arr));
		}
		awaiter.sendAwait(new byte[] { (byte) 255 }, b -> Arrays.equals(b, new byte[] { (byte) 255, 0 }));
	}

}
