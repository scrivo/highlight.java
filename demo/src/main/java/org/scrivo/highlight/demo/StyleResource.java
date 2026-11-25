package org.scrivo.highlight.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.regex.Pattern;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import org.scrivo.highlight.HighlightUtilities;

/**
 * Serves the stylesheets bundled with highlight.java, and the images some of
 * them use, straight from the jar.
 */
@Path("styles")
public class StyleResource {

	private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]*");

	@GET
	@Path("{file}")
	public Response file(@PathParam("file") String file) {
		if (!NAME.matcher(file).matches()) {
			throw new NotFoundException();
		}
		if (file.endsWith(".css")) {
			try {
				return Response.ok(HighlightUtilities.getStyleSheet(file), "text/css; charset=UTF-8").build();
			} catch (IllegalArgumentException e) {
				throw new NotFoundException(e.getMessage());
			}
		}

		// an image of a stylesheet, next to it in the jar
		String type = file.endsWith(".png") ? "image/png" : file.endsWith(".jpg") ? "image/jpeg" : null;
		if (type == null) {
			throw new NotFoundException();
		}
		try (InputStream in = HighlightUtilities.class.getResourceAsStream("styles/" + file)) {
			if (in == null) {
				throw new NotFoundException();
			}
			return Response.ok(in.readAllBytes(), type).build();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
