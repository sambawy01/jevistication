import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":engine"))

    // The project's first runtime dependency. MIT, verified at source from the resolved POM
    // rather than from a badge, with no transitive dependencies -- see docs/LICENSING.md.
    // It lives here, and not in :engine, so the core engine keeps zero runtime dependencies.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")

    // The tokenizer: Hugging Face's Rust `tokenizers` library through DJL's JNI binding, so a
    // tokenizer.json is read by the same code the Python reference uses rather than a hand-rolled
    // copy. Apache-2.0, verified from the resolved POMs together with its eight transitive
    // dependencies and the native libraries it bundles -- see docs/LICENSING.md.
    implementation("ai.djl.huggingface:tokenizers:0.38.0")

    testImplementation(kotlin("test"))
    // Reads the golden fixture. Already on the runtime classpath through DJL (same version, same
    // licence check); declared so the tests do not lean on a transitive dependency.
    testImplementation("com.google.code.gson:gson:2.13.1")
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
    // Where the gated Laya tests look for the (gitignored, ~2 GB) weights, tokenizer and exported
    // graphs. Absent files skip those tests rather than failing them, so CI stays green without.
    val models = rootProject.file("models")
    systemProperty("loupe.models.dir", models.absolutePath)
    // Declared as inputs so a test result is never replayed from the build cache across the
    // weights appearing or disappearing: without this, a run with the model present is restored as
    // "passed" on a machine where the same tests would have been skipped, and vice versa.
    inputs.files(
        fileTree(models) {
            include("laya-multilingual/tokenizer/tokenizer.json", "laya-multilingual-onnx/*.onnx")
        },
    ).withPropertyName("layaModels").withPathSensitivity(PathSensitivity.RELATIVE).optional()
    // The FP32 Laya graph is ~1.3 GB of native memory; the JVM heap only holds the fixture.
    maxHeapSize = "1g"
}
