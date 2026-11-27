package org.scrivo.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The auto-detection tests of highlight.js: each file in
 * {@code detect/<language>/} must be detected as that language.
 */
class DetectionTest {

	@TestFactory
	Stream<DynamicTest> automaticDetection() throws IOException {
		Path root = TestData.dir("detect");
		List<Path> tests;
		try (Stream<Path> files = Files.walk(root)) {
			tests = files.filter(Files::isRegularFile).sorted().toList();
		}

		Highlighter hl = new Highlighter();
		hl.setAutodetectLanguages(Highlighter.listBundledLanguages());

		return tests.stream().map(test -> {
			String language = root.relativize(test).getName(0).toString();
			return DynamicTest.dynamicTest(root.relativize(test).toString(), () -> {
				HighlightResult actual = hl.highlightAuto(TestData.read(test));
				HighlightResult secondBest = actual.getSecondBest();
				String message = String.format("Expected language: %s; [1. %s (%d%%); 2. %s (%d%%)]", language,
						actual.getLanguage(), actual.getRelevance(),
						secondBest == null ? "" : secondBest.getLanguage(),
						secondBest == null ? 0 : secondBest.getRelevance());

				// like highlight.js, only the languages that take part in auto-detection
				assumeFalse(Highlighter.getLanguage(language).isDisableAutodetect(),
						"The '" + language + "' language is excluded from auto-detection: " + message);
				assertEquals(language, actual.getLanguage(), message);
			});
		});
	}
}
