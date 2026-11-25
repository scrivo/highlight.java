package org.scrivo.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SpecialTest {

	private static String code(String name) {
		return TestData.read("special/" + name + ".txt");
	}

	private static String expected(String name) {
		return TestData.read("special/" + name + ".expect.txt");
	}

	@Test
	void tabReplace() {
		Highlighter hl = new Highlighter();
		hl.setTabReplace("    ");

		assertEquals(expected("tabreplace"), hl.highlight("python", code("tabreplace")).getValue());
	}

	@Test
	void languageAlias() {
		Highlighter hl = new Highlighter();

		assertEquals(expected("languagealias"), hl.highlight("js", code("languagealias")).getValue());
	}

	@Test
	void subLanguage() {
		Highlighter hl = new Highlighter();

		assertEquals(expected("sublanguages"), hl.highlight("xml", code("sublanguages")).getValue());
	}

	@Test
	void windowsCrlf() {
		Highlighter hl = new Highlighter();

		assertEquals(expected("line-endings.crlf"), hl.highlight("js", code("line-endings.crlf")).getValue());
	}
}
