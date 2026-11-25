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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.scrivo.highlight.languages.Registry;

/**
 * Convenience functions that are not part of highlight.js: access to the
 * bundled stylesheets and splitting highlighted code into lines.
 * <p>
 * The stylesheets of highlight.js are bundled as resources in
 * {@code org/scrivo/highlight/styles}.
 */
public final class HighlightUtilities {

	private static final String STYLES = "styles/";

	private static final Pattern OPENING_TAG = Pattern.compile("<span[^>]*+>");
	private static final Pattern CLOSING_TAG = Pattern.compile("</span>");
	private static final Pattern COMPLETE_TAG = Pattern.compile("<span[^>]*+>[^<]*+</span>");

	private static volatile Map<String, int[]> themes;

	private HighlightUtilities() {
	}

	/**
	 * The names of the bundled stylesheets, like {@code "default"} or
	 * {@code "monokai"}.
	 */
	public static List<String> getAvailableStyleSheets() {
		return new ArrayList<>(themes().keySet());
	}

	/**
	 * The location of a bundled stylesheet.
	 *
	 * @param name the name of the stylesheet, with or without {@code .css}
	 * @throws IllegalArgumentException when there is no such stylesheet
	 */
	public static URL getStyleSheetUrl(String name) {
		name = withoutCssExtension(name);
		URL url = themes().containsKey(name) ? HighlightUtilities.class.getResource(STYLES + name + ".css") : null;
		if (url == null) {
			throw new IllegalArgumentException("There is no stylesheet by the name of '" + name + "'.");
		}
		return url;
	}

	/**
	 * The contents of a bundled stylesheet.
	 *
	 * @param name the name of the stylesheet, with or without {@code .css}
	 * @throws IllegalArgumentException when there is no such stylesheet
	 */
	public static String getStyleSheet(String name) {
		try (InputStream in = getStyleSheetUrl(name).openStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * The background color of a bundled stylesheet.
	 *
	 * @param name the name of the stylesheet, with or without {@code .css}
	 * @return the red, green and blue components (0-255)
	 * @throws IllegalArgumentException when there is no such stylesheet
	 */
	public static int[] getThemeBackgroundColor(String name) {
		name = withoutCssExtension(name);
		int[] color = themes().get(name);
		if (color == null) {
			throw new IllegalArgumentException("There is no stylesheet by the name of '" + name + "'.");
		}
		return color.clone();
	}

	/**
	 * The bundled definition of a language.
	 *
	 * @param name the language id
	 * @throws IllegalArgumentException when there is no such language
	 */
	public static LanguageDefinition getLanguageDefinition(String name) {
		var supplier = Registry.LANGUAGES.get(name);
		if (supplier == null) {
			throw new IllegalArgumentException("There is no language definition for " + name);
		}
		return supplier.get();
	}

	/**
	 * Splits highlighted code into lines, closing the spans that are open at
	 * the end of a line and reopening them at the start of the next one, so
	 * that each line is valid HTML on its own.
	 *
	 * @param html the highlighted code
	 * @return the lines, an empty list for blank code
	 */
	public static List<String> splitCodeIntoArray(String html) {
		if (html.isBlank()) {
			return new ArrayList<>();
		}

		String queuedPrefix = "";
		String[] rawLines = html.split("\\R", -1);

		for (int i = 0; i < rawLines.length; i++) {
			String rawLine = rawLines[i];

			// If the previous line has been marked as "open", then we'll have something in our queue
			if (!queuedPrefix.isEmpty()) {
				rawLine = queuedPrefix + rawLine;
				queuedPrefix = "";
			}

			int openingTags = count(OPENING_TAG, rawLine);
			int closingTags = count(CLOSING_TAG, rawLine);

			// If the number of opening tags matches the number of closing tags, then
			// we don't have any new tags that span multiple lines
			if (openingTags != closingTags) {
				// Find all of the complete `<span>` tags and remove them from a working
				// copy of the line. Then we'll be left with just opening tags.
				String workingLine = COMPLETE_TAG.matcher(rawLine).replaceAll("");
				StringBuilder prefix = new StringBuilder();
				Matcher m = OPENING_TAG.matcher(workingLine);
				while (m.find()) {
					prefix.append(m.group());
				}
				queuedPrefix = prefix.toString();

				// Close all of the remaining open tags on this line
				rawLine += Highlighter.SPAN_END_TAG.repeat(Math.max(0, openingTags - closingTags));
			}
			rawLines[i] = rawLine;
		}

		return new ArrayList<>(List.of(rawLines));
	}

	private static int count(Pattern pattern, String s) {
		int n = 0;
		Matcher m = pattern.matcher(s);
		while (m.find()) {
			n++;
		}
		return n;
	}

	private static String withoutCssExtension(String name) {
		return name.endsWith(".css") ? name.substring(0, name.length() - 4) : name;
	}

	/** The themes and their background colors, from {@code styles/themes.properties}. */
	private static Map<String, int[]> themes() {
		Map<String, int[]> result = themes;
		if (result == null) {
			result = new LinkedHashMap<>();
			try (InputStream in = HighlightUtilities.class.getResourceAsStream(STYLES + "themes.properties")) {
				if (in == null) {
					throw new IllegalStateException("Missing resource " + STYLES + "themes.properties");
				}
				for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
					int eq = line.indexOf('=');
					if (line.isBlank() || line.startsWith("#") || eq < 0) {
						continue;
					}
					String[] rgb = line.substring(eq + 1).trim().split(",");
					result.put(line.substring(0, eq).trim(), new int[] { Integer.parseInt(rgb[0].trim()),
							Integer.parseInt(rgb[1].trim()), Integer.parseInt(rgb[2].trim()) });
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			result = Collections.unmodifiableMap(result);
			themes = result;
		}
		return result;
	}
}
