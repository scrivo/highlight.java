package org.scrivo.highlight;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HighlightUtilitiesTest {

	private final Highlighter hl = new Highlighter();

	@Test
	void getAvailableStyleSheets() {
		List<String> results = HighlightUtilities.getAvailableStyleSheets();

		assertFalse(results.isEmpty());
		assertTrue(results.contains("default"));
		for (String result : results) {
			assertFalse(result.contains("/"));
			assertFalse(result.contains(".css"));
			HighlightUtilities.getStyleSheetUrl(result);
		}
	}

	@Test
	void getStyleSheetExists() {
		String yesExt = HighlightUtilities.getStyleSheet("a11y-dark.css");
		String noExt = HighlightUtilities.getStyleSheet("a11y-dark");

		assertFalse(yesExt.isEmpty());
		assertEquals(yesExt, noExt);
	}

	@Test
	void getStyleSheetNotExists() {
		assertThrows(IllegalArgumentException.class, () -> HighlightUtilities.getStyleSheet("strawberry.png"));
	}

	@Test
	void splitCodeIntoArrayMultilineComment() {
		String raw = """
				/**
				 * Hello World
				 *
				 * @api
				 * @since 1.0.0
				 * @param string $str Some string parameter
				 */""";
		String value = hl.highlight("php", raw).getValue();

		List<String> cleanSplit = HighlightUtilities.splitCodeIntoArray(value);
		List<String> dumbSplit = List.of(value.split("\\R"));

		assertEquals(1, value.split("hljs-comment", -1).length - 1);
		assertEquals(cleanSplit.size(), String.join("\n", cleanSplit).split("hljs-comment", -1).length - 1);
		assertEquals(dumbSplit.size(), cleanSplit.size());
		assertNotEquals(dumbSplit, cleanSplit);

		for (String line : cleanSplit) {
			assertTrue(line.strip().startsWith("<span class=\"hljs-comment\">"));
			assertTrue(line.strip().endsWith("</span>"));
		}
	}

	@Test
	void splitCodeIntoArrayEmojis() {
		String raw = """
				// ✅ ...
				$user = new \\stdClass();
				$isUserPending = $user->isStatus('pending');""";
		List<String> split = HighlightUtilities.splitCodeIntoArray(hl.highlight("php", raw).getValue());

		assertEquals(List.of(
				"<span class=\"hljs-comment\">// ✅ ...</span>",
				"<span class=\"hljs-variable\">$user</span> = <span class=\"hljs-keyword\">new</span> \\<span class=\"hljs-built_in\">stdClass</span>();",
				"<span class=\"hljs-variable\">$isUserPending</span> = <span class=\"hljs-variable\">$user</span>-&gt;isStatus(<span class=\"hljs-string\">&#x27;pending&#x27;</span>);"),
				split);
	}

	@ParameterizedTest
	@ValueSource(strings = { "public QuoteEntity(\n)", "public QuoteEntity(\r\n)" })
	void splitCodeIntoArrayDeeplyNestedSpans(String raw) {
		List<String> split = HighlightUtilities.splitCodeIntoArray(hl.highlight("java", raw).getValue());

		assertEquals(List.of(
				"<span class=\"hljs-function\"><span class=\"hljs-keyword\">public</span> <span class=\"hljs-title\">QuoteEntity</span><span class=\"hljs-params\">(</span></span>",
				"<span class=\"hljs-function\"><span class=\"hljs-params\">)</span></span>"),
				split);
	}

	@Test
	void splitCodeIntoArrayXmlWithAttributesOnNewLines() {
		String raw = """
				<?xml version="1.0" encoding="utf-8" ?>
				               <tag a="t"
				                        b="t">
				                 </tag>""";
		List<String> split = HighlightUtilities.splitCodeIntoArray(hl.highlight("xml", raw).getValue());

		assertEquals(List.of(
				"<span class=\"hljs-meta\">&lt;?xml version=&quot;1.0&quot; encoding=&quot;utf-8&quot; ?&gt;</span>",
				"               <span class=\"hljs-tag\">&lt;<span class=\"hljs-name\">tag</span> <span class=\"hljs-attr\">a</span>=<span class=\"hljs-string\">&quot;t&quot;</span></span>",
				"<span class=\"hljs-tag\">                        <span class=\"hljs-attr\">b</span>=<span class=\"hljs-string\">&quot;t&quot;</span>&gt;</span>",
				"                 <span class=\"hljs-tag\">&lt;/<span class=\"hljs-name\">tag</span>&gt;</span>"),
				split);
	}

	@Test
	void splitCodeIntoArrayXmlWithAttributesSpanningMultipleLines() {
		String raw = """
				<?xml version="1.0" encoding="utf-8" ?>
				<nlog xmlns="http://www.nlog-project.org/schemas/NLog.xsd"
				      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
				      xsi:schemaLocation="http://www.nlog-project.org/schemas/NLog.xsd NLog.xsd"
				      autoReload="true">
				</nlog>""";
		List<String> split = HighlightUtilities.splitCodeIntoArray(hl.highlight("xml", raw).getValue());

		assertEquals(List.of(
				"<span class=\"hljs-meta\">&lt;?xml version=&quot;1.0&quot; encoding=&quot;utf-8&quot; ?&gt;</span>",
				"<span class=\"hljs-tag\">&lt;<span class=\"hljs-name\">nlog</span> <span class=\"hljs-attr\">xmlns</span>=<span class=\"hljs-string\">&quot;http://www.nlog-project.org/schemas/NLog.xsd&quot;</span></span>",
				"<span class=\"hljs-tag\">      <span class=\"hljs-attr\">xmlns:xsi</span>=<span class=\"hljs-string\">&quot;http://www.w3.org/2001/XMLSchema-instance&quot;</span></span>",
				"<span class=\"hljs-tag\">      <span class=\"hljs-attr\">xsi:schemaLocation</span>=<span class=\"hljs-string\">&quot;http://www.nlog-project.org/schemas/NLog.xsd NLog.xsd&quot;</span></span>",
				"<span class=\"hljs-tag\">      <span class=\"hljs-attr\">autoReload</span>=<span class=\"hljs-string\">&quot;true&quot;</span>&gt;</span>",
				"<span class=\"hljs-tag\">&lt;/<span class=\"hljs-name\">nlog</span>&gt;</span>"),
				split);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "\t", "  " })
	void splitCodeIntoArrayEmptyString(String string) {
		assertEquals(List.of(), HighlightUtilities.splitCodeIntoArray(string));
	}

	@Test
	void getThemeBackgroundColorSingleColor() {
		assertArrayEquals(new int[] { 40, 44, 52 }, HighlightUtilities.getThemeBackgroundColor("atom-one-dark"));
	}

	@Test
	void getThemeBackgroundColorColorWithBgImage() {
		assertArrayEquals(new int[] { 183, 166, 142 }, HighlightUtilities.getThemeBackgroundColor("brown-paper"));
	}

	@Test
	void getThemeBackgroundColorNamedColor() {
		assertArrayEquals(new int[] { 0, 0, 0 }, HighlightUtilities.getThemeBackgroundColor("tomorrow-night-bright.css"));
	}

	@Test
	void getLanguageDefinition() {
		assertEquals("org.scrivo.highlight.languages.OneC",
				HighlightUtilities.getLanguageDefinition("1c").getClass().getName());
		assertThrows(IllegalArgumentException.class, () -> HighlightUtilities.getLanguageDefinition("blah"));
	}
}
