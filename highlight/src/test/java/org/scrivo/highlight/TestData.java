package org.scrivo.highlight;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Access to the test data in {@code src/test/resources/org/scrivo/highlight}.
 */
final class TestData {

	private TestData() {
	}

	static Path dir(String name) {
		URL url = TestData.class.getResource(name);
		if (url == null) {
			throw new IllegalStateException("Missing test data: " + name);
		}
		try {
			return Path.of(url.toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	static String read(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static String read(String name) {
		return read(dir(name));
	}
}
