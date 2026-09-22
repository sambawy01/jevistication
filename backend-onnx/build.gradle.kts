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
}
