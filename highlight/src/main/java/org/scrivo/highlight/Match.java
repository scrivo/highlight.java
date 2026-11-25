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

import java.util.Arrays;
import java.util.List;

/**
 * A match of a terminator of a mode, as handed to the callbacks of a mode
 * ({@link Mode#onBegin} and {@link Mode#onEnd}).
 * <p>
 * Like the match array of highlight.js, group 0 is the text matched by the
 * rule and the groups that follow are the capturing groups of the rule's own
 * regular expression: {@code group(1)} is its first group.
 */
public final class Match {

	private final int index;
	private final String[] groups;
	private final String input;

	/** The kind of terminator: {@link Terminators#BEGIN}, {@link Terminators#END} or {@link Terminators#ILLEGAL}. */
	final String type;
	/** The mode that begins, for a begin match. */
	final Mode rule;
	/** The position of the rule among the terminators of the mode. */
	final int position;

	Match(int index, String[] groups, String input, String type, Mode rule, int position) {
		this.index = index;
		this.groups = groups;
		this.input = input;
		this.type = type;
		this.rule = rule;
		this.position = position;
	}

	/** The position of the match in the code. */
	public int index() {
		return index;
	}

	/** The matched text. */
	public String lexeme() {
		return groups[0];
	}

	/**
	 * A group of the match: 0 for the matched text, 1 and up for the capturing
	 * groups of the rule.
	 *
	 * @return the text of the group, or null when the group did not participate
	 *         in the match
	 */
	public String group(int group) {
		return group >= 0 && group < groups.length ? groups[group] : null;
	}

	/** The number of capturing groups (not counting group 0). */
	public int groupCount() {
		return groups.length - 1;
	}

	/** The code the match was found in. */
	public String input() {
		return input;
	}

	/** The groups, from group 0. */
	public List<String> groups() {
		return Arrays.asList(groups.clone());
	}

	@Override
	public String toString() {
		return "Match[" + index + ", " + Arrays.toString(groups) + "]";
	}
}
