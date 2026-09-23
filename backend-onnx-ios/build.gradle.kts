plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

// Laya on iOS behind the engine's Backend interface: ONNX Runtime 1.20.0 (official iOS C package)
// and Hugging Face tokenizers 0.21.4 (ios-native/tokenizers-ffi), both through cinterop, with the
// prompt/sequence logic from :backend-laya-common. Included by settings.gradle.kts only on macOS
// and only once ios-native/build.sh has produced the two xcframeworks.
val native = rootProject.file("ios-native/build")
val models = rootProject.file("models")

kotlin {
    // Target name -> xcframework slice (ios-native/build.sh lays both libraries out by it).
    val slices = mapOf("iosArm64" to "ios-arm64", "iosSimulatorArm64" to "ios-arm64-simulator")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        val slice = slices.getValue(target.name)
        val ort = native.resolve("onnxruntime-lib/$slice")
        val tok = native.resolve("LoupeTokenizers.xcframework/$slice")
        target.compilations.getByName("main").cinterops {
            create("onnxruntime") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/onnxruntime.def"))
                includeDirs(ort.resolve("Headers"))
                extraOpts("-libraryPath", ort.absolutePath, "-staticLibrary", "libonnxruntime.a")
            }
            create("tokenizers") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/tokenizers.def"))
                includeDirs(tok.resolve("Headers"))
                extraOpts("-libraryPath", tok.absolutePath, "-staticLibrary", "libloupe_tokenizers.a")
            }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        iosMain.dependencies {
            api(project(":backend-laya-common"))
        }
        iosTest.dependencies {
            implementation(kotlin("test"))
            // Test-only: reads the golden fixtures (Kotlin/Native has no Gson). Not in any shipped
            // artifact. 1.8.0 is the line built against Kotlin 2.1.0.
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
        }
    }
}

// The simulator test process reads the gated models and the JVM's golden fixtures straight from
// the host filesystem; simctl forwards SIMCTL_CHILD_* variables to the child without the prefix.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_LOUPE_MODELS_DIR", models.absolutePath)
    environment("SIMCTL_CHILD_LOUPE_FIXTURES_DIR", rootProject.file("backend-onnx/src/test/resources/laya").absolutePath)
    // As in :backend-onnx: never replay a gated result across the weights appearing or vanishing.
    inputs.files(
        fileTree(models) {
            include("laya-multilingual/tokenizer/tokenizer.json", "laya-multilingual-onnx/*.int8.onnx")
        },
    ).withPropertyName("layaModels").withPathSensitivity(PathSensitivity.RELATIVE).optional()
    inputs.dir(rootProject.file("backend-onnx/src/test/resources/laya")).withPropertyName("layaFixtures")
}
