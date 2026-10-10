#!/usr/bin/env bash
# Сборка ядра для Android (ADR-45, ADR-46): libsm_ffi.so под arm64-v8a и Kotlin-привязки UniFFI.
# Нужны: rustup target aarch64-linux-android, ANDROID_NDK_HOME (NDK r28).
# protoc (для libsignal, ADR-55) скачивается и сверяется scripts/fetch-protoc.sh; свой — через PROTOC
# (тогда за его происхождение отвечает тот, кто запускает; CI PROTOC не задаёт).
# Результат кладётся в android/app/build/generated/{jniLibs,uniffi} — оттуда его берёт Gradle.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
profile="${1:-release}"
api=31 # = minSdk (ADR-45)
target=aarch64-linux-android

: "${ANDROID_NDK_HOME:?Укажите ANDROID_NDK_HOME (путь к NDK)}"
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$toolchain/${target}${api}-clang" ] || { echo "Нет линкера NDK: $toolchain/${target}${api}-clang" >&2; exit 1; }

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$toolchain/${target}${api}-clang"
export CC_aarch64_linux_android="$toolchain/${target}${api}-clang"
export AR_aarch64_linux_android="$toolchain/llvm-ar"

cd "$root"
if [ -z "${PROTOC:-}" ]; then PROTOC="$(bash scripts/fetch-protoc.sh)"; fi
export PROTOC
flag=()
[ "$profile" = release ] && flag=(--release)
cargo build --locked -p sm-ffi --target "$target" "${flag[@]}"
lib="$root/target/$target/$profile/libsm_ffi.so"

out="$root/android/app/build/generated"
rm -rf "$out/jniLibs" "$out/uniffi"
mkdir -p "$out/jniLibs/arm64-v8a" "$out/uniffi"
cp "$lib" "$out/jniLibs/arm64-v8a/"

# Генератор той же версии UniFFI, отдельный воркспейс tools/uniffi-bindgen.
cargo run --locked --release --manifest-path tools/uniffi-bindgen/Cargo.toml -- \
  generate --library "$lib" --language kotlin --no-format --out-dir "$out/uniffi"
echo "Готово: $out"
