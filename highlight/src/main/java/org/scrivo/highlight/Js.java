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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers with JavaScript semantics, used by the language definitions that are
 * generated from highlight.js.
 * <p>
 * Lists and maps are mutable and shared by reference, like JavaScript arrays
 * and objects.
 */
public final class Js {

	private Js() {
	}

	/** An array literal: a new mutable list. */
	@SafeVarargs
	@SuppressWarnings("varargs")
	public static <T> List<T> list(T... items) {
		return new ArrayList<>(Arrays.asList(items));
	}

	/** An object literal used as a map of strings, like a keyword map: a new mutable map. */
	public static Map<String, String> map(String... keysAndValues) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
			map.put(keysAndValues[i], keysAndValues[i + 1]);
		}
		return map;
	}

	/** An object literal with modes as values: a new mutable map. */
	public static Map<String, Mode> modeMap(Object... keysAndValues) {
		Map<String, Mode> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
			map.put((String) keysAndValues[i], (Mode) keysAndValues[i + 1]);
		}
		return map;
	}

	/** An object literal with values of any type: a new mutable map. */
	public static Map<String, Object> objectMap(Object... keysAndValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
			map.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return map;
	}

	/**
	 * The keyword map of a mode.
	 *
	 * @throws ClassCastException when the keywords are a string
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, String> keywordMap(Object keywords) {
		return (Map<String, String>) keywords;
	}

	/**
	 * The keyword map of a mode, whose values may be strings or lists of
	 * keywords.
	 *
	 * @throws ClassCastException when the keywords are not a map
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> keywordObjectMap(Object keywords) {
		return (Map<String, Object>) keywords;
	}

	/**
	 * {@code array.concat(...)}: a new list with the elements of the list,
	 * followed by the arguments (lists are flattened, other values appended).
	 */
	@SuppressWarnings("unchecked")
	public static <T> List<T> concat(List<? extends T> list, Object... items) {
		List<T> result = new ArrayList<>(list);
		for (Object item : items) {
			if (item instanceof List<?> l) {
				result.addAll((List<T>) l);
			} else {
				result.add((T) item);
			}
		}
		return result;
	}

	/** {@code array.slice(start)}. */
	public static <T> List<T> slice(List<T> list, int start) {
		int size = list.size();
		int from = start < 0 ? Math.max(0, size + start) : Math.min(start, size);
		return new ArrayList<>(list.subList(from, size));
	}

	/** {@code array.filter(predicate)}: a new list with the elements that pass the test. */
	public static <T> List<T> filter(List<T> list, Predicate<? super T> predicate) {
		List<T> result = new ArrayList<>();
		for (T item : list) {
			if (predicate.test(item)) {
				result.add(item);
			}
		}
		return result;
	}

	/** {@code array.pop()}: removes the last element of the list and returns it, null when the list is empty. */
	public static <T> T pop(List<T> list) {
		return list.isEmpty() ? null : list.remove(list.size() - 1);
	}

	/** {@code [].concat(...lists)}: a new list with the elements of the lists. */
	public static <T> List<T> flat(List<? extends List<? extends T>> lists) {
		List<T> result = new ArrayList<>();
		for (List<? extends T> list : lists) {
			result.addAll(list);
		}
		return result;
	}

	/** {@code array.find(predicate)}: the first element that passes the test, null when there is none. */
	public static <T> T find(List<T> list, Predicate<? super T> predicate) {
		for (T item : list) {
			if (predicate.test(item)) {
				return item;
			}
		}
		return null;
	}

	/** {@code array.findIndex(predicate)}: the index of the first element that passes the test, or -1. */
	public static <T> int findIndex(List<T> list, Predicate<? super T> predicate) {
		for (int i = 0; i < list.size(); i++) {
			if (predicate.test(list.get(i))) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * {@code array.splice(start, deleteCount, ...items)}: removes elements from
	 * the list and inserts the items in their place.
	 *
	 * @return the removed elements
	 */
	@SafeVarargs
	public static <T> List<T> splice(List<T> list, int start, int deleteCount, T... items) {
		int size = list.size();
		int from = start < 0 ? Math.max(0, size + start) : Math.min(start, size);
		int to = Math.min(size, from + Math.max(0, deleteCount));
		List<T> range = list.subList(from, to);
		List<T> removed = new ArrayList<>(range);
		range.clear();
		list.addAll(from, Arrays.asList(items));
		return removed;
	}

	/** {@code string[index]}: the character at the index as a string, null (undefined) when out of range. */
	public static String charAt(String s, int index) {
		return index >= 0 && index < s.length() ? String.valueOf(s.charAt(index)) : null;
	}

	/** {@code object.key} for an object of unknown type: a value of a map or a property of a mode. */
	public static Object get(Object object, String key) {
		if (object instanceof Map<?, ?> map) {
			return map.get(key);
		}
		if (object instanceof Mode mode) {
			return mode.get(key);
		}
		return null;
	}

	/** A value of unknown type that is used as a list. */
	@SuppressWarnings("unchecked")
	public static <T> List<T> asList(Object value) {
		return (List<T>) value;
	}

	/**
	 * A regular expression as a value, for lists that hold both strings and
	 * regular expressions; elsewhere a regular expression is represented by its
	 * source.
	 *
	 * @param source the source, in JavaScript syntax
	 * @param flags  the flags, like {@code "g"}
	 */
	public record RegExp(String source, String flags) {
		/** Like JavaScript, {@code /source/flags}. */
		@Override
		public String toString() {
			return "/" + source + "/" + flags;
		}
	}

	/** A regular expression literal in a list of values. */
	public static RegExp regexp(String source) {
		return new RegExp(source, "");
	}

	/** The source of a regular expression: the source of a {@link RegExp}, or a string. */
	public static String source(Object value) {
		if (value instanceof RegExp re) {
			return re.source();
		}
		return value == null ? null : String.valueOf(value);
	}

	/** The sources of a list of regular expressions and strings. */
	public static List<String> sources(List<?> values) {
		List<String> result = new ArrayList<>();
		for (Object value : values) {
			result.add(source(value));
		}
		return result;
	}

	/**
	 * {@code /regex/.test(value)}: whether the regex matches the value, as a
	 * string (a {@link RegExp} is converted to {@code /source/flags}, like in JavaScript).
	 */
	public static boolean test(String regex, Object value) {
		return JsRegex.compile(regex, false).matcher(String.valueOf(value)).find();
	}

	/** {@code string.match(/regex/)}: the groups of the first match, null when it does not match. */
	public static List<String> match(String s, String regex) {
		Matcher m = JsRegex.compile(regex, false).matcher(s);
		if (!m.find()) {
			return null;
		}
		List<String> groups = new ArrayList<>();
		for (int i = 0; i <= m.groupCount(); i++) {
			groups.add(m.group(i));
		}
		return groups;
	}

	/** {@code Object.assign(target, ...sources)} for modes: copies the properties and returns the target. */
	public static Mode assign(Mode target, Mode... sources) {
		for (Mode source : sources) {
			if (source != null) {
				target.assign(source);
			}
		}
		return target;
	}

	/** {@code string.slice(start)}. */
	public static String slice(String s, int start) {
		return slice(s, start, s.length());
	}

	/** {@code string.slice(start, end)}. */
	public static String slice(String s, int start, int end) {
		int length = s.length();
		int from = start < 0 ? Math.max(0, length + start) : Math.min(start, length);
		int to = end < 0 ? Math.max(0, length + end) : Math.min(end, length);
		return from < to ? s.substring(from, to) : "";
	}

	/** {@code array.reverse()}: reverses the list in place and returns it. */
	public static <T> List<T> reverse(List<T> list) {
		Collections.reverse(list);
		return list;
	}

	/** {@code array.map(fn)}. */
	public static <T, R> List<R> map(List<T> list, Function<? super T, ? extends R> fn) {
		List<R> result = new ArrayList<>(list.size());
		for (T item : list) {
			result.add(fn.apply(item));
		}
		return result;
	}

	/** {@code array.join(separator)}. */
	public static String join(List<?> list, String separator) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < list.size(); i++) {
			if (i > 0) {
				sb.append(separator);
			}
			Object item = list.get(i);
			sb.append(item == null ? "" : item);
		}
		return sb.toString();
	}

	/** {@code string.split(separator)} with a string separator. */
	public static List<String> split(String s, String separator) {
		List<String> result = new ArrayList<>();
		if (separator.isEmpty()) {
			for (int i = 0; i < s.length(); i++) {
				result.add(String.valueOf(s.charAt(i)));
			}
			return result;
		}
		int start = 0;
		int pos;
		while ((pos = s.indexOf(separator, start)) >= 0) {
			result.add(s.substring(start, pos));
			start = pos + separator.length();
		}
		result.add(s.substring(start));
		return result;
	}

	/** {@code string.replace(pattern, replacement)} with a string pattern: replaces the first occurrence. */
	public static String replace(String s, String search, String replacement) {
		int pos = s.indexOf(search);
		return pos < 0 ? s : s.substring(0, pos) + replacement + s.substring(pos + search.length());
	}

	/**
	 * {@code string.replace(/regex/, replacement)}.
	 *
	 * @param regex  the source of a JavaScript regular expression
	 * @param global whether the regex has the {@code g} flag
	 */
	public static String replaceRegex(String s, String regex, String replacement, boolean global) {
		Matcher m = Pattern.compile(JsRegex.translate(regex)).matcher(s);
		String quoted = Matcher.quoteReplacement(replacement);
		return global ? m.replaceAll(quoted) : m.replaceFirst(quoted);
	}

	/** {@code a || b}: the first value when it is truthy, the second otherwise. */
	public static <T> T or(T a, T b) {
		return truthy(a) ? a : b;
	}

	/** Whether a value is truthy in JavaScript. */
	public static boolean truthy(Object value) {
		if (value == null) {
			return false;
		}
		if (value instanceof Boolean b) {
			return b;
		}
		if (value instanceof String s) {
			return !s.isEmpty();
		}
		if (value instanceof Number n) {
			return n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
		}
		return true;
	}
}
