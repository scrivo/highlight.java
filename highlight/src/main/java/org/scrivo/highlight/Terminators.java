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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Searches for all the terminators of a mode at once: the begin patterns of the
 * contained modes, the end pattern and the illegal pattern. They are joined in a
 * single {@code (a)|(b)|(c)} regex, the group that matched tells which
 * terminator was found (MultiRegex in highlight.js).
 * <p>
 * Instances are immutable and can be shared between threads.
 */
final class Terminators {

	static final String BEGIN = "begin";
	static final String END = "end";
	static final String ILLEGAL = "illegal";

	/** A match of a terminator. */
	record Match(int index, String lexeme, String type, Mode rule) {
	}

	/** Backreferences, and what may look like them: classes, escapes and groups. */
	private static final Pattern BACKREFERENCE_RE = Pattern
			.compile("\\[(?:[^\\\\\\]]|\\\\.)*\\]|\\(\\??|\\\\([1-9][0-9]*)|\\\\.");

	/** The rule (a Mode, or the type of the terminator) for the group number of each terminator. */
	private final Object[] matchIndexes;
	private final Pattern matcherRe;

	Terminators(Mode mode, boolean caseInsensitive) {
		List<Object> rules = new ArrayList<>();
		List<String> regexes = new ArrayList<>();

		for (Mode term : mode.contains) {
			// highlight.js ignores keyword matches preceded or followed by a dot, which is what these look-arounds do
			regexes.add(term.beginKeywords != null ? "(?<!\\.)(?:" + term.begin + ")(?!\\.)" : term.begin);
			rules.add(term);
		}
		if (mode.terminatorEnd != null && !mode.terminatorEnd.isEmpty()) {
			regexes.add(mode.terminatorEnd);
			rules.add(END);
		}
		if (mode.illegal != null && !mode.illegal.isEmpty()) {
			regexes.add(mode.illegal);
			rules.add(ILLEGAL);
		}

		if (regexes.isEmpty()) {
			matchIndexes = new Object[0];
			matcherRe = null;
			return;
		}

		List<Object> indexes = new ArrayList<>();
		indexes.add(null);
		for (int i = 0; i < regexes.size(); i++) {
			indexes.add(rules.get(i));
			for (int g = JsRegex.countMatchGroups(regexes.get(i)); g > 0; g--) {
				indexes.add(null);
			}
		}
		matchIndexes = indexes.toArray();
		matcherRe = JsRegex.compile(joinRe(regexes, "|"), caseInsensitive);
	}

	/**
	 * Finds the first terminator in the input at or after the given position.
	 *
	 * @return the match, or null when there is none
	 */
	Match exec(String input, int fromIndex) {
		if (matcherRe == null || fromIndex > input.length()) {
			return null;
		}
		Matcher m = matcherRe.matcher(input);
		if (!m.find(fromIndex)) {
			return null;
		}
		for (int i = 1; i <= m.groupCount() && i < matchIndexes.length; i++) {
			if (matchIndexes[i] != null && m.start(i) != -1) {
				Object rule = matchIndexes[i];
				if (rule instanceof Mode mode) {
					return new Match(m.start(), m.group(), BEGIN, mode);
				}
				return new Match(m.start(), m.group(), (String) rule, null);
			}
		}
		return new Match(m.start(), m.group(), null, null);
	}

	/**
	 * Joins regular expressions with the separator, putting each of them in a
	 * group and renumbering their backreferences so they continue to match.
	 */
	static String joinRe(List<String> regexps, String separator) {
		int numCaptures = 0;
		StringBuilder ret = new StringBuilder();

		for (int i = 0; i < regexps.size(); i++) {
			numCaptures++;
			int offset = numCaptures;
			String re = regexps.get(i);

			if (i > 0) {
				ret.append(separator);
			}
			ret.append('(');

			while (!re.isEmpty()) {
				Matcher m = BACKREFERENCE_RE.matcher(re);
				if (!m.find()) {
					ret.append(re);
					break;
				}
				ret.append(re, 0, m.start());
				re = re.substring(m.end());
				if (m.group().charAt(0) == '\\' && m.group(1) != null) {
					// adjust the backreference
					ret.append('\\').append(Integer.parseInt(m.group(1)) + offset);
				} else {
					ret.append(m.group());
					if (m.group().equals("(")) {
						numCaptures++;
					}
				}
			}
			ret.append(')');
		}
		return ret.toString();
	}
}
