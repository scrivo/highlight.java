package org.scrivo.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.scrivo.highlight.languages.Registry;

class LanguageDefinitionTest {

	@AfterAll
	static void tearDown() {
		Highlighter.clearAllLanguages();
		Highlighter.registerAllLanguages();
	}

	static Stream<String> bundledLanguages() {
		return Registry.LANGUAGES.keySet().stream();
	}

	@ParameterizedTest
	@MethodSource("bundledLanguages")
	void registryAliasesMatchDefinition(String id) throws ReflectiveOperationException {
		LanguageDefinition definition = Registry.LANGUAGES.get(id).get();
		Language language = new Language(id, definition, Highlighter::getLanguage);

		assertEquals(language.getAliases(), Registry.ALIASES.get(id));
		assertEquals(id, definition.getClass().getField("ID").get(null));
		assertTrue(Registry.isBundled(id, definition));
	}

	@ParameterizedTest
	@MethodSource("bundledLanguages")
	void allRegularExpressionsCompile(String id) {
		Highlighter hl = new Highlighter();
		hl.disableSafeMode();
		// highlighting compiles the language
		hl.highlight(id, "");

		int[] count = { 0 };
		walk(Highlighter.getLanguage(id).getMode(), mode -> {
			assertTrue(mode.compiled || mode.variants != null, "not compiled");
			for (Pattern p : new Pattern[] { mode.beginRe, mode.endRe, mode.illegalRe, mode.keywordPatternRe }) {
				if (p != null) {
					count[0]++;
				}
			}
		});
		assertTrue(count[0] > 0);
	}

	@Test
	void registerCustomLanguageDefinition() {
		Highlighter.clearAllLanguages();
		Highlighter.registerLanguage("test", hljs -> new Mode()
				.aliases("tst")
				.keywords("hello")
				.contains(hljs.QUOTE_STRING_MODE));
		Highlighter hl = new Highlighter(false);

		assertEquals("<span class=\"hljs-keyword\">hello</span> <span class=\"hljs-string\">&quot;world&quot;</span>",
				hl.highlight("tst", "hello \"world\"").getValue());
		Highlighter.registerAllLanguages();
	}

	/**
	 * An illegal rule that matches at the end of a line is a zero width match; when
	 * illegal matches are ignored the parser must skip it instead of looping forever
	 * (highlight.js 10.0.2, issue #2522).
	 */
	@Test
	void zeroWidthIllegalMatchDoesNotFreeze() {
		Highlighter.registerLanguage("zero-width-illegal", hljs -> new Mode()
				.illegal("$")
				.contains(new Mode().className("keyword").begin("foo")), true);
		Highlighter hl = new Highlighter(false);
		hl.disableSafeMode();

		HighlightResult result = assertTimeoutPreemptively(Duration.ofSeconds(10),
				() -> hl.highlight("zero-width-illegal", "foo bar\nfoo", true));

		// highlight.js 10.0.2 skips the character after the zero width match, so the newline is dropped
		assertEquals("<span class=\"hljs-keyword\">foo</span> bar<span class=\"hljs-keyword\">foo</span>",
				result.getValue());
	}

	/**
	 * When an on:begin callback ignores a match, the rules after it are tried at
	 * the same position (highlight.js 10.1.0).
	 */
	@Test
	void onBeginCanIgnoreAMatch() {
		Highlighter.registerLanguage("ignore-match", hljs -> new Mode().contains(
				new Mode().className("keyword").begin("\\w+").onBegin((m, resp) -> {
					if (m.lexeme().equals("skip")) {
						resp.ignoreMatch();
					}
				}),
				new Mode().className("string").begin("sk").end("ip")), true);
		Highlighter hl = new Highlighter(false);
		hl.disableSafeMode();

		assertEquals("<span class=\"hljs-keyword\">a</span> <span class=\"hljs-string\">skip</span> "
				+ "<span class=\"hljs-keyword\">b</span>", hl.highlight("ignore-match", "a skip b").getValue());
	}

	@Test
	void endSameAsBegin() {
		Highlighter.registerLanguage("same-end", hljs -> new Mode().contains(
				hljs.END_SAME_AS_BEGIN(new Mode().className("string").begin("<<(\\w+)").end("(\\w+)>>"))), true);
		Highlighter hl = new Highlighter(false);
		hl.disableSafeMode();

		// the string only ends with the delimiter it began with
		assertEquals("<span class=\"hljs-string\">&lt;&lt;A x B&gt;&gt; y A&gt;&gt;</span> z",
				hl.highlight("same-end", "<<A x B>> y A>> z").getValue());
	}

	@Test
	void shebangOnlyAtTheStart() {
		Highlighter.registerLanguage("shebang", hljs -> new Mode().contains(hljs.SHEBANG()), true);
		Highlighter hl = new Highlighter(false);
		hl.disableSafeMode();

		assertEquals("<span class=\"hljs-meta\">#!/bin/sh</span>\n#!/bin/sh",
				hl.highlight("shebang", "#!/bin/sh\n#!/bin/sh").getValue());
	}

	@Test
	void keywordPattern() {
		Highlighter.registerLanguage("keyword-pattern", hljs -> new Mode()
				.keywords(Js.map("$pattern", "[a-z-]+", "keyword", "is-a")), true);
		Highlighter hl = new Highlighter(false);
		hl.disableSafeMode();

		assertEquals("<span class=\"hljs-keyword\">is-a</span> is", hl.highlight("keyword-pattern", "is-a is").getValue());
	}

	@Test
	void keywordPatternAndLexemesAreNotAllowedTogether() {
		Highlighter.registerLanguage("keyword-pattern-lexemes", hljs -> new Mode()
				.lexemes("\\w+")
				.keywords(Js.map("$pattern", "\\w+", "keyword", "a")), true);
		Highlighter hl = new Highlighter(false);
		hl.disableSafeMode();

		assertThrows(IllegalStateException.class, () -> hl.highlight("keyword-pattern-lexemes", "a"));
	}

	/** classNameAliases renames the CSS classes of modes and keywords (highlight.js 10.4.0). */
	@Test
	void classNameAliases() {
		Highlighter.registerLanguage("class-aliases", hljs -> new Mode()
				.classNameAliases(Js.map("my-string", "string", "my-keyword", "keyword"))
				.keywords(Js.map("my-keyword", "if"))
				.contains(new Mode().className("my-string").begin("'").end("'")), true);
		Highlighter hl = new Highlighter(false);
		hl.disableSafeMode();

		assertEquals("<span class=\"hljs-keyword\">if</span> <span class=\"hljs-string\">&#x27;a&#x27;</span>",
				hl.highlight("class-aliases", "if 'a'").getValue());
	}

	/** match is a shorthand for a mode that only has a begin (highlight.js 10.5.0). */
	@Test
	void matchIsShorthandForBegin() {
		Highlighter.registerLanguage("match", hljs -> new Mode()
				.contains(new Mode().className("number").match("\\d+")), true);
		Highlighter.registerLanguage("match-and-begin", hljs -> new Mode()
				.contains(new Mode().className("number").match("\\d+").begin("x")), true);
		Highlighter hl = new Highlighter(false);
		hl.disableSafeMode();

		assertEquals("a <span class=\"hljs-number\">12</span>", hl.highlight("match", "a 12").getValue());
		assertThrows(IllegalStateException.class, () -> hl.highlight("match-and-begin", "a 12"));
	}

	/** compilerExtensions of a language change its modes when it is compiled (highlight.js 10.5.0). */
	@Test
	void compilerExtensions() {
		Highlighter.registerLanguage("extensions", hljs -> new Mode()
				.compilerExtensions((mode, parent) -> {
					// a mode with a "word" becomes a keyword mode for that word
					if (mode.get("word") != null) {
						// match is already converted into begin when the extensions run
						mode.begin("\\b" + mode.get("word") + "\\b");
						mode.className("keyword");
						mode.remove("word");
					}
				})
				.contains(new Mode().set("word", "if")), true);
		Highlighter hl = new Highlighter(false);
		hl.disableSafeMode();

		assertEquals("<span class=\"hljs-keyword\">if</span> iffy", hl.highlight("extensions", "if iffy").getValue());
	}

	@Test
	void removeAProperty() {
		Mode mode = new Mode().begin("a").illegal(null).set("extra", 1);
		mode.remove("begin");
		mode.remove("illegal");
		mode.remove("extra");

		assertEquals(List.of(), mode.keys());
	}

	@Test
	void regexHelpers() {
		assertEquals("ab", Regex.concat("a", null, "b"));
		assertEquals("(?=a)", Regex.lookahead("a"));
		assertEquals("(a)*", Regex.anyNumberOfTimes("a"));
		assertEquals("(a)?", Regex.optional("a"));
		assertEquals("(a|b)", Regex.either("a", "b"));
	}

	@Test
	void languageCanExtendAnotherLanguage() {
		// cpp is built on c-like through `getLanguage('c-like').rawDefinition()`
		Highlighter hl = new Highlighter();

		assertTrue(hl.highlight("cpp", "int main() {}").getValue().contains("hljs-keyword"));
	}

	@Test
	void sharedListsKeepJavaScriptSemantics() {
		// json.js pushes OBJECT and ARRAY onto TYPES after TYPES was used in VALUE_CONTAINER
		Highlighter hl = new Highlighter();

		assertEquals("{<span class=\"hljs-attr\">&quot;a&quot;</span>: {<span class=\"hljs-attr\">&quot;b&quot;</span>: "
				+ "[<span class=\"hljs-number\">1</span>]}}", hl.highlight("json", "{\"a\": {\"b\": [1]}}").getValue());
	}

	@Test
	void inheritWithAnExplicitNullRemovesAProperty() {
		Hljs hljs = new Hljs(null);
		Mode mode = hljs.inherit(hljs.QUOTE_STRING_MODE, new Mode().illegal(null));

		assertNull(mode.illegal);
		assertTrue(mode.has("illegal"));
		assertEquals("\"", mode.begin);
		assertSame(hljs.QUOTE_STRING_MODE.contains, mode.contains);
		assertEquals("\\n", hljs.QUOTE_STRING_MODE.illegal);
	}

	@Test
	void selfIsNotAllowedAtTheTopLevel() {
		Highlighter.registerLanguage("selfish", hljs -> new Mode().contains(Mode.SELF, hljs.QUOTE_STRING_MODE), true);
		Highlighter hl = new Highlighter(false);
		hl.disableSafeMode();

		assertThrows(IllegalStateException.class, () -> hl.highlight("selfish", "\"a\""));
	}

	@Test
	void selfIsRemovedFromTheTopLevelInSafeMode() {
		Highlighter.registerLanguage("selfish2", hljs -> new Mode().contains(Mode.SELF, hljs.QUOTE_STRING_MODE), true);
		Highlighter hl = new Highlighter(false);

		assertEquals("<span class=\"hljs-string\">&quot;a&quot;</span>", hl.highlight("selfish2", "\"a\"").getValue());
	}

	@Test
	void invalidRegexThrowsWhenTheLanguageIsCompiled() {
		// like in highlight.js, also in safe mode
		Highlighter.registerLanguage("broken", hljs -> new Mode().contains(new Mode().begin("(").end("b")), true);
		Highlighter hl = new Highlighter(false);

		assertThrows(PatternSyntaxException.class, () -> hl.highlight("broken", "<a>"));
	}

	static Stream<Arguments> jsRegexes() {
		String w = "\\w";
		String s = "[" + JsRegex.SPACE + "]";
		return Stream.of(
				Arguments.of("empty class", "[]", "(?!)"),
				Arguments.of("negated empty class", "[^]", "[\\s\\S]"),
				Arguments.of("unicode escape", "\\u00C0", "\\u00C0"),
				Arguments.of("unicode code point escape", "\\u{1F600}", "\\x{1F600}"),
				Arguments.of("bracket in class", "[a[]", "[a\\[]"),
				Arguments.of("ampersands in class", "[&&a]", "[\\&\\&a]"),
				Arguments.of("hyphen after class escape", "[\\w-.]", "[" + w + "\\-.]"),
				Arguments.of("hyphen at class end", "[a-]", "[a\\-]"),
				Arguments.of("identity escape", "\\e", "e"),
				Arguments.of("identity escape of a Java escape", "\\Q\\h", "Qh"),
				Arguments.of("vertical tab", "\\v", "\\x0B"),
				Arguments.of("NUL", "\\0", "\\x00"),
				Arguments.of("backspace in class", "[\\b]", "[\\x08]"),
				Arguments.of("word boundary", "\\b", JsRegex.WORD_BOUNDARY),
				Arguments.of("not a word boundary", "\\B", JsRegex.NOT_WORD_BOUNDARY),
				Arguments.of("word characters", "\\w+", "\\w+"),
				Arguments.of("white space", "\\s", s),
				Arguments.of("non white space in class", "[^\\S\\n]", "[^[^" + JsRegex.SPACE + "]\\n]"),
				Arguments.of("literal brace", "@{a}", "@\\{a\\}"),
				Arguments.of("quantifier", "a{1,2}", "a{1,2}"),
				Arguments.of("dot", ".", "[^" + JsRegex.LINE_TERMINATOR + "]"),
				Arguments.of("backreference", "(a)\\1", "(a)\\1"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("jsRegexes")
	void jsRegexTranslation(String name, String js, String java) {
		assertEquals(java, JsRegex.translate(js));
		Pattern.compile(JsRegex.translate(js));
	}

	@Test
	void jsRegexSemantics() {
		// In JS "р1" has a word boundary between the Cyrillic letter and the digit
		assertTrue(JsRegex.compile("\\b\\d+", false).matcher("\u04401").find());
		assertFalse(JsRegex.compile("\\w", false).matcher("\u0440").find());
		// ... and no word boundaries between Cyrillic letters, not even in Java before JDK 19
		assertFalse(JsRegex.compile("\\b", false).matcher("\u0440\u0440").find());
		assertTrue(JsRegex.compile("\\B", false).matcher("\u0440\u0440").find());
		assertTrue(JsRegex.compile("\\bab\\b", true).matcher("AB").find());
		// \s includes Unicode spaces
		assertTrue(JsRegex.compile("^\\s$", false).matcher("\u00a0").find());
		// $ matches before \r, also when it is followed by \n
		assertTrue(JsRegex.compile("a$", false).matcher("a\r\nb").find());
		assertTrue(JsRegex.compile("^b", false).matcher("a\rb").find());
		// . does not match line terminators
		assertFalse(JsRegex.compile("a.b", false).matcher("a\u2028b").find());
		assertTrue(JsRegex.compile("a.b", false).matcher("a\u0085b").find());
		assertTrue(JsRegex.compile("ABC", true).matcher("abc").find());
		assertEquals(3, JsRegex.countMatchGroups("(a)(?:b)(c(d))"));
	}

	@Test
	void joinRenumbersBackreferences() {
		assertEquals("(a(b)\\2)|(c(d)\\4)", Terminators.joinRe(List.of("a(b)\\1", "c(d)\\1"), "|"));
	}

	private static void walk(Mode mode, Consumer<Mode> callback) {
		walk(mode, callback, Collections.newSetFromMap(new IdentityHashMap<>()));
	}

	private static void walk(Mode mode, Consumer<Mode> callback, Set<Mode> seen) {
		if (!seen.add(mode)) {
			return;
		}
		callback.accept(mode);
		for (List<Mode> children : List.of(nonNull(mode.contains), nonNull(mode.cachedVariants))) {
			for (Mode child : children) {
				walk(child, callback, seen);
			}
		}
		if (mode.starts != null) {
			walk(mode.starts, callback, seen);
		}
	}

	private static List<Mode> nonNull(List<Mode> list) {
		return list == null ? List.of() : list;
	}
}
