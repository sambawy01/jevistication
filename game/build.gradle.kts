import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

// The game's rules in common Kotlin (JVM + iOS), exported to the iPhone app through LoupeKit.
// Its only runtime dependency is the engine's Backend and Judgment types; it carries no
// third-party code. JVM-only pieces (the thread-pool AsyncDecider, Match.report's String.format)
// live in jvmMain. ParityTest (commonTest) runs on both and pins the same world bit for bit.
val appleHost = System.getProperty("os.name").startsWith("Mac")

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    if (appleHost) {
        iosArm64()
        iosSimulatorArm64()
    }

    applyDefaultHierarchyTemplate()

    compilerOptions {
        // GameClock is an expect object (System.nanoTime / the kernel's monotonic clock).
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":engine"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // Tests only: the gated real-model tests load Laya through the ONNX backend. Never on
            // the game's runtime classpath.
            implementation(project(":backend-onnx"))
            implementation("org.junit.jupiter:junit-jupiter:5.11.4")
            runtimeOnly("org.junit.platform:junit-platform-launcher")
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    // The gated tests look for the gitignored Laya weights here and skip without them.
    val models = rootProject.file("models")
    systemProperty("loupe.models.dir", models.absolutePath)
    // Declared as inputs so a result is never replayed from the build cache across the weights
    // appearing or disappearing (the trap recorded in docs/BUILD.md's hand-off).
    inputs.files(
        fileTree(models) {
            include("laya-multilingual/tokenizer/tokenizer.json", "laya-multilingual-onnx/*.onnx")
        },
    ).withPropertyName("layaModels").withPathSensitivity(PathSensitivity.RELATIVE).optional()
    maxHeapSize = "1g"
    testLogging {
        showStandardStreams = true
    }
}
