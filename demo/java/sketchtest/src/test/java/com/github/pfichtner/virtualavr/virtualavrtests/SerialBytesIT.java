package com.github.pfichtner.virtualavr.virtualavrtests;

import static com.github.pfichtner.virtualavr.SerialConnectionAwait.awaiter;

import java.io.File;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.github.pfichtner.virtualavr.SerialConnectionAwait;
import com.github.pfichtner.virtualavr.VirtualAvrContainer;

@Testcontainers
class SerialBytesIT {

	private static final String VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME = "virtualavr.docker.tag";

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = new VirtualAvrContainer<>(imageName()) //
			.withSketchFile(loadClasspath("/byteecho.ino")) //
			.withDeviceName("virtualavr" + UUID.randomUUID()) //
			.withBaudrate(115200) //
			.withDeviceGroup("root") //
			.withDeviceMode(666) //
	;

	/**
	 * If you want the version from dockerhub, you have to use <code>latest</code>
	 * as value for {@value #VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME}. <br>
	 * To prevent that we test accidentally the image pulled from dockerhub when
	 * running our integration tests there is <b>NO</b> default value!
	 * 
	 * @return the image name including tag
	 */
	static DockerImageName imageName() {
		String dockerTagName = System.getProperty(VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME);
		if (dockerTagName == null) {
			throw new IllegalStateException("\"" + VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME + "\" property not set!");
		}
		return VirtualAvrContainer.DEFAULT_IMAGE_NAME.withTag(dockerTagName);
	}

	static File loadClasspath(String name) {
		try {
			return new File(SerialBytesIT.class.getResource(name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

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
