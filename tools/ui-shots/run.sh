#!/usr/bin/env bash
# Рисует экраны приложения в tools/ui-shots/out/*.png (правило [UI-RENDER-CHECK], ADR-148).
# Нужны: Rust (rustup), PROTOC, JDK 17 с javac (JAVA17_HOME), Gradle 9.8.1 (GRADLE), Android SDK с принятыми
# лицензиями (ANDROID_SDK_ROOT; платформу 37 Gradle докачает сам). Android-ядро и телефон не нужны.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
: "${JAVA17_HOME:?JDK 17 с javac}"
: "${GRADLE:?путь к gradle 9.8.1 (bin/gradle)}"
: "${ANDROID_SDK_ROOT:?путь к Android SDK}"
[ -f "$HOME/.cargo/env" ] && . "$HOME/.cargo/env"
if [ -z "${PROTOC:-}" ]; then PROTOC="$(bash "$root/scripts/fetch-protoc.sh")"; fi
export PROTOC

mode="${1:-verify}"
case "$mode" in record|verify) ;; *) echo "Usage: run.sh [record|verify]" >&2; exit 2;; esac
in="$here/build-in"
rm -rf "$in" "$here/out"
mkdir -p "$in/uniffi" "$here/out"

# Привязки Kotlin: ядро собирается под этот компьютер (нужны только типы, вызовы в тестах не идут).
(cd "$root" && cargo build --locked -p sm-ffi \
  && cargo run --locked --release --manifest-path tools/uniffi-bindgen/Cargo.toml -- \
       generate --library target/debug/libsm_ffi.so --language kotlin --no-format --out-dir "$in/uniffi")

# Копия исходников приложения: private и internal → открытое, чтобы тест видел внутренние экраны. В репозитории ничего не меняется.
mkdir -p "$in/src"
cp -r "$root/android/app/src/main/java/." "$in/src/"
# Согласованный debug-журнал (ADR-150) отсутствует в release, стенд рисует debug-экраны.
cp -r "$root/android/app/src/debug/java/." "$in/src/"
cp -r "$root/android/app/src/main/res" "$in/res"
cp "$root/android/app/src/main/AndroidManifest.xml" "$in/AndroidManifest.xml"
find "$in/src" -name '*.kt' -print0 \
  | xargs -0 sed -i -E 's/^(private|internal) (fun|class|data class|object|val|enum class|sealed class|sealed interface|interface) /\2 /'

echo "sdk.dir=$ANDROID_SDK_ROOT" > "$here/local.properties"
cd "$here"
JAVA_HOME="$JAVA17_HOME" "$GRADLE" --no-daemon :lib:cleanTestDebugUnitTest :lib:testDebugUnitTest
python3 "$here/verify.py" "$mode"
echo "Готово: $here/out"
