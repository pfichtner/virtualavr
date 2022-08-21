package com.github.pfichtner.virtualavr.virtualavrtests;

import static com.github.pfichtner.virtualavr.SerialConnectionAwait.awaiter;
import static java.util.Collections.indexOfSubList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.IntStream.range;

import java.io.File;
import java.net.URISyntaxException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.github.pfichtner.virtualavr.VirtualAvrContainer;

@Testcontainers
class SerialBytesIT {

	private static final String VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME = "virtualavr.docker.tag";

	@Container
	VirtualAvrContainer<?> virtualAvrContainer = new VirtualAvrContainer<>(imageName()) //
			.withSketchFile(loadClasspath("/allBytes.ino")) //
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
		List<Byte> allPossibleBytes = range(0, 255).mapToObj(i -> Byte.valueOf((byte) i)).collect(toList());
		awaiter(virtualAvrContainer.serialConnection())
				.awaitReceivedBytes(bytes -> indexOfSubList(toByteList(bytes), allPossibleBytes) >= 0);
	}

	static List<Byte> toByteList(byte[] bytes) {
		return range(0, bytes.length).mapToObj(i -> bytes[i]).collect(toList());
	}

}
