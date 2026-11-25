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

		HighlightResult result = new HighlightResult("", 0, escape(code), false, null, null);
		HighlightResult secondBest = result;

		for (String name : languageSubset) {
			Language language = getLanguage(name);
			if (language == null || language.isDisableAutodetect()) {
				continue;
			}

			HighlightResult current = highlight(name, code, false);

			if (current.getRelevance() > secondBest.getRelevance()) {
				secondBest = current;
			}
			if (current.getRelevance() > result.getRelevance()) {
				secondBest = result;
				result = current;
			}
		}

		if (!secondBest.getLanguage().isEmpty()) {
			result.setSecondBest(secondBest);
		}
		return result;
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
		private int relevance;
		private Terminators.Match lastMatch;

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
					Terminators.Match match = top.mode.terminators.exec(codeToHighlight, index);
					if (match == null) {
						break;
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

				return new HighlightResult(language.getId(), relevance, replaceTabs(result.toString()), false, null,
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

		private Frame endOfMode(Frame frame, int index) {
			if (testRe(frame.endRe != null ? frame.endRe : frame.mode.endRe, index)) {
				while (Mode.is(frame.mode.endsParent) && frame.parent != null) {
					frame = frame.parent;
				}
				return frame;
			}
			if (Mode.is(frame.mode.endsWithParent)) {
				return endOfMode(frame.parent, index);
			}
			return null;
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
			Matcher m = mode.lexemesRe.matcher(buffer);
			boolean caseInsensitive = language.isCaseInsensitive();
			while (m.find()) {
				result.append(escape(buffer.substring(lastIndex, m.start())));
				String lexeme = m.group();
				Mode.Keyword keyword = mode.compiledKeywords
						.get(caseInsensitive ? lexeme.toLowerCase(Locale.ROOT) : lexeme);
				if (keyword != null) {
					relevance += keyword.relevance();
					result.append(buildSpan(keyword.className(), escape(lexeme), false, false));
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
				result.append(buildSpan(mode.className, "", true, false));
			}
			top = new Frame(mode, top, endRe);
		}

		private int doBeginMatch(Terminators.Match match) {
			String lexeme = match.lexeme();
			Mode newMode = match.rule();

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

		private Integer doEndMatch(Terminators.Match match) {
			String lexeme = match.lexeme();
			Frame endMode = endOfMode(top, match.index());
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

		private int processLexeme(String textBeforeMatch, Terminators.Match match) {
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
			if (lastMatch != null && Terminators.BEGIN.equals(lastMatch.type()) && Terminators.END.equals(match.type())
					&& lastMatch.index() == match.index() && lexeme.isEmpty()) {
				// spit the "skipped" character that our regex choked on back into the output sequence
				if (match.index() < codeToHighlight.length()) {
					modeBuffer.append(codeToHighlight.charAt(match.index()));
				}
				return 1;
			}
			lastMatch = match;

			if (Terminators.BEGIN.equals(match.type())) {
				return doBeginMatch(match);
			} else if (Terminators.ILLEGAL.equals(match.type()) && !ignoreIllegals) {
				// illegal match, we do not continue processing
				String className = top.mode.className != null ? top.mode.className : "<unnamed>";
				throw new IllegalLexemeException("Illegal lexeme \"" + lexeme + "\" for mode \"" + className + "\"");
			} else if (Terminators.END.equals(match.type())) {
				Integer processed = doEndMatch(match);
				if (processed != null) {
					return processed;
				}
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
