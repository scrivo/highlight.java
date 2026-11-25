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
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Searches for all the terminators of a mode at once: the begin patterns of the
 * contained modes, the end pattern and the illegal pattern. They are joined in a
 * single {@code (a)|(b)|(c)} regex, the group that matched tells which
 * terminator was found (MultiRegex in highlight.js).
 * <p>
 * The search can be resumed after a rule whose match was ignored by a callback:
 * then only the rules that follow it are considered (ResumableMultiRegex in
 * highlight.js). The regex for each starting rule is built when it is first
 * needed.
 * <p>
 * Instances can be shared between threads.
 */
final class Terminators {

	static final String BEGIN = "begin";
	static final String END = "end";
	static final String ILLEGAL = "illegal";

	/** Backreferences, and what may look like them: classes, escapes and groups. */
	private static final Pattern BACKREFERENCE_RE = Pattern
			.compile("\\[(?:[^\\\\\\]]|\\\\.)*\\]|\\(\\??|\\\\([1-9][0-9]*)|\\\\.");

	/** The regex of each rule. */
	private final List<String> regexes = new ArrayList<>();
	/** The rule of each regex: the mode that begins, or the type of the terminator. */
	private final List<Object> rules = new ArrayList<>();
	/** The number of begin rules, they come first. */
	private final int count;
	private final boolean caseInsensitive;
	/** The joined regexes, by the index of the first rule they hold. */
	private final AtomicReferenceArray<Joined> joined;

	/** The regexes of the rules from a rule on, joined in one regex. */
	private record Joined(Pattern matcherRe, int[] ruleOfGroup) {
	}

	Terminators(Mode mode, boolean caseInsensitive) {
		this.caseInsensitive = caseInsensitive;
		for (Mode term : mode.contains) {
			regexes.add(term.begin);
			rules.add(term);
		}
		count = regexes.size();
		if (mode.terminatorEnd != null && !mode.terminatorEnd.isEmpty()) {
			regexes.add(mode.terminatorEnd);
			rules.add(END);
		}
		if (mode.illegal != null && !mode.illegal.isEmpty()) {
			regexes.add(mode.illegal);
			rules.add(ILLEGAL);
		}
		joined = new AtomicReferenceArray<>(Math.max(regexes.size(), 1));
	}

	/** The number of begin rules. */
	int count() {
		return count;
	}

	private Joined joined(int startRule) {
		Joined j = joined.get(startRule);
		if (j == null) {
			List<String> slice = regexes.subList(startRule, regexes.size());
			List<Integer> ruleOfGroup = new ArrayList<>();
			ruleOfGroup.add(-1);
			for (int i = 0; i < slice.size(); i++) {
				ruleOfGroup.add(startRule + i);
				for (int g = JsRegex.countMatchGroups(slice.get(i)); g > 0; g--) {
					ruleOfGroup.add(-1);
				}
			}
			j = new Joined(JsRegex.compile(joinRe(slice, "|"), caseInsensitive),
					ruleOfGroup.stream().mapToInt(Integer::intValue).toArray());
			joined.compareAndSet(startRule, null, j);
		}
		return j;
	}

	/**
	 * Finds the first terminator in the input at or after the given position.
	 *
	 * @param startRule only the rules from this one on are considered
	 * @return the match, or null when there is none
	 */
	Match exec(String input, int fromIndex, int startRule) {
		if (startRule >= regexes.size() || fromIndex > input.length()) {
			return null;
		}
		Joined j = joined(startRule);
		Matcher m = j.matcherRe().matcher(input);
		if (!m.find(fromIndex)) {
			return null;
		}
		for (int i = 1; i <= m.groupCount() && i < j.ruleOfGroup().length; i++) {
			int position = j.ruleOfGroup()[i];
			if (position >= 0 && m.start(i) != -1) {
				// like highlight.js, drop the groups of the rules before the one that matched
				String[] groups = new String[m.groupCount() - i + 1];
				for (int g = 0; g < groups.length; g++) {
					groups[g] = m.group(i + g);
				}
				Object rule = rules.get(position);
				if (rule instanceof Mode mode) {
					return new Match(m.start(), groups, input, BEGIN, mode, position);
				}
				return new Match(m.start(), groups, input, (String) rule, null, position);
			}
		}
		return new Match(m.start(), new String[] { m.group() }, input, null, null, -1);
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
