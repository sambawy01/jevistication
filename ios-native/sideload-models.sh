#!/usr/bin/env bash
# Development only: copies Laya's tokenizer and INT8 graph from models/ into an installed app's
# Application Support directory on a simulator, where LayaModelStore.applicationSupport() looks,
# and checks both against the SHA-256 pins LayaModelStore enforces. The shipping app gets these
# files through its own one-time, consented download instead; nothing here is used at runtime.
#
# Usage: ios-native/sideload-models.sh [bundle-id (default com.loupe-ai.ios)] [simulator udid | booted]
set -euo pipefail

bundle="${1:-com.loupe-ai.ios}"
device="${2:-booted}"
root="$(cd "$(dirname "$0")/.." && pwd)"

tokenizer="$root/models/laya-multilingual/tokenizer/tokenizer.json"
graph="$root/models/laya-multilingual-onnx/laya-multilingual-choice.int8.onnx"
# Keep in step with LayaModelStore.FILES (backend-onnx-ios).
pins="609d8f4c067cd3950f88594c5a802616cea245823836ef5848ee4fc40aab5b6f  $tokenizer
8b994315135dd7684331bb58fe3a769e3ca1145a5c421a2a41e2b3c85397b2fa  $graph"
echo "$pins" | shasum -a 256 -c - || { echo "models/ does not hold the pinned files" >&2; exit 1; }

container="$(xcrun simctl get_app_container "$device" "$bundle" data)"
dest="$container/Library/Application Support/Loupe/laya-multilingual"
mkdir -p "$dest"
cp "$tokenizer" "$dest/tokenizer.json"
cp "$graph" "$dest/laya-multilingual-choice.int8.onnx"
echo "side-loaded Laya into $dest"
