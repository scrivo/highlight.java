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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A registered language: a language definition and its compiled mode tree.
 */
public final class Language {

	private static final List<String> COMMON_KEYWORDS = List.of("of", "and", "for", "in", "not", "or", "if", "then");

	private final String id;
	private final LanguageDefinition definition;
	private final Function<String, Language> languageResolver;
	private volatile Mode mode;
	private volatile boolean compiled;

	/**
	 * @param id               the language id, like {@code "javascript"}
	 * @param definition       the language definition
	 * @param languageResolver resolves other languages by name or alias, used by
	 *                         definitions that build upon other languages
	 */
	public Language(String id, LanguageDefinition definition, Function<String, Language> languageResolver) {
		this.id = id;
		this.definition = definition;
		this.languageResolver = languageResolver == null ? name -> null : languageResolver;
	}

	public String getId() {
		return id;
	}

	public LanguageDefinition getDefinition() {
		return definition;
	}

	/**
	 * Evaluates the language definition anew, like {@code rawDefinition()} in
	 * highlight.js. The result is not compiled and can be modified, which is how
	 * a language can build upon another one.
	 */
	public Mode rawDefinition() {
		return definition.define(new Hljs(languageResolver));
	}

	/** The mode tree of this language, as defined by its definition. */
	public Mode getMode() {
		Mode m = mode;
		if (m == null) {
			synchronized (this) {
				if (mode == null) {
					mode = rawDefinition();
				}
				m = mode;
			}
		}
		return m;
	}

	/** The human readable name of the language, like {@code "JavaScript"}. */
	public String getName() {
		return getMode().name;
	}

	public List<String> getAliases() {
		return getMode().aliases == null ? List.of() : getMode().aliases;
	}

	public boolean isCaseInsensitive() {
		return Mode.is(getMode().case_insensitive);
	}

	public boolean isDisableAutodetect() {
		return Mode.is(getMode().disableAutodetect);
	}

	/**
	 * Compiles the language definition.
	 *
	 * @param safeMode when false, errors in the definition are thrown instead of
	 *                 being worked around
	 */
	void compile(boolean safeMode) {
		if (compiled) {
			return;
		}
		synchronized (this) {
			if (compiled) {
				return;
			}
			Mode root = getMode();
			// self is not valid at the top-level
			if (root.contains != null && root.contains.contains(Mode.SELF)) {
				if (!safeMode) {
					throw new IllegalStateException("`self` is not supported at the top-level of a language.");
				}
				root.contains = new ArrayList<>(root.contains);
				root.contains.removeIf(c -> c == Mode.SELF);
			}
			new Compiler(isCaseInsensitive()).compileMode(root, null);
			compiled = true;
		}
	}

	@Override
	public String toString() {
		return "Language[" + id + "]";
	}

	/** Compiles a mode tree (mode_compiler.js in highlight.js). */
	private static final class Compiler {
		private final boolean caseInsensitive;

		Compiler(boolean caseInsensitive) {
			this.caseInsensitive = caseInsensitive;
		}

		private Pattern langRe(String value) {
			return JsRegex.compile(value, caseInsensitive);
		}

		void compileMode(Mode mode, Mode parent) {
			if (mode.compiled) {
				return;
			}
			mode.compiled = true;

			Object keywords = mode.keywords != null && !"".equals(mode.keywords) ? mode.keywords : mode.beginKeywords;
			if (keywords != null && !"".equals(keywords)) {
				mode.compiledKeywords = compileKeywords(keywords);
			}

			mode.lexemesRe = langRe(mode.lexemes != null && !mode.lexemes.isEmpty() ? mode.lexemes : "\\w+");

			if (parent != null) {
				if (mode.beginKeywords != null && !mode.beginKeywords.isEmpty()) {
					// for languages with keywords that include non-word characters checking for
					// a word boundary is not sufficient, so instead we check for a word boundary
					// or whitespace - this does no harm in any case since our keyword engine
					// doesn't allow spaces in keywords anyways and we still check for the boundary
					// first
					mode.begin = "\\b(" + String.join("|", mode.beginKeywords.split(" ", -1)) + ")(?=\\b|\\s)";
				}
				if (mode.begin == null || mode.begin.isEmpty()) {
					mode.begin = "\\B|\\b";
				}
				mode.beginRe = langRe(mode.begin);
				if (Mode.is(mode.endSameAsBegin)) {
					mode.end = mode.begin;
				}
				if ((mode.end == null || mode.end.isEmpty()) && !Mode.is(mode.endsWithParent)) {
					mode.end = "\\B|\\b";
				}
				if (mode.end != null && !mode.end.isEmpty()) {
					mode.endRe = langRe(mode.end);
				}
				mode.terminatorEnd = mode.end == null ? "" : mode.end;
				if (Mode.is(mode.endsWithParent) && parent.terminatorEnd != null && !parent.terminatorEnd.isEmpty()) {
					mode.terminatorEnd += (mode.end != null && !mode.end.isEmpty() ? "|" : "") + parent.terminatorEnd;
				}
			}
			if (mode.illegal != null && !mode.illegal.isEmpty()) {
				mode.illegalRe = langRe(mode.illegal);
			}
			if (mode.relevance == null) {
				mode.relevance = 1;
			}

			List<Mode> expanded = new ArrayList<>();
			if (mode.contains != null) {
				for (Mode c : mode.contains) {
					expanded.addAll(expandOrCloneMode(c == Mode.SELF ? mode : c));
				}
			}
			mode.contains = expanded;
			for (Mode c : mode.contains) {
				compileMode(c, mode);
			}

			if (mode.starts != null) {
				compileMode(mode.starts, parent);
			}

			mode.terminators = new Terminators(mode, caseInsensitive);
		}

		private static boolean dependencyOnParent(Mode mode) {
			while (mode != null) {
				if (Mode.is(mode.endsWithParent)) {
					return true;
				}
				mode = mode.starts;
			}
			return false;
		}

		private static List<Mode> expandOrCloneMode(Mode mode) {
			if (mode.variants != null && !mode.variants.isEmpty() && mode.cachedVariants == null) {
				// build the list before assigning it, so the variants don't inherit a partial list
				List<Mode> cachedVariants = new ArrayList<>();
				for (Mode variant : mode.variants) {
					cachedVariants.add(Hljs.merge(mode, new Mode().variants((List<Mode>) null), variant));
				}
				mode.cachedVariants = cachedVariants;
			}

			// EXPAND
			// if we have variants then essentially "replace" the mode with the variants
			// this happens in compileMode, where this function is called from
			if (mode.cachedVariants != null) {
				return mode.cachedVariants;
			}

			// CLONE
			// if we have dependencies on parents then we need a unique
			// instance of ourselves, so we can be reused with many
			// different parents without issue
			if (dependencyOnParent(mode)) {
				return List.of(Hljs.merge(mode, new Mode().starts(mode.starts != null ? Hljs.merge(mode.starts) : null)));
			}

			// no special dependency issues, just return ourselves
			return List.of(mode);
		}

		private Map<String, Mode.Keyword> compileKeywords(Object rawKeywords) {
			Map<String, Mode.Keyword> compiled = new HashMap<>();
			if (rawKeywords instanceof String s) {
				splitAndCompile("keyword", s, compiled);
			} else {
				for (Map.Entry<?, ?> e : ((Map<?, ?>) rawKeywords).entrySet()) {
					splitAndCompile((String) e.getKey(), String.valueOf(e.getValue()), compiled);
				}
			}
			return compiled;
		}

		private void splitAndCompile(String className, String str, Map<String, Mode.Keyword> compiled) {
			if (caseInsensitive) {
				str = str.toLowerCase(Locale.ROOT);
			}
			for (String keyword : str.split(" ", -1)) {
				String[] pair = keyword.split("\\|", -1);
				String providedScore = pair.length > 1 ? pair[1] : null;
				compiled.put(pair[0], new Mode.Keyword(className, scoreForKeyword(pair[0], providedScore)));
			}
		}

		private static int scoreForKeyword(String keyword, String providedScore) {
			// manual scores always win over common keywords, also a score of "0" (which is truthy in JS)
			if (providedScore != null && !providedScore.isEmpty()) {
				try {
					return Integer.parseInt(providedScore.trim());
				} catch (NumberFormatException e) {
					return 0;
				}
			}
			return COMMON_KEYWORDS.contains(keyword.toLowerCase(Locale.ROOT)) ? 0 : 1;
		}
	}
}
