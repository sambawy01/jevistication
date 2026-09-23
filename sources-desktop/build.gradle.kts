import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":engine"))

    // Desktop sources only, never :engine. Each is Apache-2.0, checked from its resolved POM with
    // its transitive dependencies and bundled files — see docs/LICENSING.md, "Desktop sources".
    // Email: a real MIME parser (headers, encodings, multipart, charsets) rather than a hand-rolled
    // one, and mbox framing from the same project.
    implementation("org.apache.james:apache-mime4j-core:0.8.15")
    implementation("org.apache.james:apache-mime4j-dom:0.8.15")
    implementation("org.apache.james:apache-mime4j-mbox-iterator:0.8.15")
    // PDF text layers. Text only: no rendering, no OCR.
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    // Image metadata (EXIF, dimensions, dates). There is no OCR on the desktop.
    implementation("com.drewnoakes:metadata-extractor:2.21.0")

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
    // PDFBox writes a font cache to the home directory unless told where; keep it in the build.
    systemProperty("pdfbox.fontcache", layout.buildDirectory.dir("pdfbox-cache").get().asFile.absolutePath)
}

// Regenerates the synthetic sample PDFs and images committed under src/main/resources. Run by hand
// after editing SampleDataGenerator; the outputs are committed so the build never depends on it.
tasks.register<JavaExec>("generateSamples") {
    group = "build setup"
    description = "Regenerates the synthetic sample PDFs and images."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.loupe.sources.SampleDataGeneratorKt")
    args(file("src/main/resources/dev/loupe/sources/sample").absolutePath)
    systemProperty("pdfbox.fontcache", layout.buildDirectory.dir("pdfbox-cache").get().asFile.absolutePath)
}
