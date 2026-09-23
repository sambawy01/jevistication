rootProject.name = "loupe"

include("engine")
include("backend-laya-common")
include("backend-onnx")
include("game")
include("game-desktop")
include("templates")
include("persistence")
include("sources-common")
include("sources-desktop")
include("loupe-desktop")
include("loupe-kit")

// Laya on iOS: ONNX Runtime and the Hugging Face tokenizer through cinterop. Needs macOS and the
// native pieces ios-native/build.sh produces (gitignored); without them the module is left out and
// everything else builds as before. See docs/BUILD.md, progress log "Epic #6 child 3".
if (System.getProperty("os.name").startsWith("Mac")) {
    val native = file("ios-native/build")
    if (native.resolve("onnxruntime-lib").isDirectory && native.resolve("LoupeTokenizers.xcframework").isDirectory) {
        include("backend-onnx-ios")
    } else {
        println("backend-onnx-ios skipped: run ios-native/build.sh to build Laya for iOS")
    }
}
