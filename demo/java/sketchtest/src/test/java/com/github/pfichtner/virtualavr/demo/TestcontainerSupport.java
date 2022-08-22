package com.github.pfichtner.virtualavr.demo;

import java.io.File;
import java.net.URISyntaxException;

import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.utility.DockerImageName;

import com.github.pfichtner.virtualavr.VirtualAvrContainer;

public final class TestcontainerSupport {

	private static final String VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME = "virtualavr.docker.tag";
	private static final String VIRTUALAVR_DOCKER_PULL_PROPERTY_NAME = "virtualavr.docker.pull";

	private TestcontainerSupport() {
		super();
	}

	public static ImagePullPolicy onlyPullIfEnabled() {
		return imageName -> System.getProperty(VIRTUALAVR_DOCKER_PULL_PROPERTY_NAME) != null;
	}

	public static DockerImageName imageName() {
		String dockerTagName = System.getProperty(VIRTUALAVR_DOCKER_TAG_PROPERTY_NAME);
		DockerImageName defaultImageName = VirtualAvrContainer.DEFAULT_IMAGE_NAME;
		return dockerTagName == null ? defaultImageName : defaultImageName.withTag(dockerTagName);
	}

	public static File loadClasspath(String name) {
		try {
			return new File(TestcontainerSupport.class.getResource(name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

}
