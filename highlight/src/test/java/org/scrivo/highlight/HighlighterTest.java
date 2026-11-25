package org.scrivo.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scrivo.highlight.languages.Registry;

class HighlighterTest {

	@BeforeEach
	void setUp() {
		Highlighter.clearAllLanguages();
	}

	@AfterAll
	static void tearDown() {
		Highlighter.clearAllLanguages();
		Highlighter.registerAllLanguages();
	}

	@Test
	void unknownLanguageThrowsException() {
		Highlighter hl = new Highlighter();

		assertThrows(IllegalArgumentException.class, () -> hl.highlight("blah++", "als blurp eq z dan zeg 'flipper'"));
	}

	@Test
	void listLanguagesWithoutAliases() {
		new Highlighter();

		assertEquals(Registry.LANGUAGES.size(), Highlighter.listRegisteredLanguages().size());
	}

	@Test
	void listLanguagesWithAliases() {
		new Highlighter();

		List<String> languages = Highlighter.listRegisteredLanguages(true);
		assertTrue(languages.size() > Registry.LANGUAGES.size());

		// Verify some common aliases/names are present.
		assertTrue(languages.contains("yaml"));
		assertTrue(languages.contains("yml"));
		assertTrue(languages.contains("c++"));
		assertTrue(languages.contains("cpp"));
	}

	private static List<String> expectedAliases(String id) {
		List<String> aliases = new ArrayList<>(new Language(id, Registry.LANGUAGES.get(id).get(), null).getAliases());
		aliases.add(id);
		Collections.sort(aliases);
		return aliases;
	}

	private static List<String> sorted(List<String> list) {
		List<String> result = new ArrayList<>(list);
		Collections.sort(result);
		return result;
	}

	@Test
	void getAliasesForLanguageWhenUsingMainLanguageName() {
		Highlighter hl = new Highlighter();

		assertEquals(expectedAliases("php"), sorted(hl.getAliasesForLanguage("php")));
	}

	@Test
	void getAliasesForLanguageWhenLanguageHasNoAliases() {
		Highlighter hl = new Highlighter();

		assertEquals(List.of("ada"), hl.getAliasesForLanguage("ada"));
	}

	@Test
	void getAliasesForLanguageWhenUsingLanguageAlias() {
		Highlighter hl = new Highlighter();

		assertEquals(expectedAliases("php"), sorted(hl.getAliasesForLanguage("php3")));
	}

	@Test
	void getAliasesForLanguageRaisesExceptionForNonExistingLanguage() {
		Highlighter hl = new Highlighter();

		assertThrows(IllegalArgumentException.class, () -> hl.getAliasesForLanguage("blah+"));
	}

	/**
	 * When a match is ignored and the rules after it don't match anymore,
	 * highlighting continues after the ignored match (highlight.js 10.2.0, #2649).
	 */
	@Test
	void resumeScanWhenAMatchIsIgnored() {
		Highlighter hl = new Highlighter();

		assertEquals("ImmutablePair.of(Stuff.class, <span class=\"hljs-string\">&quot;bar&quot;</span>)",
				hl.highlight("java", "ImmutablePair.of(Stuff.class, \"bar\")").getValue());
	}

	/** Names of JavaScript object properties are no languages (highlight.js 10.1.2, #2636). */
	@Test
	void getLanguageDoesNotReturnObjectProperties() {
		new Highlighter();

		assertNull(Highlighter.getLanguage("constructor"));
		assertNull(Highlighter.getLanguage("__proto__"));
		assertNull(Highlighter.getLanguage("hasOwnProperty"));
	}

	@Test
	void registerAliases() {
		Highlighter hl = new Highlighter();
		Highlighter.registerAliases(List.of("my-js", "MyScript"), "javascript");
		Highlighter.registerAliases("my-py", "python");

		assertEquals("javascript", hl.highlight("my-js", "var a").getLanguage());
		assertEquals("javascript", hl.highlight("myscript", "var a").getLanguage());
		assertEquals("python", hl.highlight("my-py", "def a").getLanguage());
	}

	@Test
	void loadAllLanguagesByDefault() {
		new Highlighter();

		assertEquals(Registry.LANGUAGES.size(), Highlighter.listBundledLanguages().size());
		assertEquals(Registry.LANGUAGES.size(), Highlighter.listRegisteredLanguages().size());
	}

	@Test
	void loadNoLanguagesInConstructor() {
		new Highlighter(false);

		assertEquals(0, Highlighter.listRegisteredLanguages().size());
	}

	@Test
	void loadOneLanguageManually() {
		Highlighter.registerLanguage("1c", Registry.LANGUAGES.get("1c").get());

		new Highlighter(false);

		assertEquals(List.of("1c"), Highlighter.listRegisteredLanguages());
	}

	@Test
	void languageNamesAreCaseInsensitive() {
		Highlighter hl = new Highlighter();

		assertEquals("javascript", hl.highlight("JavaScript", "var a;").getLanguage());
	}

	@Test
	void resultLanguageIsTheLanguageIdForAnAlias() {
		Highlighter hl = new Highlighter();

		assertEquals("javascript", hl.highlight("js", "var a;").getLanguage());
	}

	@Test
	void classPrefix() {
		Highlighter hl = new Highlighter();
		hl.setClassPrefix("x-");

		assertEquals("<span class=\"x-keyword\">var</span> a;", hl.highlight("javascript", "var a;").getValue());
	}

	@Test
	void htmlIsEscaped() {
		Highlighter hl = new Highlighter();

		// like highlight.js (since 10.1.0), quotes are escaped as well
		assertEquals("a &lt; b &amp;&amp; c &gt; &quot; &#x27;", hl.highlight("plaintext", "a < b && c > \" '").getValue());
	}

	@Test
	void illegalLexemes() {
		Highlighter hl = new Highlighter();

		// a newline is illegal in a JSON string
		HighlightResult result = hl.highlight("json", "{\"a\nb\": 1}", false);
		assertTrue(result.isIllegal());
		assertEquals(0, result.getRelevance());
		assertEquals("{&quot;a\nb&quot;: 1}", result.getValue());

		assertFalse(hl.highlight("json", "{\"a\nb\": 1}", true).isIllegal());
	}

	@Test
	void autoDetectionWithLanguageSubset() {
		Highlighter hl = new Highlighter();

		HighlightResult result = hl.highlightAuto("<?php echo 'hello'; ?>", List.of("php", "ruby"));
		assertEquals("php", result.getLanguage());
		assertNotNull(result.getSecondBest());
	}

	@Test
	void autoDetectionWithoutMatch() {
		Highlighter hl = new Highlighter();

		HighlightResult result = hl.highlightAuto("", List.of("php", "ruby"));
		assertEquals("", result.getLanguage());
		assertEquals("", result.getValue());
		assertNull(result.getSecondBest());
	}

	@Test
	void highlighterCanBeSharedBetweenThreads() throws Exception {
		Highlighter hl = new Highlighter();
		String code = TestData.read("special/sublanguages.txt");
		String expected = TestData.read("special/sublanguages.expect.txt");

		ExecutorService executor = Executors.newFixedThreadPool(8);
		try {
			List<Future<String>> results = new ArrayList<>();
			for (int i = 0; i < 64; i++) {
				results.add(executor.submit(() -> hl.highlight("xml", code).getValue()));
			}
			for (Future<String> result : results) {
				assertEquals(expected, result.get());
			}
		} finally {
			executor.shutdown();
		}
	}
}
