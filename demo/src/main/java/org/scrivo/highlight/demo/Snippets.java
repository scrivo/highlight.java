package org.scrivo.highlight.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Singleton;

import org.scrivo.highlight.Highlighter;

/**
 * The sample snippet of each language: {@code detect/<language>/default.txt}
 * from the auto-detection tests of highlight.js, which come with the test jar
 * of highlight.java.
 */
@Singleton
public class Snippets {

	private static final String DIR = "org/scrivo/highlight/detect/";

	private final List<String> languages = Highlighter.listBundledLanguages().stream()
			.filter(language -> resource(language) != null)
			.sorted()
			.toList();

	/** The languages with a snippet, sorted by name. */
	public List<String> languages() {
		return languages;
	}

	/** The snippet of a language, empty for unknown languages. */
	public Optional<String> snippet(String language) {
		// only known languages, the name is used in a resource path
		if (!languages.contains(language)) {
			return Optional.empty();
		}
		try (InputStream in = resource(language)) {
			return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static InputStream resource(String language) {
		return Thread.currentThread().getContextClassLoader().getResourceAsStream(DIR + language + "/default.txt");
	}
}
