# highlight.java

*highlight.java* is a server-side syntax highlighter written in Java that supports 189 languages. It's a port of [highlight.js](https://highlightjs.org) by Ivan Sagalaev, and a sibling of [highlight.php](https://github.com/scrivo/highlight.php). It uses the language definitions and stylesheets of highlight.js: the language definitions are transpiled from JavaScript into Java classes.

This version is based on highlight.js 10.0.0 and requires Java 17 or newer.

## Installation

highlight.java is available from Maven Central:

```xml
<dependency>
    <groupId>org.scrivo</groupId>
    <artifactId>highlight</artifactId>
    <version>10.0.0</version>
</dependency>
```

For Gradle: `implementation("org.scrivo:highlight:10.0.0")`.

## Usage

The `org.scrivo.highlight.Highlighter` class contains the syntax highlighting functionality. You can choose between two highlighting modes:

1. explicit mode
2. automatic language detection mode

### Explicit Mode

In explicit mode, you must define which language you will be highlighting as.

```java
Highlighter hl = new Highlighter();
String code = Files.readString(Path.of("some_ruby_script.rb"));

try {
    HighlightResult highlighted = hl.highlight("ruby", code);

    out.print("<pre><code class=\"hljs " + highlighted.getLanguage() + "\">");
    out.print(highlighted.getValue());
    out.print("</code></pre>");
} catch (IllegalArgumentException e) {
    // thrown if the specified language does not exist
}
```

### Automatic Language Detection Mode

Alternatively you can use the automatic detection mode, which highlights your code with the language the library thinks is best. It is highly recommended you explicitly choose the language or limit the number of languages to automatically detect to reduce the number of inaccuracies.

```java
Highlighter hl = new Highlighter();
hl.setAutodetectLanguages(List.of("ruby", "python", "perl"));

HighlightResult highlighted = hl.highlightAuto(code);
```

When no autodetect languages are set, all registered languages are tried, as in highlight.js.

### Options

- `setTabReplace(String)`: replaces tabs in the result, null (the default) keeps them.
- `setClassPrefix(String)`: the prefix of the CSS classes, `hljs-` by default.
- `disableSafeMode()`: in safe mode (the default) errors while highlighting return the escaped code (`HighlightResult.getErrorRaised()` tells what went wrong); without it, they are thrown.

Languages are registered statically, so they are shared by all `Highlighter` instances. A `Highlighter` can be used by several threads at once, as long as its options are not changed meanwhile.

### Stylesheets

The stylesheets of highlight.js are bundled as resources in `org/scrivo/highlight/styles`. `HighlightUtilities` gives access to them:

- `getAvailableStyleSheets()`: the names of the stylesheets
- `getStyleSheet(name)`: the contents of a stylesheet
- `getStyleSheetUrl(name)`: the location of a stylesheet
- `getThemeBackgroundColor(name)`: the background color of a stylesheet, as `{r, g, b}`

`HighlightUtilities.splitCodeIntoArray(html)` splits highlighted code into lines that are valid HTML on their own, which is handy to add line numbers.

### Custom Languages

A language is a `LanguageDefinition`, a function that builds the modes of the language, as described in the [highlight.js language definition guide](https://highlightjs.readthedocs.io/en/latest/language-guide.html). Regular expressions use JavaScript syntax and are translated for Java automatically.

```java
Highlighter.registerLanguage("mylanguage", hljs -> new Mode()
        .aliases("mine")
        .keywords("if else return")
        .contains(
            hljs.QUOTE_STRING_MODE,
            hljs.C_LINE_COMMENT_MODE));
```

## Layout

- `highlight/`: the library (Maven project `org.scrivo:highlight`), with its tests and tools
- `demo/`: a demo web application, a separate Maven project

## How the port works

The bundled languages in `org.scrivo.highlight.languages` are generated from the highlight.js sources by the transpiler in `highlight/tools/transpiler`:

- `generate.js` parses each language definition (with [acorn](https://github.com/acornjs/acorn)) and translates it into a Java class. Java is statically typed, so the transpiler infers whether values are modes, keyword maps, lists of modes or strings. Anything outside the small subset of JavaScript used by language definitions is reported, so that new constructs in future highlight.js versions are noticed instead of silently mistranslated.
- `verify.sh` evaluates every definition both in JavaScript and in Java, and compares canonical dumps of the resulting mode graphs (including which modes are shared).

`JsRegex` translates JavaScript regular expressions to `java.util.regex` patterns. Both use UTF-16 strings, so positions in the code are the same as in highlight.js.

`highlight/tools/process.sh <version>` updates everything to a version of highlight.js: it downloads it, transpiles and verifies the languages, copies the stylesheets and the tests, and runs the tests.

## Demo

The `demo` directory holds a [Quarkus](https://quarkus.io) application that shows highlight.java at work on a web server: auto-detection of all languages, a comparison with highlight.js in the browser, line numbers and a minimal example. It is a separate Maven project that uses highlight.java from the Maven repository, as any application would, together with the `test-snippets` jar that holds a sample snippet of each language from the tests.

```bash
(cd highlight && mvn install)    # highlight.java and its test snippets into ~/.m2
cd demo
mvn quarkus:dev                  # http://localhost:8080
```

The demo is deliberately not below the directory of the library: Quarkus dev mode looks for Maven projects in the parent directories, and would use the library from `highlight/target` instead of the Maven repository.

## Tests

```bash
cd highlight
mvn test
```

The tests include the markup and auto-detection tests of highlight.js.

## License

[BSD](./LICENSE)
