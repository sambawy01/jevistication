#!/usr/bin/env bash
# Builds the native pieces Laya needs on iOS into ios-native/build/ (gitignored):
#
#   LoupeTokenizers.xcframework  Hugging Face `tokenizers` 0.21.4 (tokenizers-ffi/), static,
#                                device (aarch64-apple-ios) + simulator (aarch64-apple-ios-sim)
#   onnxruntime.xcframework      the official ONNX Runtime 1.20.0 iOS C package (pod archive
#                                `onnxruntime-c`), static, checked against a pinned SHA-256
#   onnxruntime-lib/<slice>/      the same ORT archives as arm64 `libonnxruntime.a` + headers, the
#                                form Kotlin/Native's cinterop links (:backend-onnx-ios)
#
# Needs: macOS + Xcode, rustup (the toolchain and targets are pinned in
# tokenizers-ffi/rust-toolchain.toml and installed on first use), network for crates.io and
# download.onnxruntime.ai. This is build tooling; nothing here runs inside the app.
#
# Usage: ios-native/build.sh            (idempotent; re-run after changing tokenizers-ffi)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="$here/build"
mkdir -p "$out"

ORT_VERSION="1.20.0"
ORT_ZIP="pod-archive-onnxruntime-c-$ORT_VERSION.zip"
ORT_URL="https://download.onnxruntime.ai/$ORT_ZIP"
ORT_SHA256="50891a8aadd17d4811acb05ed151ba6c394129bb3ab14e843b0fc83a48d450ff"

export PATH="$HOME/.cargo/bin:$PATH"
# Match Kotlin/Native's minimum iOS version, so the Rust and C objects do not claim a newer OS than
# the binaries they are linked into (ld warns, and a real app on an older iOS could fail to load).
export IPHONEOS_DEPLOYMENT_TARGET=14.0
command -v rustup >/dev/null || { echo "rustup not found: install it from https://rustup.rs" >&2; exit 1; }

# ---- tokenizers ---------------------------------------------------------------------------------
(
    cd "$here/tokenizers-ffi"
    for target in aarch64-apple-ios aarch64-apple-ios-sim; do
        cargo build --release --locked --target "$target"
    done
)
rm -rf "$out/LoupeTokenizers.xcframework"
xcodebuild -create-xcframework \
    -library "$here/tokenizers-ffi/target/aarch64-apple-ios/release/libloupe_tokenizers.a" \
    -headers "$here/tokenizers-ffi/include" \
    -library "$here/tokenizers-ffi/target/aarch64-apple-ios-sim/release/libloupe_tokenizers.a" \
    -headers "$here/tokenizers-ffi/include" \
    -output "$out/LoupeTokenizers.xcframework" >/dev/null

# ---- ONNX Runtime -------------------------------------------------------------------------------
if [[ ! -f "$out/$ORT_ZIP" ]] || ! echo "$ORT_SHA256  $out/$ORT_ZIP" | shasum -a 256 -c - >/dev/null 2>&1; then
    curl -sSfL "$ORT_URL" -o "$out/$ORT_ZIP.part"
    mv "$out/$ORT_ZIP.part" "$out/$ORT_ZIP"
fi
echo "$ORT_SHA256  $out/$ORT_ZIP" | shasum -a 256 -c - >/dev/null || {
    echo "SHA-256 mismatch for $ORT_ZIP; refusing to use it" >&2; exit 1; }
rm -rf "$out/onnxruntime.xcframework" "$out/onnxruntime-unpacked"
mkdir -p "$out/onnxruntime-unpacked"
unzip -q "$out/$ORT_ZIP" -d "$out/onnxruntime-unpacked"
mv "$out/onnxruntime-unpacked/onnxruntime.xcframework" "$out/onnxruntime.xcframework"
cp "$out/onnxruntime-unpacked/LICENSE" "$out/onnxruntime-LICENSE"
rm -rf "$out/onnxruntime-unpacked"

# Kotlin/Native's cinterop embeds static libraries by file name and only links `lib*.a` archives,
# so give it the framework binaries under that name: arm64 only (lipo -thin drops the x86_64
# simulator half), headers alongside.
rm -rf "$out/onnxruntime-lib"
for slice in ios-arm64:ios-arm64 ios-arm64_x86_64-simulator:ios-arm64-simulator; do
    src="$out/onnxruntime.xcframework/${slice%%:*}/onnxruntime.framework"
    dst="$out/onnxruntime-lib/${slice##*:}"
    mkdir -p "$dst"
    if lipo -info "$src/onnxruntime" | grep -q "Non-fat"; then
        cp "$src/onnxruntime" "$dst/libonnxruntime.a"
    else
        lipo "$src/onnxruntime" -thin arm64 -output "$dst/libonnxruntime.a"
    fi
    cp -R "$src/Headers" "$dst/Headers"
done

echo "ios-native: built $out/LoupeTokenizers.xcframework and $out/onnxruntime.xcframework ($ORT_VERSION)"
