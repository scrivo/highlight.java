package org.scrivo.highlight.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.scrivo.highlight.HighlightResult;
import org.scrivo.highlight.HighlightUtilities;
import org.scrivo.highlight.Highlighter;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;

/**
 * The demo pages, the counterparts of the demo pages of highlight.php.
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
public class DemoResource {

	/** The templates in {@code templates/DemoResource/}. */
	@CheckedTemplate
	static class Templates {
		static native TemplateInstance index(int languageCount, int snippetCount);

		static native TemplateInstance detect(List<String> languages, List<String> styles);

		static native TemplateInstance detection(Detection detection);

		static native TemplateInstance compare(List<Comparison> comparisons);

		static native TemplateInstance lineNumbers(List<String> lines);

		static native TemplateInstance example(String language, String code);
	}

	/** The result of auto-detecting the language of a snippet. */
	public record Detection(String expected, String actual, int relevance, String secondBest,
			int secondBestRelevance, String code, String time) {

		public boolean passed() {
			return expected.equals(actual);
		}
	}

	/** A snippet highlighted by highlight.java, to compare with highlight.js. */
	public record Comparison(String language, String snippet, String code) {
	}

	@Inject
	Highlighter highlighter;

	@Inject
	Snippets snippets;

	@GET
	public TemplateInstance index() {
		return Templates.index(Highlighter.listBundledLanguages().size(), snippets.languages().size());
	}

	/** Auto-detection of all languages (demo.php). */
	@GET
	@Path("detect")
	public TemplateInstance detect() {
		List<String> styles = new ArrayList<>(HighlightUtilities.getAvailableStyleSheets());
		styles.sort(null);
		return Templates.detect(snippets.languages(), styles);
	}

	/** Auto-detection of a single language, shown in an iframe of the detect page. */
	@GET
	@Path("detect/{language}")
	public TemplateInstance detection(@PathParam("language") String language) {
		String snippet = snippet(language);

		long start = System.nanoTime();
		HighlightResult result = highlighter.highlightAuto(snippet);
		double seconds = (System.nanoTime() - start) / 1e9;

		HighlightResult secondBest = result.getSecondBest();
		return Templates.detection(new Detection(language, result.getLanguage(), result.getRelevance(),
				secondBest == null ? "" : secondBest.getLanguage(), secondBest == null ? 0 : secondBest.getRelevance(),
				result.getValue(), String.format("%.3f", seconds)));
	}

	/** highlight.java next to highlight.js (compare.php). */
	@GET
	@Path("compare")
	public TemplateInstance compare() {
		List<Comparison> comparisons = new ArrayList<>();
		for (String language : snippets.languages()) {
			String snippet = snippet(language);
			comparisons.add(new Comparison(language, snippet, highlighter.highlight(language, snippet).getValue()));
		}
		return Templates.compare(comparisons);
	}

	/** Highlighted code with line numbers (line-numbers.php). */
	@GET
	@Path("line-numbers")
	public TemplateInstance lineNumbers() {
		String code = highlighter.highlight("java", snippet("java")).getValue();
		return Templates.lineNumbers(HighlightUtilities.splitCodeIntoArray(code));
	}

	/** The simplest use of highlight.java: this page shows its own template (example.php). */
	@GET
	@Path("example")
	public TemplateInstance example() {
		String template = resource("templates/DemoResource/example.html");
		HighlightResult result = highlighter.highlight("html", template);
		return Templates.example(result.getLanguage(), result.getValue());
	}

	/** The raw snippet of a language. */
	@GET
	@Path("snippets/{language}")
	@Produces(MediaType.TEXT_PLAIN)
	public String rawSnippet(@PathParam("language") String language) {
		return snippet(language);
	}

	private String snippet(String language) {
		return snippets.snippet(language).orElseThrow(() -> new NotFoundException("Unknown language: " + language));
	}

	private static String resource(String name) {
		try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
			if (in == null) {
				throw new NotFoundException(name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
