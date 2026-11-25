package org.scrivo.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The markup tests of highlight.js: each {@code markup/<language>/<name>.txt}
 * must highlight to {@code <name>.expect.txt}.
 */
class MarkupTest {

	/** [language, test name] pairs of tests that are known to fail. */
	private static final List<List<String>> ALLOWED_FAILURES = List.of();

	@TestFactory
	Stream<DynamicTest> markup() throws IOException {
		Path root = TestData.dir("markup");
		List<Path> tests;
		try (Stream<Path> files = Files.walk(root)) {
			// like highlight.js, the tests are the files with the expected markup
			tests = files.filter(p -> p.toString().endsWith(".expect.txt")).sorted().toList();
		}
		return tests.stream().map(expected -> {
			String language = expected.getParent().getFileName().toString();
			String name = expected.getFileName().toString().replaceFirst("\\.expect\\.txt$", "");
			Path test = expected.resolveSibling(name + ".txt");
			return DynamicTest.dynamicTest(language + "/" + name, () -> {
				if (ALLOWED_FAILURES.contains(List.of(language, name))) {
					return;
				}
				HighlightResult actual = new Highlighter().highlight(language, TestData.read(test));
				assertEquals(language, actual.getLanguage());
				assertEquals(TestData.read(expected).strip(), actual.getValue().strip(),
						"The \"" + name + "\" markup test failed for the \"" + language + "\" language");
			});
		});
	}
}
