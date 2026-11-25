package org.scrivo.highlight.demo;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class DemoResourceTest {

	@Test
	void index() {
		given().when().get("/").then().statusCode(200).body(containsString("highlight.java demo"));
	}

	@Test
	void detectListsAllLanguages() {
		given().when().get("/detect").then().statusCode(200)
				.body(containsString("<iframe src=\"/detect/java\""))
				.body(containsString("value=\"monokai\""));
	}

	@Test
	void detection() {
		given().when().get("/detect/java").then().statusCode(200)
				.body(containsString("Result: Passed"))
				.body(containsString("<span class=\"hljs-keyword\">"));
	}

	@Test
	void unknownLanguage() {
		given().when().get("/detect/no-such-language").then().statusCode(404);
		given().when().get("/snippets/..%2F..%2Fapplication.properties").then().statusCode(404);
	}

	@Test
	void compare() {
		given().when().get("/compare").then().statusCode(200)
				.body(containsString("<td class=\"signal\">xml</td>"))
				.body(containsString("<span class=\"hljs-tag\">"));
	}

	@Test
	void lineNumbers() {
		given().when().get("/line-numbers").then().statusCode(200)
				.body(containsString("data-line-number=\"1\""));
	}

	@Test
	void exampleShowsItsOwnTemplate() {
		given().when().get("/example").then().statusCode(200)
				.body(containsString("<code class=\"hljs xml\">"))
				.body(containsString("<span class=\"hljs-tag\">"))
				// the template source is shown, not rendered
				.body(containsString("{code.raw}"));
	}

	@Test
	void rawSnippet() {
		given().when().get("/snippets/java").then().statusCode(200).contentType(containsString("text/plain"));
	}

	@Test
	void stylesheetsAndTheirImages() {
		given().when().get("/styles/default.css").then().statusCode(200)
				.contentType(containsString("text/css"))
				.body(containsString(".hljs"));
		given().when().get("/styles/school-book.png").then().statusCode(200).contentType("image/png");
		given().when().get("/styles/nope.css").then().statusCode(404);
		given().when().get("/styles/themes.properties").then().statusCode(404);
	}

	@Test
	void highlightJsForTheComparison() {
		given().when().get("/highlight.pack.js").then().statusCode(200);
	}
}
