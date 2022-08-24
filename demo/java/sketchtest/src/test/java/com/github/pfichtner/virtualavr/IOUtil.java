package com.github.pfichtner.virtualavr;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;

public final class IOUtil {
	
	private IOUtil() {
		super();
	}

	public static File downloadTo(URL source, File target)
			throws IOException, MalformedURLException, FileNotFoundException {
		try (BufferedInputStream in = new BufferedInputStream(source.openStream());
				FileOutputStream out = new FileOutputStream(target)) {
			out.write(in.readAllBytes());
		}
		return target;
	}

	public static File withSketchFromClasspath(String name) {
		try {
			return new File(TestcontainerSupport.class.getResource(name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String filename(URL url) {
		return Paths.get(url.getPath()).getFileName().toString();
	}

}
