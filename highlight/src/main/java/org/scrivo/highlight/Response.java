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

import java.util.Map;

/**
 * The response object handed to the callbacks of a mode ({@link Mode#onBegin}
 * and {@link Mode#onEnd}): a callback can ignore the match, and keep data
 * between callbacks.
 */
public final class Response {

	private final Map<String, Object> data;
	private boolean ignore;

	Response(Map<String, Object> data) {
		this.data = data;
	}

	/**
	 * Data of the mode, kept between the callbacks of the mode. Like
	 * {@code mode.data} in highlight.js, it is shared by all occurrences of the
	 * mode in the code being highlighted.
	 */
	public Map<String, Object> data() {
		return data;
	}

	/** Ignores the match: highlighting continues as if the rule had not matched. */
	public void ignoreMatch() {
		ignore = true;
	}

	/** Whether {@link #ignoreMatch()} was called. */
	public boolean isIgnored() {
		return ignore;
	}
}
