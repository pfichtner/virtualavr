package com.github.pfichtner.virtualavr;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
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
		return new VirtualAvrContainer<>() //
				.withImagePullPolicy(onlyPullIfEnabled()) //
				.withSketchFile(inoFile) //
				.withDeviceName("virtualavr" + UUID.randomUUID()) //
				.withDeviceGroup("root") //
				.withDeviceMode(666) //
		;
	}

	public static ImagePullPolicy onlyPullIfEnabled() {
		return imageName -> System.getProperty(VIRTUALAVR_DOCKER_PULL_PROPERTY_NAME) != null;
	}

	public static DockerImageName imageName() {
		String dockerTagName = System.getProperty(VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME);
		DockerImageName defaultImageName = VirtualAvrContainer.DEFAULT_IMAGE_NAME;
		return dockerTagName == null ? defaultImageName : defaultImageName.withTag(dockerTagName);
	}

	public static File withSketchFromClasspath(String name) {
		try {
			return new File(TestcontainerSupport.class.getResource(name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	public static File downloadTo(URL source, File target)
			throws IOException, MalformedURLException, FileNotFoundException {
		try (BufferedInputStream in = new BufferedInputStream(source.openStream());
				FileOutputStream out = new FileOutputStream(target)) {
			out.write(in.readAllBytes());
		}
		return target;
	}

}
