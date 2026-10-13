#!/usr/bin/env bash
# Writes test/dk/simongray/conneg/negotiator_cases.cljc from the test suite
# of jshttp/negotiator at the version below. It needs git, node and npm, and
# runs from the root of the repository.
set -euo pipefail

version=1.1.0
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT

git clone --quiet --depth 1 --branch "v$version" https://github.com/jshttp/negotiator "$dir"
(cd "$dir" && npm install --silent --no-audit --no-fund)
node dev/negotiator.js "$dir" test/dk/simongray/conneg/negotiator_cases.cljc
