#!/bin/bash
# Updates highlight.java to a version of highlight.js: transpiles the language
# definitions, copies the stylesheets and the tests, and verifies the result.
#
# usage: tools/process.sh [highlight.js version]
#
# Requires curl, node, a JDK (17+) and Maven.
set -e

HLJS_V="${1:-10.0.0}"
HLJS_DL="https://api.github.com/repos/highlightjs/highlight.js/tarball/$HLJS_V"

cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
TEST_RESOURCES="$ROOT/src/test/resources/org/scrivo/highlight"

curl -L "$HLJS_DL" --output "lib_highlight.tar.gz"

rm -rf lib_highlight
mkdir lib_highlight
tar xzf lib_highlight.tar.gz -C lib_highlight --strip-components 1

# Transpile the language definitions into Java classes and verify them
(cd transpiler && npm install --silent)
rm -f "$ROOT"/src/main/java/org/scrivo/highlight/languages/*.java
node transpiler/generate.js lib_highlight
(cd "$ROOT" && mvn -q compile)
bash transpiler/verify.sh lib_highlight

# Copy the stylesheets, with an index of the themes and their background colors
node styles.js lib_highlight

# Copy the unit tests
rm -rf "$TEST_RESOURCES/detect" "$TEST_RESOURCES/markup"
cp -a lib_highlight/test/detect "$TEST_RESOURCES/detect"
cp -a lib_highlight/test/markup "$TEST_RESOURCES/markup"
rm -f "$TEST_RESOURCES"/{detect,markup}/index.js

(cd "$ROOT" && mvn -q test)
