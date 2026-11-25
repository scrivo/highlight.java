#!/bin/bash
# Verifies the generated Java language classes against highlight.js by
# comparing canonical dumps of the evaluated definitions.
#
# usage: tools/transpiler/verify.sh <highlight.js dir> [language...]
#
# The classes must be compiled first (mvn compile).
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"
HLJS="$1"; shift
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

node "$DIR/dump.js" "$HLJS" "$TMP/js" "$@"
java -cp "$ROOT/target/classes" "$DIR/Dump.java" "$TMP/java" "$@"
node "$DIR/compare.js" "$TMP/js" "$TMP/java"
