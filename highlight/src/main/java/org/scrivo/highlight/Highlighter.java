/* Copyright (c)
 * - 2006-2013, Ivan Sagalaev (maniac@softwaremaniacs.org), highlight.js
 *              (original author)
 * - 2013-2026, Geert Bergman (geert@scrivo.nl), highlight.php, highlight.java
 * - 2014       Daniel Lynge, highlight.php (contributor)
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
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.scrivo.highlight.languages.Registry;

/**
 * A syntax highlighter, the Java port of highlight.js.
 *
 * <pre>
 * Highlighter hl = new Highlighter();
 * HighlightResult result = hl.highlight("java", code);
 * String html = "&lt;pre&gt;&lt;code class=\"hljs " + result.getLanguage() + "\"&gt;" + result.getValue() + "&lt;/code&gt;&lt;/pre&gt;";
 * </pre>
 *
 * Languages are registered globally (statically), so they are available to all
 * instances. A Highlighter can be used by several threads at once, as long as
 * its options are not changed meanwhile.
 */
public class Highlighter {

	public static final String SPAN_END_TAG = "</span>";

	/** A mapping of a language ID to a Language definition. */
	private static final Map<String, Language> classMap = new ConcurrentHashMap<>();

	/** A list of registered language IDs. */
	private static final LinkedHashSet<String> languages = new LinkedHashSet<>();

	/** A mapping from alias (key) to main language ID (value). */
	private static final Map<String, String> aliases = new ConcurrentHashMap<>();

	private boolean safeMode = true;
	private String classPrefix = "hljs-";
	private String tabReplace;
	private List<String> autodetectLanguages;

	/**
	 * Creates a highlighter and registers all languages bundled with this
	 * library.
	 */
	public Highlighter() {
		this(true);
	}

	/**
	 * @param loadAllLanguages If true, will automatically register all languages
	 *                         distributed with this library. If false, user must
	 *                         explicitly register languages by calling
	 *                         {@link #registerLanguage}.
	 */
	public Highlighter(boolean loadAllLanguages) {
		if (loadAllLanguages) {
			registerAllLanguages();
		}
	}

	// ---- language registry

	/**
	 * Returns a list of all available languages bundled with this library.
	 */
	public static List<String> listBundledLanguages() {
		// Languages that take precedence (they win ties in auto-detection), as in highlight.php
		LinkedHashSet<String> bundled = new LinkedHashSet<>(List.of("xml", "django", "javascript", "matlab", "cpp"));
		bundled.addAll(Registry.LANGUAGES.keySet());
		return new ArrayList<>(bundled);
	}

	/**
	 * Returns a list of all the registered languages. Using this list in
	 * {@link #setAutodetectLanguages} will turn on auto-detection for all
	 * registered languages.
	 *
	 * @param includeAliases whether language aliases should be included as well
	 */
	public static synchronized List<String> listRegisteredLanguages(boolean includeAliases) {
		List<String> result = new ArrayList<>(languages);
		if (includeAliases) {
			result.addAll(aliases.keySet());
		}
		return result;
	}

	/** Returns a list of all the registered languages, without aliases. */
	public static List<String> listRegisteredLanguages() {
		return listRegisteredLanguages(false);
	}

	/**
	 * Registers all languages that are bundled with this library. To register
	 * languages individually, use {@link #registerLanguage}.
	 */
	public static synchronized void registerAllLanguages() {
		for (String id : listBundledLanguages()) {
			if (!classMap.containsKey(id)) {
				registerLanguage(id, Registry.LANGUAGES.get(id).get());
			}
		}
	}

	/**
	 * Registers a language definition, unless a language with this id was
	 * registered already. Languages are registered statically, so they are
	 * available to all instances.
	 *
	 * @param id         the unique name of a language
	 * @param definition the language definition
	 * @return the registered language
	 */
	public static Language registerLanguage(String id, LanguageDefinition definition) {
		return registerLanguage(id, definition, false);
	}

	/**
	 * Registers a language definition.
	 *
	 * @param id         the unique name of a language
	 * @param definition the language definition
	 * @param overwrite  overwrite the language if it already exists
	 * @return the registered language
	 */
	public static synchronized Language registerLanguage(String id, LanguageDefinition definition,
			boolean overwrite) {
		Language existing = classMap.get(id);
		if (existing != null && !overwrite) {
			return existing;
		}

		Language lang = new Language(id, definition, Highlighter::getLanguage);
		classMap.put(id, lang);
		languages.add(id);

		// Bundled languages come with their aliases so that registering them does not require evaluating them
		List<String> languageAliases = Registry.isBundled(id, definition) ? Registry.ALIASES.get(id)
				: lang.getAliases();
		for (String alias : languageAliases) {
			aliases.put(alias, id);
		}
		return lang;
	}

	/** Clears all registered languages. */
	/**
	 * Registers aliases for a language.
	 *
	 * @param aliasList    the aliases
	 * @param languageName the id of the language
	 */
	public static synchronized void registerAliases(List<String> aliasList, String languageName) {
		for (String alias : aliasList) {
			aliases.put(alias.toLowerCase(Locale.ROOT), languageName);
		}
	}

	/**
	 * Registers an alias for a language.
	 *
	 * @param alias        the alias
	 * @param languageName the id of the language
	 */
	public static void registerAliases(String alias, String languageName) {
		registerAliases(List.of(alias), languageName);
	}

	public static synchronized void clearAllLanguages() {
		classMap.clear();
		languages.clear();
		aliases.clear();
	}

	/**
	 * Finds a registered language.
	 *
	 * @param name a language name or alias
	 * @return the language, or null when there is no such language
	 */
	public static Language getLanguage(String name) {
		if (name == null) {
			return null;
		}
		name = name.toLowerCase(Locale.ROOT);
		Language language = classMap.get(name);
		if (language == null) {
			String id = aliases.get(name);
			language = id == null ? null : classMap.get(id);
		}
		return language;
	}

	/**
	 * Returns the list of all available aliases for the given language name.
	 *
	 * @param name name or alias of language to look-up
	 * @return the language id followed by its aliases
	 * @throws IllegalArgumentException if the language is not registered
	 */
	public List<String> getAliasesForLanguage(String name) {
		Language language = getLanguage(name);
		if (language == null) {
			throw new IllegalArgumentException("Unknown language: \"" + name + "\"");
		}
		List<String> result = new ArrayList<>();
		result.add(language.getId());
		result.addAll(language.getAliases());
		return result;
	}

	// ---- options

	/**
	 * Sets the languages that will be used for auto-detection. When using
	 * auto-detection the code to highlight will be probed for every language in
	 * this set. Limiting this set to only the languages you want to use will
	 * greatly improve highlighting speed.
	 *
	 * @param set the language names to use for auto-detection, or null to use
	 *            all registered languages (the default)
	 */
	public void setAutodetectLanguages(List<String> set) {
		autodetectLanguages = set == null ? null : List.copyOf(new LinkedHashSet<>(set));
	}

	/** The languages used for auto-detection, or null when all registered languages are used. */
	public List<String> getAutodetectLanguages() {
		return autodetectLanguages;
	}

	/** The tab replacement string, null means that tabs are not replaced. */
	public String getTabReplace() {
		return tabReplace;
	}

	/**
	 * Sets the tab replacement string. This defaults to null: no tabs will be
	 * replaced.
	 */
	public void setTabReplace(String tabReplace) {
		this.tabReplace = tabReplace;
	}

	/** The prefix of the CSS class names, {@code "hljs-"} by default. */
	public String getClassPrefix() {
		return classPrefix;
	}

	public void setClassPrefix(String classPrefix) {
		this.classPrefix = classPrefix;
	}

	/**
	 * In safe mode (the default) errors in language definitions don't raise
	 * exceptions, the code is returned escaped instead.
	 */
	public void enableSafeMode() {
		safeMode = true;
	}

	public void disableSafeMode() {
		safeMode = false;
	}

	public boolean isSafeMode() {
		return safeMode;
	}

	// ---- highlighting

	/**
	 * Highlights code in the given language, ignoring illegal lexemes.
	 *
	 * @param languageName a language name or alias
	 * @param code         the code to highlight
	 * @throws IllegalArgumentException if the language is not registered
	 */
	public HighlightResult highlight(String languageName, String code) {
		return highlight(languageName, code, true);
	}

	/**
	 * Highlights code in the given language.
	 *
	 * @param languageName   a language name or alias
	 * @param code           the code to highlight
	 * @param ignoreIllegals when false, the code is not highlighted when an
	 *                       illegal lexeme is found ({@link HighlightResult#isIllegal()})
	 * @throws IllegalArgumentException if the language is not registered
	 */
	public HighlightResult highlight(String languageName, String code, boolean ignoreIllegals) {
		return highlight(languageName, code, ignoreIllegals, null);
	}

	HighlightResult highlight(String languageName, String code, boolean ignoreIllegals, Frame continuation) {
		Language language = getLanguage(languageName);
		if (language == null) {
			throw new IllegalArgumentException("Unknown language: \"" + languageName + "\"");
		}
		language.compile(safeMode);
		return new Run(language, code, ignoreIllegals).highlight(continuation);
	}

	/**
	 * Highlights the code with each of the auto-detection languages and returns
	 * the result with the highest relevance.
	 */
	public HighlightResult highlightAuto(String code) {
		return highlightAuto(code, null);
	}

	/**
	 * Highlights the code with each of the given languages and returns the
	 * result with the highest relevance.
	 *
	 * @param languageSubset the languages to try, null for the auto-detection
	 *                       languages
	 */
	public HighlightResult highlightAuto(String code, List<String> languageSubset) {
		if (languageSubset == null) {
			languageSubset = autodetectLanguages != null && !autodetectLanguages.isEmpty() ? autodetectLanguages
					: listRegisteredLanguages();
		}

		HighlightResult plaintext = new HighlightResult("", 0, escape(code), false, null, null);

		List<HighlightResult> results = new ArrayList<>();
		results.add(plaintext); // plaintext is always an option
		for (String name : languageSubset) {
			Language language = getLanguage(name);
			if (language == null || language.isDisableAutodetect()) {
				continue;
			}
			results.add(highlight(name, code, false));
		}

		// a stable sort, so that ties keep the order of the languages
		for (int i = 1; i < results.size(); i++) {
			HighlightResult current = results.get(i);
			int j = i;
			while (j > 0 && compareAutoResults(results.get(j - 1), current) > 0) {
				results.set(j, results.get(j - 1));
				j--;
			}
			results.set(j, current);
		}

		HighlightResult result = results.get(0);
		if (results.size() > 1) {
			result.setSecondBest(results.get(1));
		}
		return result;
	}

	/**
	 * Orders the results of auto-detection: on relevance, and a tie is awarded to
	 * the base language (like C++ over Arduino). Otherwise they are equal, so the
	 * language that comes first wins a tie, as it historically did.
	 */
	private static int compareAutoResults(HighlightResult a, HighlightResult b) {
		if (a.getRelevance() != b.getRelevance()) {
			return Integer.compare(b.getRelevance(), a.getRelevance());
		}

		// always award the tie to the base language
		// ie if C++ and Arduino are tied, it's more likely to be C++
		// (highlight.js results for illegal code have no language)
		String aLanguage = a.isIllegal() ? "" : a.getLanguage();
		String bLanguage = b.isIllegal() ? "" : b.getLanguage();
		if (!aLanguage.isEmpty() && !bLanguage.isEmpty()) {
			Language aLang = getLanguage(aLanguage);
			Language bLang = getLanguage(bLanguage);
			if (aLang != null && bLanguage.equals(aLang.getSupersetOf())) {
				return 1;
			} else if (bLang != null && aLanguage.equals(bLang.getSupersetOf())) {
				return -1;
			}
		}
		return 0;
	}

	static String escape(String value) {
		StringBuilder sb = null;
		int len = value.length();
		for (int i = 0; i < len; i++) {
			char ch = value.charAt(i);
			String replacement = switch (ch) {
			case '&' -> "&amp;";
			case '<' -> "&lt;";
			case '>' -> "&gt;";
			case '"' -> "&quot;";
			case '\'' -> "&#x27;";
			default -> null;
			};
			if (replacement != null) {
				if (sb == null) {
					sb = new StringBuilder(len + 16);
					sb.append(value, 0, i);
				}
				sb.append(replacement);
			} else if (sb != null) {
				sb.append(ch);
			}
		}
		return sb == null ? value : sb.toString();
	}

	/**
	 * An entry of the mode stack: a mode, the mode it is nested in and the end
	 * pattern for this occurrence of the mode (which differs from the end pattern
	 * of the mode when it ends with the same text as it began with).
	 */
	static final class Frame {
		final Mode mode;
		final Frame parent;
		final Pattern endRe;

		Frame(Mode mode, Frame parent, Pattern endRe) {
			this.mode = mode;
			this.parent = parent;
			this.endRe = endRe;
		}
	}

	private static final class IllegalLexemeException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		IllegalLexemeException(String message) {
			super(message, null, false, false);
		}
	}

	/** The state of highlighting a piece of code. */
	private final class Run {
		private final Language language;
		private final String codeToHighlight;
		private final boolean ignoreIllegals;
		private final StringBuilder result = new StringBuilder();
		private final StringBuilder modeBuffer = new StringBuilder();
		private final Map<String, Frame> continuations = new HashMap<>();
		private Frame top;
		/** The relevance, which may be a fraction (keyword scores like "1.5"); rounded down in the result. */
		private double relevance;
		private Match lastMatch;
		/** The number of matches processed, to detect infinite loops. */
		private int iterations;
		/** The first rule of the terminators of the current mode to consider. */
		private int regexIndex;
		/** Whether to continue at the same position, with the rules after the one whose match was ignored. */
		private boolean continueScanAtSamePosition;
		/** The data of the callbacks of each mode. */
		private final Map<Mode, Map<String, Object>> callbackData = new IdentityHashMap<>();

		Run(Language language, String code, boolean ignoreIllegals) {
			this.language = language;
			this.codeToHighlight = code;
			this.ignoreIllegals = ignoreIllegals;
		}

		HighlightResult highlight(Frame continuation) {
			top = continuation != null ? continuation : new Frame(language.getMode(), null, null);

			// reopen the spans of the continued modes
			List<String> classNames = new ArrayList<>();
			for (Frame current = top; current.parent != null; current = current.parent) {
				if (current.mode.className != null && !current.mode.className.isEmpty()) {
					classNames.add(0, current.mode.className);
				}
			}
			for (String className : classNames) {
				result.append(buildSpan(className, "", true, false));
			}

			try {
				int index = 0;
				while (true) {
					iterations++;
					if (continueScanAtSamePosition) {
						// only regexes not matched previously will now be
						// considered for a potential match
						continueScanAtSamePosition = false;
					} else {
						regexIndex = 0;
					}
					Terminators terminators = top.mode.terminators;
					int resumeFrom = regexIndex;
					Match match = terminators.exec(codeToHighlight, index, resumeFrom);
					int matcherStart = resumeFrom;

					// When a match was ignored, the scan resumes at the same position with the rules
					// after the ignored one. There is no easy way to say "resume scanning at the
					// existing position but also skip the current rule ONLY": a resumed scan skips all
					// prior rules too, and could scan ahead past matches of those rules. So, like
					// highlight.js, a second full scan starts at the position after: the resumed scan
					// is only used when it matches at the same position, otherwise the full scan is.
					if (resumeFrom != 0 && (match == null || match.index() != index)) {
						match = terminators.exec(codeToHighlight, index + 1, 0);
						matcherStart = 0;
					}
					if (match == null) {
						break;
					}
					if (match.position >= 0) {
						// like highlight.js, the position of the rule is relative to the scan that found it
						regexIndex = resumeFrom + (match.position - matcherStart) + 1;
						if (regexIndex == terminators.count()) {
							// wrap-around to considering all matches again
							regexIndex = 0;
						}
					}
					int count = processLexeme(codeToHighlight.substring(index, match.index()), match);
					index = match.index() + count;
				}
				processLexeme(codeToHighlight.substring(Math.min(index, codeToHighlight.length())), null);

				for (Frame current = top; current.parent != null; current = current.parent) {
					if (current.mode.className != null && !current.mode.className.isEmpty()) {
						result.append(SPAN_END_TAG);
					}
				}

				return new HighlightResult(language.getId(), (int) Math.floor(relevance), replaceTabs(result.toString()), false, null,
						top);
			} catch (IllegalLexemeException e) {
				return new HighlightResult(language.getId(), 0, escape(codeToHighlight), true, null, null);
			} catch (RuntimeException | StackOverflowError e) {
				if (safeMode) {
					return new HighlightResult(language.getId(), 0, escape(codeToHighlight), false, e, top);
				}
				throw e;
			}
		}

		private String replaceTabs(String code) {
			return tabReplace != null ? code.replace("\t", tabReplace) : code;
		}

		/**
		 * Whether the regex matches the code at the index. Like highlight.js, which
		 * matches against the remainder of the code, the code before the index is
		 * not visible to the regex (the region bounds are opaque).
		 */
		private boolean testRe(Pattern re, int index) {
			if (re == null) {
				return false;
			}
			Matcher m = re.matcher(codeToHighlight);
			m.region(index, codeToHighlight.length());
			return m.lookingAt();
		}

		private Response response(Mode mode) {
			return new Response(callbackData.computeIfAbsent(mode, m -> new HashMap<>()));
		}

		private Frame endOfMode(Frame frame, Match match) {
			boolean matched = testRe(frame.endRe != null ? frame.endRe : frame.mode.endRe, match.index());

			if (matched) {
				if (frame.mode.onEnd != null) {
					Response resp = response(frame.mode);
					frame.mode.onEnd.call(match, resp);
					if (resp.isIgnored()) {
						matched = false;
					}
				}

				if (matched) {
					while (Mode.is(frame.mode.endsParent) && frame.parent != null) {
						frame = frame.parent;
					}
					return frame;
				}
			}
			// even if on:end fires an `ignore` it's still possible
			// that we might trigger the end node because of a parent mode
			if (Mode.is(frame.mode.endsWithParent)) {
				return endOfMode(frame.parent, match);
			}
			return null;
		}

		private int doIgnore(String lexeme) {
			if (regexIndex == 0) {
				// no more regexs to potentially match here, so we move the cursor forward one
				// space
				if (!lexeme.isEmpty()) {
					modeBuffer.append(lexeme.charAt(0));
				}
				return 1;
			} else {
				// no need to move the cursor, we still have additional regexes to try and
				// match at this very spot
				continueScanAtSamePosition = true;
				return 0;
			}
		}

		private String buildSpan(String className, String insideSpan, boolean leaveOpen, boolean noPrefix) {
			if (!leaveOpen && insideSpan.isEmpty()) {
				return "";
			}
			if (className == null || className.isEmpty()) {
				return insideSpan;
			}
			return "<span class=\"" + (noPrefix ? "" : classPrefix) + className + "\">" + insideSpan
					+ (leaveOpen ? "" : SPAN_END_TAG);
		}

		private void processKeywords() {
			Mode mode = top.mode;
			String buffer = modeBuffer.toString();
			if (mode.compiledKeywords == null) {
				result.append(escape(buffer));
				return;
			}

			int lastIndex = 0;
			Matcher m = mode.keywordPatternRe.matcher(buffer);
			boolean caseInsensitive = language.isCaseInsensitive();
			while (m.find()) {
				result.append(escape(buffer.substring(lastIndex, m.start())));
				String lexeme = m.group();
				Mode.Keyword keyword = mode.compiledKeywords
						.get(caseInsensitive ? lexeme.toLowerCase(Locale.ROOT) : lexeme);
				if (keyword != null) {
					relevance += keyword.relevance();
					result.append(buildSpan(language.classNameAlias(keyword.className()), escape(lexeme), false, false));
				} else {
					result.append(escape(lexeme));
				}
				lastIndex = m.end();
			}
			result.append(escape(buffer.substring(lastIndex)));
		}

		private void processSubLanguage() {
			Mode mode = top.mode;
			String buffer = modeBuffer.toString();
			if (buffer.isEmpty()) {
				return;
			}

			Highlighter hl = new Highlighter(false);
			hl.autodetectLanguages = autodetectLanguages;
			hl.classPrefix = classPrefix;
			hl.safeMode = safeMode;

			HighlightResult res;
			if (mode.subLanguage instanceof String subLanguage) {
				if (!classMap.containsKey(subLanguage)) {
					result.append(escape(buffer));
					return;
				}
				res = hl.highlight(subLanguage, buffer, true, continuations.get(subLanguage));
				continuations.put(subLanguage, res.top);
			} else {
				@SuppressWarnings("unchecked")
				List<String> subset = (List<String>) mode.subLanguage;
				res = hl.highlightAuto(buffer, subset.isEmpty() ? null : subset);
			}

			// Counting embedded language score towards the host language may be disabled
			// with zeroing the containing mode relevance. Use case in point is Markdown that
			// allows XML everywhere and makes every XML snippet to have a much larger Markdown
			// score.
			if (mode.relevance != null && mode.relevance > 0) {
				relevance += res.getRelevance();
			}

			result.append(buildSpan(res.getLanguage(), res.getValue(), false, true));
		}

		private void processBuffer() {
			if (top.mode.subLanguage != null) {
				processSubLanguage();
			} else {
				processKeywords();
			}
			modeBuffer.setLength(0);
		}

		private void startNewMode(Mode mode, Pattern endRe) {
			if (mode.className != null && !mode.className.isEmpty()) {
				result.append(buildSpan(language.classNameAlias(mode.className), "", true, false));
			}
			top = new Frame(mode, top, endRe);
		}

		private int doBeginMatch(Match match) {
			String lexeme = match.lexeme();
			Mode newMode = match.rule;

			// first internal before callbacks, then the public ones
			for (Mode.Callback cb : new Mode.Callback[] { newMode.beforeBegin, newMode.onBegin }) {
				if (cb == null) {
					continue;
				}
				Response resp = response(newMode);
				cb.call(match, resp);
				if (resp.isIgnored()) {
					return doIgnore(lexeme);
				}
			}

			Pattern endRe = null;
			if (Mode.is(newMode.endSameAsBegin)) {
				endRe = Pattern.compile(Pattern.quote(lexeme));
			}

			if (Mode.is(newMode.skip)) {
				modeBuffer.append(lexeme);
			} else {
				if (Mode.is(newMode.excludeBegin)) {
					modeBuffer.append(lexeme);
				}
				processBuffer();
				if (!Mode.is(newMode.returnBegin) && !Mode.is(newMode.excludeBegin)) {
					modeBuffer.setLength(0);
					modeBuffer.append(lexeme);
				}
			}
			startNewMode(newMode, endRe);

			return Mode.is(newMode.returnBegin) ? 0 : lexeme.length();
		}

		private Integer doEndMatch(Match match) {
			String lexeme = match.lexeme();
			Frame endMode = endOfMode(top, match);
			if (endMode == null) {
				return null;
			}

			Mode origin = top.mode;
			if (Mode.is(origin.skip)) {
				modeBuffer.append(lexeme);
			} else {
				if (!(Mode.is(origin.returnEnd) || Mode.is(origin.excludeEnd))) {
					modeBuffer.append(lexeme);
				}
				processBuffer();
				if (Mode.is(origin.excludeEnd)) {
					modeBuffer.append(lexeme);
				}
			}

			do {
				if (top.mode.className != null && !top.mode.className.isEmpty()) {
					result.append(SPAN_END_TAG);
				}
				if (!Mode.is(top.mode.skip) && top.mode.subLanguage == null) {
					relevance += top.mode.relevance;
				}
				top = top.parent;
			} while (top != endMode.parent);

			if (endMode.mode.starts != null) {
				startNewMode(endMode.mode.starts, Mode.is(endMode.mode.endSameAsBegin) ? endMode.endRe : null);
			}

			return Mode.is(origin.returnEnd) ? 0 : lexeme.length();
		}

		private int processLexeme(String textBeforeMatch, Match match) {
			// add non-matched text to the current mode buffer
			modeBuffer.append(textBeforeMatch);

			if (match == null) {
				processBuffer();
				return 0;
			}
			String lexeme = match.lexeme();

			// we've found a 0 width match and we're stuck, so we need to advance
			// this happens when we have badly behaved rules that have optional matchers to the degree that
			// sometimes they can end up matching nothing at all
			// Ref: https://github.com/highlightjs/highlight.js/issues/2140
			if (lastMatch != null && Terminators.BEGIN.equals(lastMatch.type) && Terminators.END.equals(match.type)
					&& lastMatch.index() == match.index() && lexeme.isEmpty()) {
				// spit the "skipped" character that our regex choked on back into the output sequence
				if (match.index() < codeToHighlight.length()) {
					modeBuffer.append(codeToHighlight.charAt(match.index()));
				}
				return 1;
			}
			lastMatch = match;

			if (Terminators.BEGIN.equals(match.type)) {
				return doBeginMatch(match);
			} else if (Terminators.ILLEGAL.equals(match.type) && !ignoreIllegals) {
				// illegal match, we do not continue processing
				String className = top.mode.className != null ? top.mode.className : "<unnamed>";
				throw new IllegalLexemeException("Illegal lexeme \"" + lexeme + "\" for mode \"" + className + "\"");
			} else if (Terminators.END.equals(match.type)) {
				Integer processed = doEndMatch(match);
				if (processed != null) {
					return processed;
				}
			}

			// edge case for when illegal matches $ (end of line) which is technically
			// a 0 width match but not a begin/end match so it's not caught by the
			// first handler (when ignoreIllegals is true)
			if (Terminators.ILLEGAL.equals(match.type) && lexeme.isEmpty()) {
				// advance so we aren't stuck in an infinite loop
				return 1;
			}

			// infinite loops are BAD, this is a last ditch catch all. if we have a
			// decent number of iterations yet our index (cursor position in our
			// parsing) still 3x behind our index then something is very wrong
			// so we bail
			if (iterations > 100000 && iterations > match.index() * 3) {
				throw new IllegalStateException("potential infinite loop, way more iterations than matches");
			}

			// Why might be find ourselves here? Only one occasion now. An end match that was
			// triggered but could not be completed. When might this happen? When an `endSameasBegin`
			// rule sets the end rule to a specific match. Since the overall mode termination rule that's
			// being used to scan the text isn't recompiled that means that any match that LOOKS like
			// the end (but is not, because it is not an exact match to the beginning) will
			// end up here. A definite end match, but when `doEndMatch` tries to "reapply"
			// the end rule and fails to match, we wind up here, and just silently ignore the end.
			//
			// This causes no real harm other than stopping a few times too many.

			modeBuffer.append(lexeme);
			return lexeme.length();
		}
	}
}
