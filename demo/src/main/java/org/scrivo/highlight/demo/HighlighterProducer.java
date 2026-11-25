package org.scrivo.highlight.demo;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.scrivo.highlight.Highlighter;

/**
 * Makes a Highlighter available for injection. A single instance serves all
 * requests: a Highlighter can be used by several threads at once, as long as
 * its options are not changed.
 */
public class HighlighterProducer {

	@Produces
	@Singleton
	Highlighter highlighter() {
		// registers all bundled languages
		return new Highlighter();
	}
}
