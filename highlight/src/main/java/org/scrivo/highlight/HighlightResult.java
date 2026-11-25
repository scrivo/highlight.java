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

/**
 * The result of highlighting code.
 */
public final class HighlightResult {

	private final String language;
	private final int relevance;
	private final String value;
	private final boolean illegal;
	private final Throwable errorRaised;
	final Highlighter.Frame top;
	private HighlightResult secondBest;

	HighlightResult(String language, int relevance, String value, boolean illegal, Throwable errorRaised,
			Highlighter.Frame top) {
		this.language = language;
		this.relevance = relevance;
		this.value = value;
		this.illegal = illegal;
		this.errorRaised = errorRaised;
		this.top = top;
	}

	/** The id of the language, or an empty string when no language was detected. */
	public String getLanguage() {
		return language;
	}

	/** The relevance score. */
	public int getRelevance() {
		return relevance;
	}

	/** The highlighted HTML code. */
	public String getValue() {
		return value;
	}

	/** Whether an illegal lexeme was found, the value is the escaped code then. */
	public boolean isIllegal() {
		return illegal;
	}

	/**
	 * The error that occurred while highlighting in safe mode, the value is the
	 * escaped code then.
	 *
	 * @return the error, or null
	 */
	public Throwable getErrorRaised() {
		return errorRaised;
	}

	/**
	 * When the language was detected, the result of the runner-up language.
	 *
	 * @return the result, or null
	 */
	public HighlightResult getSecondBest() {
		return secondBest;
	}

	void setSecondBest(HighlightResult secondBest) {
		this.secondBest = secondBest;
	}

	@Override
	public String toString() {
		return "HighlightResult[language=" + language + ", relevance=" + relevance + ", illegal=" + illegal + "]";
	}
}
