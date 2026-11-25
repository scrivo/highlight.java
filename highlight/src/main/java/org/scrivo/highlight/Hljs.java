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

import java.util.Locale;
import java.util.function.Function;

/**
 * The {@code hljs} object handed to language definitions: the common regular
 * expressions and modes, and helpers to build modes and to use other languages.
 * <p>
 * A new instance, with new common modes, is created each time a definition is
 * evaluated, so the modes of a language are never shared with another language.
 */
public final class Hljs {

	// Common regexps
	public final String IDENT_RE = "[a-zA-Z]\\w*";
	public final String UNDERSCORE_IDENT_RE = "[a-zA-Z_]\\w*";
	public final String NUMBER_RE = "\\b\\d+(\\.\\d+)?";
	/** 0x..., 0..., decimal, float */
	public final String C_NUMBER_RE = "(-?)(\\b0[xX][a-fA-F0-9]+|(\\b\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?)";
	/** 0b... */
	public final String BINARY_NUMBER_RE = "\\b(0b[01]+)";
	public final String RE_STARTERS_RE = "!|!=|!==|%|%=|&|&&|&=|\\*|\\*=|\\+|\\+=|,|-|-=|/=|/|:|;|<<|<<=|<=|<|===|==|=|>>>=|>>=|>=|>>>|>>|>|\\?|\\[|\\{|\\(|\\^|\\^=|\\||\\|=|\\|\\||~";

	// Common modes
	public final Mode BACKSLASH_ESCAPE;
	public final Mode APOS_STRING_MODE;
	public final Mode QUOTE_STRING_MODE;
	public final Mode PHRASAL_WORDS_MODE;
	public final Mode C_LINE_COMMENT_MODE;
	public final Mode C_BLOCK_COMMENT_MODE;
	public final Mode HASH_COMMENT_MODE;
	public final Mode NUMBER_MODE;
	public final Mode C_NUMBER_MODE;
	public final Mode BINARY_NUMBER_MODE;
	public final Mode CSS_NUMBER_MODE;
	public final Mode REGEXP_MODE;
	public final Mode TITLE_MODE;
	public final Mode UNDERSCORE_TITLE_MODE;
	public final Mode METHOD_GUARD;

	private final Function<String, Language> languageResolver;

	/**
	 * @param languageResolver resolves a language name or alias, may be null
	 */
	public Hljs(Function<String, Language> languageResolver) {
		this.languageResolver = languageResolver == null ? name -> null : languageResolver;

		BACKSLASH_ESCAPE = new Mode()
				.begin("\\\\[\\s\\S]")
				.relevance(0);
		APOS_STRING_MODE = new Mode()
				.className("string")
				.begin("'")
				.end("'")
				.illegal("\\n")
				.contains(BACKSLASH_ESCAPE);
		QUOTE_STRING_MODE = new Mode()
				.className("string")
				.begin("\"")
				.end("\"")
				.illegal("\\n")
				.contains(BACKSLASH_ESCAPE);
		PHRASAL_WORDS_MODE = new Mode()
				.begin("\\b(a|an|the|are|I'm|isn't|don't|doesn't|won't|but|just|should|pretty|simply|enough|gonna|going|wtf|so|such|will|you|your|they|like|more)\\b");
		C_LINE_COMMENT_MODE = COMMENT("//", "$");
		C_BLOCK_COMMENT_MODE = COMMENT("/\\*", "\\*/");
		HASH_COMMENT_MODE = COMMENT("#", "$");
		NUMBER_MODE = new Mode()
				.className("number")
				.begin(NUMBER_RE)
				.relevance(0);
		C_NUMBER_MODE = new Mode()
				.className("number")
				.begin(C_NUMBER_RE)
				.relevance(0);
		BINARY_NUMBER_MODE = new Mode()
				.className("number")
				.begin(BINARY_NUMBER_RE)
				.relevance(0);
		CSS_NUMBER_MODE = new Mode()
				.className("number")
				.begin(NUMBER_RE + "("
						+ "%|em|ex|ch|rem"
						+ "|vw|vh|vmin|vmax"
						+ "|cm|mm|in|pt|pc|px"
						+ "|deg|grad|rad|turn"
						+ "|s|ms"
						+ "|Hz|kHz"
						+ "|dpi|dpcm|dppx"
						+ ")?")
				.relevance(0);
		REGEXP_MODE = new Mode()
				// this outer rule makes sure we actually have a WHOLE regex and not simply
				// an expression such as:
				//
				// 3 / something
				//
				// (which will then blow up when regex's `illegal` sees the newline)
				.begin("(?=\\/[^/\\n]*\\/)")
				.contains(new Mode()
						.className("regexp")
						.begin("\\/")
						.end("\\/[gimuy]*")
						.illegal("\\n")
						.contains(
								BACKSLASH_ESCAPE,
								new Mode()
										.begin("\\[")
										.end("\\]")
										.relevance(0)
										.contains(BACKSLASH_ESCAPE)));
		TITLE_MODE = new Mode()
				.className("title")
				.begin(IDENT_RE)
				.relevance(0);
		UNDERSCORE_TITLE_MODE = new Mode()
				.className("title")
				.begin(UNDERSCORE_IDENT_RE)
				.relevance(0);
		METHOD_GUARD = new Mode()
				// excludes method names from keyword processing
				.begin("\\.\\s*" + UNDERSCORE_IDENT_RE)
				.relevance(0);
	}

	/**
	 * Performs a shallow merge of modes into a new mode: the properties of the
	 * parent, overridden by the properties of each of the overrides in turn.
	 * Lists and nested modes are shared, not copied, as in JavaScript.
	 */
	public Mode inherit(Mode parent, Mode... overrides) {
		return merge(parent, overrides);
	}

	static Mode merge(Mode parent, Mode... overrides) {
		Mode result = new Mode();
		result.assign(parent);
		for (Mode override : overrides) {
			if (override != null) {
				result.assign(override);
			}
		}
		return result;
	}

	/** Creates a comment mode. */
	public Mode COMMENT(String begin, String end) {
		return COMMENT(begin, end, null);
	}

	/**
	 * Creates a comment mode.
	 *
	 * @param inherits properties that override the defaults, may be null
	 */
	public Mode COMMENT(String begin, String end, Mode inherits) {
		Mode mode = merge(new Mode()
				.className("comment")
				.begin(begin)
				.end(end)
				.contains(new Mode[0]),
				inherits);
		mode.contains.add(PHRASAL_WORDS_MODE);
		mode.contains.add(new Mode()
				.className("doctag")
.begin("(?:TODO|FIXME|NOTE|BUG|OPTIMIZE|HACK|XXX):")
				.relevance(0));
		return mode;
	}

	/** Creates a mode for a shebang line ({@code #!/usr/bin/env ...}) at the start of the code. */
	public Mode SHEBANG() {
		return SHEBANG(null);
	}

	/**
	 * Creates a mode for a shebang line ({@code #!/usr/bin/env ...}) at the start
	 * of the code.
	 *
	 * @param opts properties that override the defaults, may be null; its
	 *             {@code binary} property (a regex) limits the mode to shebang
	 *             lines of that interpreter
	 */
	public Mode SHEBANG(Mode opts) {
		String beginShebang = "^#![ ]*\\/";
		if (opts == null) {
			opts = new Mode();
		}
		Object binary = opts.get("binary");
		if (binary != null) {
			opts.begin(Regex.concat(beginShebang, ".*\\b", (String) binary, "\\b.*"));
		}
		return merge(new Mode()
				.className("meta")
				.begin(beginShebang)
				.end("$")
				.relevance(0)
				.onBegin((m, resp) -> {
					if (m.index() != 0) {
						resp.ignoreMatch();
					}
				}), opts);
	}

	/**
	 * Makes a mode end with the same text as its first group matched when it
	 * began: the first group of the end pattern must match the same text.
	 *
	 * @return the mode itself
	 */
	public Mode END_SAME_AS_BEGIN(Mode mode) {
		return mode
				.onBegin((m, resp) -> resp.data().put("_beginMatch", m.group(1)))
				.onEnd((m, resp) -> {
					if (!java.util.Objects.equals(resp.data().get("_beginMatch"), m.group(1))) {
						resp.ignoreMatch();
					}
				});
	}

	/**
	 * @param name a language name or alias
	 * @return the language, or null when it is not available
	 */
	public Language getLanguage(String name) {
		return languageResolver.apply(name == null ? "" : name.toLowerCase(Locale.ROOT));
	}

	/**
	 * @param name a language name or alias
	 * @throws IllegalStateException when the language is not available
	 */
	public Language requireLanguage(String name) {
		Language language = getLanguage(name);
		if (language == null) {
			throw new IllegalStateException("The '" + name + "' language is required, but not loaded.");
		}
		return language;
	}
}
