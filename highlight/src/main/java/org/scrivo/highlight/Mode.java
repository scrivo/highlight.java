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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A mode of a language definition, the Java counterpart of a mode object in a
 * highlight.js language definition.
 * <p>
 * The public fields carry the same names and meaning as their highlight.js
 * counterparts and can be read and written directly, which is what the
 * generated language definitions do when they build upon other modes. The
 * fluent setters of the same names make it convenient to define modes:
 *
 * <pre>
 * new Mode()
 * 	.className("string")
 * 	.begin("'")
 * 	.end("'")
 * 	.contains(hljs.BACKSLASH_ESCAPE);
 * </pre>
 *
 * Regular expressions ({@link #begin}, {@link #end}, {@link #illegal} and
 * {@link #lexemes}) use JavaScript syntax, they are translated when the
 * language is compiled.
 * <p>
 * Like in JavaScript, a property can be present with a {@code null} value
 * (e.g. {@code hljs.inherit(hljs.QUOTE_STRING_MODE, new Mode().illegal(null))}
 * removes the illegal pattern of the inherited mode). Setting a property to
 * null through its setter records this, so {@link Hljs#inherit} can honor it.
 *
 * @see <a href="https://highlightjs.readthedocs.io/en/latest/reference.html">Mode reference</a>
 */
public class Mode {

	/**
	 * Stands for {@code 'self'} in a list of contained modes: the mode
	 * containing the list.
	 */
	public static final Mode SELF = new Mode();

	// ---- language properties (only used on the top level mode)

	/** The human readable name of the language. */
	public String name;

	/** Other names of the language. */
	public List<String> aliases;

	/** Whether the keywords and regular expressions are case insensitive. */
	public Boolean case_insensitive;

	/** Whether the language is excluded from auto-detection. */
	public Boolean disableAutodetect;

	// ---- mode properties

	public String className;
	public String begin;
	public String end;
	public String beginKeywords;
	public Boolean endsWithParent;
	public Boolean endsParent;
	public Boolean endSameAsBegin;
	public String lexemes;

	/** The keywords: a string or a {@code Map<String, String>} of keyword class names to keywords. */
	public Object keywords;

	public String illegal;
	public Boolean excludeBegin;
	public Boolean excludeEnd;
	public Boolean returnBegin;
	public Boolean returnEnd;
	public List<Mode> contains;
	public Mode starts;
	public List<Mode> variants;
	public Integer relevance;

	/** The sub-language: a language name, or a {@code List<String>} of languages to detect from. */
	public Object subLanguage;

	public Boolean skip;

	/**
	 * Called when the mode begins ({@code on:begin} in highlight.js); the callback
	 * can ignore the match with {@link Response#ignoreMatch()}.
	 */
	public Callback onBegin;

	/**
	 * Called when the mode ends ({@code on:end} in highlight.js); the callback can
	 * ignore the match with {@link Response#ignoreMatch()}.
	 */
	public Callback onEnd;

	/** Properties that are not known to the highlighter, kept for language definitions to share data. */
	public Map<String, Object> extra;

	/** The names of the properties that were explicitly set to null. */
	private Set<String> nulls;

	// ---- compiled state, set when the language is compiled

	boolean compiled;
	Map<String, Keyword> compiledKeywords;
	Pattern keywordPatternRe;
	/** The internal callback called before {@link #onBegin} ({@code __beforeBegin} in highlight.js). */
	Callback beforeBegin;

	Pattern beginRe;
	Pattern endRe;
	Pattern illegalRe;
	String terminatorEnd;
	Terminators terminators;
	List<Mode> cachedVariants;

	/** A compiled keyword: its class name and relevance. */
	record Keyword(String className, int relevance) {
	}

	/** A callback of a mode, see {@link #onBegin} and {@link #onEnd}. */
	@FunctionalInterface
	public interface Callback {
		/**
		 * @param match    the match of the begin or end pattern
		 * @param response to ignore the match, or to keep data between callbacks
		 */
		void call(Match match, Response response);
	}

	// ---- fluent setters

	public Mode name(String value) {
		name = record("name", value);
		return this;
	}

	public Mode aliases(String... values) {
		return aliases(new ArrayList<>(Arrays.asList(values)));
	}

	public Mode aliases(List<String> values) {
		aliases = record("aliases", values);
		return this;
	}

	public Mode case_insensitive(Boolean value) {
		case_insensitive = record("case_insensitive", value);
		return this;
	}

	public Mode disableAutodetect(Boolean value) {
		disableAutodetect = record("disableAutodetect", value);
		return this;
	}

	public Mode className(String value) {
		className = record("className", value);
		return this;
	}

	public Mode begin(String value) {
		begin = record("begin", value);
		return this;
	}

	public Mode end(String value) {
		end = record("end", value);
		return this;
	}

	public Mode beginKeywords(String value) {
		beginKeywords = record("beginKeywords", value);
		return this;
	}

	public Mode endsWithParent(Boolean value) {
		endsWithParent = record("endsWithParent", value);
		return this;
	}

	public Mode endsParent(Boolean value) {
		endsParent = record("endsParent", value);
		return this;
	}

	public Mode endSameAsBegin(Boolean value) {
		endSameAsBegin = record("endSameAsBegin", value);
		return this;
	}

	public Mode lexemes(String value) {
		lexemes = record("lexemes", value);
		return this;
	}

	/**
	 * @param value space separated keywords, optionally followed by {@code |}
	 *              and a relevance, as in {@code "if else|0"}
	 */
	public Mode keywords(String value) {
		keywords = record("keywords", value);
		return this;
	}

	/**
	 * @param value keyword class names (like {@code "keyword"} or
	 *              {@code "built_in"}) mapped to space separated keywords
	 */
	public Mode keywords(Map<String, String> value) {
		keywords = record("keywords", value);
		return this;
	}

	public Mode illegal(String value) {
		illegal = record("illegal", value);
		return this;
	}

	public Mode excludeBegin(Boolean value) {
		excludeBegin = record("excludeBegin", value);
		return this;
	}

	public Mode excludeEnd(Boolean value) {
		excludeEnd = record("excludeEnd", value);
		return this;
	}

	public Mode returnBegin(Boolean value) {
		returnBegin = record("returnBegin", value);
		return this;
	}

	public Mode returnEnd(Boolean value) {
		returnEnd = record("returnEnd", value);
		return this;
	}

	/** Sets the contained modes to a new list holding the given modes, use {@link #SELF} for {@code 'self'}. */
	public Mode contains(Mode... values) {
		return contains(new ArrayList<>(Arrays.asList(values)));
	}

	/** Sets the contained modes to the given list, which is not copied. */
	public Mode contains(List<Mode> values) {
		contains = record("contains", values);
		return this;
	}

	public Mode starts(Mode value) {
		starts = record("starts", value);
		return this;
	}

	public Mode variants(Mode... values) {
		return variants(new ArrayList<>(Arrays.asList(values)));
	}

	public Mode variants(List<Mode> values) {
		variants = record("variants", values);
		return this;
	}

	public Mode relevance(Integer value) {
		relevance = record("relevance", value);
		return this;
	}

	public Mode subLanguage(String value) {
		subLanguage = record("subLanguage", value);
		return this;
	}

	public Mode subLanguage(List<String> value) {
		subLanguage = record("subLanguage", value);
		return this;
	}

	public Mode skip(Boolean value) {
		skip = record("skip", value);
		return this;
	}

	/** Sets the {@code on:begin} callback. */
	public Mode onBegin(Callback value) {
		onBegin = record("on:begin", value);
		return this;
	}

	/** Sets the {@code on:end} callback. */
	public Mode onEnd(Callback value) {
		onEnd = record("on:end", value);
		return this;
	}

	/**
	 * Sets a property by name, as a property assignment would in JavaScript.
	 * Properties not known to the highlighter are stored in {@link #extra}.
	 *
	 * @throws ClassCastException if the value has the wrong type for a known property
	 */
	@SuppressWarnings("unchecked")
	public Mode set(String key, Object value) {
		switch (key) {
		case "name" -> name((String) value);
		case "aliases" -> aliases((List<String>) value);
		case "case_insensitive" -> case_insensitive((Boolean) value);
		case "disableAutodetect" -> disableAutodetect((Boolean) value);
		case "className" -> className((String) value);
		case "begin" -> begin((String) value);
		case "end" -> end((String) value);
		case "beginKeywords" -> beginKeywords((String) value);
		case "endsWithParent" -> endsWithParent((Boolean) value);
		case "endsParent" -> endsParent((Boolean) value);
		case "endSameAsBegin" -> endSameAsBegin((Boolean) value);
		case "lexemes" -> lexemes((String) value);
		case "keywords" -> keywords = record(key, checkKeywords(value));
		case "illegal" -> illegal((String) value);
		case "excludeBegin" -> excludeBegin((Boolean) value);
		case "excludeEnd" -> excludeEnd((Boolean) value);
		case "returnBegin" -> returnBegin((Boolean) value);
		case "returnEnd" -> returnEnd((Boolean) value);
		case "contains" -> contains((List<Mode>) value);
		case "starts" -> starts((Mode) value);
		case "variants" -> variants((List<Mode>) value);
		case "relevance" -> relevance((Integer) value);
		case "subLanguage" -> subLanguage = record(key, checkSubLanguage(value));
		case "skip" -> skip((Boolean) value);
		case "on:begin" -> onBegin((Callback) value);
		case "on:end" -> onEnd((Callback) value);
		default -> {
			if (extra == null) {
				extra = new LinkedHashMap<>();
			}
			extra.put(key, value);
		}
		}
		return this;
	}

	/**
	 * Reads a property by name, as a property access would in JavaScript.
	 *
	 * @return the value, or null when the property is not set
	 */
	public Object get(String key) {
		return switch (key) {
		case "name" -> name;
		case "aliases" -> aliases;
		case "case_insensitive" -> case_insensitive;
		case "disableAutodetect" -> disableAutodetect;
		case "className" -> className;
		case "begin" -> begin;
		case "end" -> end;
		case "beginKeywords" -> beginKeywords;
		case "endsWithParent" -> endsWithParent;
		case "endsParent" -> endsParent;
		case "endSameAsBegin" -> endSameAsBegin;
		case "lexemes" -> lexemes;
		case "keywords" -> keywords;
		case "illegal" -> illegal;
		case "excludeBegin" -> excludeBegin;
		case "excludeEnd" -> excludeEnd;
		case "returnBegin" -> returnBegin;
		case "returnEnd" -> returnEnd;
		case "contains" -> contains;
		case "starts" -> starts;
		case "variants" -> variants;
		case "relevance" -> relevance;
		case "subLanguage" -> subLanguage;
		case "skip" -> skip;
		case "on:begin" -> onBegin;
		case "on:end" -> onEnd;
		default -> extra == null ? null : extra.get(key);
		};
	}

	/**
	 * The names of the properties of this mode, in a fixed order: the ones that
	 * have a value and the ones that were explicitly set to null.
	 */
	public List<String> keys() {
		List<String> keys = new ArrayList<>();
		for (String key : PROPERTIES) {
			if (get(key) != null || (nulls != null && nulls.contains(key))) {
				keys.add(key);
			}
		}
		if (extra != null) {
			keys.addAll(extra.keySet());
		}
		return keys;
	}

	/** Whether a property is present, either with a value or explicitly set to null. */
	public boolean has(String key) {
		return get(key) != null || (nulls != null && nulls.contains(key))
				|| (extra != null && extra.containsKey(key));
	}

	private static final List<String> PROPERTIES = List.of("name", "aliases", "case_insensitive",
			"disableAutodetect", "className", "begin", "end", "beginKeywords", "endsWithParent", "endsParent",
			"endSameAsBegin", "lexemes", "keywords", "illegal", "excludeBegin", "excludeEnd", "returnBegin",
			"returnEnd", "contains", "starts", "variants", "relevance", "subLanguage", "skip", "on:begin", "on:end");

	/** Copies the properties of another mode into this one (a shallow copy, like JavaScript's object spread). */
	void assign(Mode other) {
		for (String key : other.keys()) {
			set(key, other.get(key));
		}
	}

	private <T> T record(String key, T value) {
		if (value == null) {
			if (nulls == null) {
				nulls = new HashSet<>();
			}
			nulls.add(key);
		} else if (nulls != null) {
			nulls.remove(key);
		}
		return value;
	}

	private static Object checkKeywords(Object value) {
		if (value == null || value instanceof String || value instanceof Map) {
			return value;
		}
		throw new ClassCastException("keywords must be a String or a Map, got " + value.getClass().getName());
	}

	private static Object checkSubLanguage(Object value) {
		if (value == null || value instanceof String || value instanceof List) {
			return value;
		}
		throw new ClassCastException("subLanguage must be a String or a List, got " + value.getClass().getName());
	}

	static boolean is(Boolean value) {
		return value != null && value;
	}
}
