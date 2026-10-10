#!/usr/bin/env bash
# protoc для сборки libsignal: libsignal-protocol и spqr генерируют код из .proto через prost-build
# (ADR-55 п.4). Закреплённый релиз protocolbuffers/protobuf, архив сверяется с sha256, опубликованной
# на странице релиза GitHub (проверено 2026-10-10, wiki/references/platforms.md). Архив скачивается
# заново при каждом вызове — непроверенный protoc из кэша не используется.
# Печатает путь к protoc. Запуск: export PROTOC="$(bash scripts/fetch-protoc.sh)"
set -euo pipefail

version=36.2
sha256=121f6c7afe1d4d0e3ea6aab9432038599250134cbf4474cb1167d2c7decd4278

[ "$(uname -s)-$(uname -m)" = Linux-x86_64 ] || { echo "fetch-protoc: только Linux x86_64" >&2; exit 1; }

dir="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/protoc-$version.XXXXXX")"
curl -sSfL -o "$dir/protoc.zip" \
  "https://github.com/protocolbuffers/protobuf/releases/download/v$version/protoc-$version-linux-x86_64.zip"
echo "$sha256  $dir/protoc.zip" | sha256sum -c --quiet - >&2
unzip -q "$dir/protoc.zip" -d "$dir"
rm -f "$dir/protoc.zip"
echo "$dir/bin/protoc"
