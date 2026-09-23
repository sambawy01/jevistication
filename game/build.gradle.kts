import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    // The game's only runtime dependency. It needs nothing but the engine's Backend and Judgment
    // types, so it ports to Android unchanged and carries no third-party code of its own.
    api(project(":engine"))

    // Tests only: the gated real-model tests load Laya through the ONNX backend. Never on the
    // game's runtime classpath.
    testImplementation(project(":backend-onnx"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.test {
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
