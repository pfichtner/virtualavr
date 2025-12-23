package com.github.pfichtner.testcontainers.virtualavr;

import static java.lang.String.format;

import java.io.File;
import java.util.UUID;

import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.utility.DockerImageName;

public final class TestcontainerSupport {

	private static final String VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME = "virtualavr.docker.tag";
	private static final String VIRTUALAVR_DOCKER_PULL_PROPERTY_NAME = "virtualavr.docker.pull";

	private TestcontainerSupport() {
		super();
	}

	@SuppressWarnings("resource")
	public static VirtualAvrContainer<?> virtualAvrContainer(File inoFile) {
		VirtualAvrContainer<?> container = new VirtualAvrContainer<>(imageName()) //
				.withImagePullPolicy(onlyPullIfEnabled()) //
				.withSketchFile(inoFile) //
				.withDeviceName(format("virtualavr%s", UUID.randomUUID())) //
				.withDeviceGroup("root") //
				.withDeviceMode(666) //
		;

		// Use TCP serial mode on non-Linux systems (macOS/Windows with Docker Desktop)
		return isLinux() ? container : container.withTcpSerialMode();
	}

	public static ImagePullPolicy onlyPullIfEnabled() {
		return imageName -> System.getProperty(VIRTUALAVR_DOCKER_PULL_PROPERTY_NAME) != null;
	}

	public static DockerImageName imageName() {
		String dockerTagName = System.getProperty(VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME);
		DockerImageName defaultImageName = VirtualAvrContainer.DEFAULT_IMAGE_NAME;
		return dockerTagName == null ? defaultImageName : defaultImageName.withTag(dockerTagName);
	}

	/**
	 * Checks if the current OS is Linux. On Linux, the standard PTY mode works
	 * because /dev can be bind-mounted. On macOS/Windows with Docker Desktop, TCP
	 * serial mode is needed.
	 *
	 * @return <code>true</code> if running on Linux
	 */
	private static boolean isLinux() {
		return System.getProperty("os.name", "").toLowerCase().contains("linux");
	}

}
