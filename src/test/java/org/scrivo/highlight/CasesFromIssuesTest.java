package org.scrivo.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Cases from issues reported for highlight.php: {@code <issue>-[<language>].txt}
 * must highlight to {@code <issue>-[<language>].expected.txt}.
 */
class CasesFromIssuesTest {

	private static final Pattern FILE_NAME = Pattern.compile("(\\d+)-\\[(\\w+)]\\.txt");

	@TestFactory
	Stream<DynamicTest> issueSpecificCases() throws IOException {
		List<Path> cases;
		try (Stream<Path> files = Files.list(TestData.dir("issues"))) {
			cases = files.filter(p -> FILE_NAME.matcher(p.getFileName().toString()).matches()).sorted().toList();
		}
		return cases.stream().map(source -> {
			Matcher m = FILE_NAME.matcher(source.getFileName().toString());
			m.matches();
			String issue = m.group(1);
			String language = m.group(2);
			Path expected = source.resolveSibling(issue + "-[" + language + "].expected.txt");
			return DynamicTest.dynamicTest("#" + issue + " " + language, () -> {
				HighlightResult actual = new Highlighter().highlight(language, TestData.read(source).strip());

				assertEquals(TestData.read(expected).strip(), actual.getValue(),
						"Unit test added for Issue #" + issue + " failed");
			});
		});
	}
}
