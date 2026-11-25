/* Copyright (c) 2026 Geert Bergman (geert@scrivo.nl), highlight.java
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. Neither the name of "highlight.js", "highlight.java", nor the names of its
 *    contributors may be used to endorse or promote products derived from this
 *    software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package org.scrivo.highlight;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Translates JavaScript regular expressions into equivalent
 * {@link java.util.regex.Pattern}s.
 * <p>
 * Language definitions use JavaScript regex syntax, highlight.js compiles them
 * with the {@code m} flag (and {@code i} for case insensitive languages). These
 * are the differences with Java that matter:
 * <ul>
 * <li>{@code ^}, {@code $} and {@code .} use JavaScript's line terminators
 * ({@code \n}, {@code \r}, {@code  } and {@code  }): Java treats
 * {@code \r\n} as a single line terminator and also knows {@code \u0085};</li>
 * <li>{@code \s} includes Unicode spaces in JavaScript;</li>
 * <li>{@code \v} is a vertical tab in JavaScript, a class in Java;</li>
 * <li>{@code \0} is the NUL character in JavaScript;</li>
 * <li>{@code []} (never matches) and {@code [^]} (matches anything) are errors
 * in Java;</li>
 * <li>inside a character class an unescaped {@code [} is a literal, in Java it
 * starts a nested class; {@code &&} is an intersection in Java; a {@code -}
 * next to a class escape such as {@code \w} is a literal;</li>
 * <li>{@code \b} is a backspace inside a character class;</li>
 * <li>an opening brace that does not start a quantifier is a literal in JavaScript,
 * an error in Java;</li>
 * <li>escaped letters that are not JavaScript escapes are literals in
 * JavaScript, but errors or something else (like {@code \h}, {@code \e},
 * {@code \Q}) in Java.</li>
 * </ul>
 * {@code \w} and {@code \d} are ASCII only in both flavors. {@code \b} and
 * {@code \B} are ASCII only in JavaScript, but only since JDK 19 in Java, so
 * on older JDKs they are spelled out with look-arounds on {@code \w}.
 */
public final class JsRegex {

	/** JavaScript's {@code \s}. */
	static final String SPACE = "\\t\\n\\x0B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff";

	/**
	 * Whether Java's {@code \b} is ASCII only, like JavaScript's: before JDK 19 it
	 * considers Unicode letters to be word characters (JDK-8264160).
	 */
	private static final boolean ASCII_WORD_BOUNDARY = Runtime.version().feature() >= 19;

	/** JavaScript's {@code \b}, the native one is faster but only correct as of JDK 19. */
	static final String WORD_BOUNDARY = ASCII_WORD_BOUNDARY ? "\\b" : "(?:(?<=\\w)(?!\\w)|(?<!\\w)(?=\\w))";

	/** JavaScript's {@code \B}. */
	static final String NOT_WORD_BOUNDARY = ASCII_WORD_BOUNDARY ? "\\B" : "(?:(?<=\\w)(?=\\w)|(?<!\\w)(?!\\w))";

	/** JavaScript's line terminators. */
	static final String LINE_TERMINATOR = "\\n\\r\\u2028\\u2029";

	private static final Map<String, String> TRANSLATIONS = new ConcurrentHashMap<>();
	private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

	private JsRegex() {
	}

	/**
	 * Compiles the source of a JavaScript regular expression, as highlight.js
	 * would with the {@code m} flag.
	 *
	 * @param source          the JavaScript regex source
	 * @param caseInsensitive adds the {@code i} flag
	 * @throws java.util.regex.PatternSyntaxException if the expression is invalid
	 */
	public static Pattern compile(String source, boolean caseInsensitive) {
		String key = (caseInsensitive ? "i" : "-") + source;
		Pattern pattern = PATTERNS.get(key);
		if (pattern == null) {
			int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
			pattern = Pattern.compile(translate(source), flags);
			PATTERNS.put(key, pattern);
		}
		return pattern;
	}

	/**
	 * Counts the capture groups of a JavaScript regular expression.
	 */
	public static int countMatchGroups(String source) {
		return compile(source + "|", false).matcher("").groupCount();
	}

	/**
	 * Translates the source of a JavaScript regular expression (with the
	 * {@code m} flag) into an equivalent Java regular expression.
	 */
	public static String translate(String source) {
		String translated = TRANSLATIONS.get(source);
		if (translated == null) {
			translated = new Translator(source).translate();
			TRANSLATIONS.put(source, translated);
		}
		return translated;
	}

	private static final class Translator {
		private final String source;
		private final int length;
		private final StringBuilder out = new StringBuilder();

		/** Whether the last escape translated in a class was a class escape, like {@code \w}. */
		private boolean classEscape;

		Translator(String source) {
			this.source = source;
			this.length = source.length();
		}

		String translate() {
			for (int i = 0; i < length; i++) {
				char ch = source.charAt(i);
				switch (ch) {
				case '\\' -> i = escape(i, false, out);
				case '[' -> i = characterClass(i);
				case '{' -> i = brace(i);
				case '}' -> out.append("\\}");
				case ']' -> out.append("\\]");
				case '.' -> out.append("[^").append(LINE_TERMINATOR).append(']');
				case '^' -> out.append("(?:^|(?<=[").append(LINE_TERMINATOR).append("]))");
				case '$' -> out.append("(?=[").append(LINE_TERMINATOR).append("]|\\z)");
				default -> out.append(ch);
				}
			}
			return out.toString();
		}

		/** A quantifier stays a quantifier, any other brace is a literal. */
		private int brace(int i) {
			int j = i + 1;
			int digits = 0;
			while (j < length && Character.isDigit(source.charAt(j))) {
				j++;
				digits++;
			}
			if (digits > 0 && j < length && source.charAt(j) == ',') {
				j++;
				while (j < length && Character.isDigit(source.charAt(j))) {
					j++;
				}
			}
			if (digits > 0 && j < length && source.charAt(j) == '}') {
				out.append(source, i, j + 1);
				return j;
			}
			out.append("\\{");
			return i;
		}

		/**
		 * @param i position of the opening {@code [}
		 * @return the position of the closing {@code ]}
		 */
		private int characterClass(int i) {
			int start = i;
			boolean negated = i + 1 < length && source.charAt(i + 1) == '^';
			i += negated ? 2 : 1;

			StringBuilder items = new StringBuilder();
			boolean prevClassEscape = false;

			for (; i < length; i++) {
				char ch = source.charAt(i);
				if (ch == ']') {
					break;
				}
				if (ch == '\\') {
					i = escape(i, true, items);
					prevClassEscape = classEscape;
					continue;
				}
				if (ch == '[' || ch == '&') {
					items.append('\\').append(ch);
				} else if (ch == '-' && (prevClassEscape || nextIsClassEscape(i + 1)
						|| (i + 1 < length && source.charAt(i + 1) == ']'))) {
					items.append("\\-");
				} else {
					items.append(ch);
				}
				prevClassEscape = false;
			}

			if (i >= length) {
				// unterminated class, leave it to Java to report
				out.append(source, start, length);
				return length - 1;
			}

			if (items.length() == 0) {
				// `[]` never matches, `[^]` matches anything
				out.append(negated ? "[\\s\\S]" : "(?!)");
			} else {
				out.append('[').append(negated ? "^" : "").append(items).append(']');
			}
			return i;
		}

		private boolean nextIsClassEscape(int i) {
			return i + 1 < length && source.charAt(i) == '\\' && "dDwWsS".indexOf(source.charAt(i + 1)) >= 0;
		}

		/**
		 * @param i position of the backslash
		 * @return the position of the last character of the escape
		 */
		private int escape(int i, boolean inClass, StringBuilder out) {
			classEscape = false;
			if (i + 1 >= length) {
				out.append("\\\\");
				return i;
			}

			char next = source.charAt(i + 1);
			switch (next) {
			case 'u': {
				if (i + 2 < length && source.charAt(i + 2) == '{') {
					int close = source.indexOf('}', i + 3);
					if (close > i + 3 && isHex(i + 3, close)) {
						out.append("\\x{").append(source, i + 3, close).append('}');
						return close;
					}
				}
				if (i + 6 <= length && isHex(i + 2, i + 6)) {
					out.append(source, i, i + 6);
					return i + 5;
				}
				out.append('u');
				return i + 1;
			}
			case 'x':
				if (i + 4 <= length && isHex(i + 2, i + 4)) {
					out.append(source, i, i + 4);
					return i + 3;
				}
				out.append('x');
				return i + 1;
			case 'c':
				if (i + 2 < length && isAsciiLetter(source.charAt(i + 2))) {
					out.append(source, i, i + 3);
					return i + 2;
				}
				out.append("\\\\c");
				return i + 1;
			case 'v':
				out.append("\\x0B");
				return i + 1;
			case '0':
				out.append("\\x00");
				return i + 1;
			case 'd', 'D', 'w', 'W':
				classEscape = true;
				out.append('\\').append(next);
				return i + 1;
			case 's':
				classEscape = true;
				out.append(inClass ? SPACE : "[" + SPACE + "]");
				return i + 1;
			case 'S':
				// a nested class in a class is a union in Java
				classEscape = true;
				out.append("[^").append(SPACE).append(']');
				return i + 1;
			case 't', 'n', 'r', 'f':
				out.append('\\').append(next);
				return i + 1;
			case 'b':
				// in a JavaScript character class `\b` is a backspace
				out.append(inClass ? "\\x08" : WORD_BOUNDARY);
				return i + 1;
			case 'B':
				out.append(inClass ? "B" : NOT_WORD_BOUNDARY);
				return i + 1;
			case 'k':
				if (i + 2 < length && source.charAt(i + 2) == '<') {
					out.append("\\k");
					return i + 1;
				}
				out.append('k');
				return i + 1;
			default:
				if (next >= '1' && next <= '9') {
					out.append('\\').append(next);
					return i + 1;
				}
				if (isAsciiLetter(next) || next > 0x7f) {
					// an identity escape: a literal character in JavaScript
					out.append(next);
					return i + 1;
				}
				// escaped punctuation is a literal in both flavors
				out.append('\\').append(next);
				return i + 1;
			}
		}

		private boolean isHex(int from, int to) {
			for (int j = from; j < to; j++) {
				if (Character.digit(source.charAt(j), 16) < 0) {
					return false;
				}
			}
			return true;
		}

		private static boolean isAsciiLetter(char ch) {
			return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
		}
	}
}
