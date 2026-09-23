import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(project(":game"))
    implementation(project(":backend-onnx"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
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

val modelsDir: String = rootProject.file("models").absolutePath

compose.desktop {
    application {
        mainClass = "dev.loupe.game.desktop.MainKt"
        // Where the app looks for the gitignored Laya weights; absent, it flies the baseline.
        jvmArgs += listOf("-Dloupe.models.dir=$modelsDir", "-Xmx1g")
    }
}

// Headless model-vs-baseline: ./gradlew :game-desktop:match [-Pseeds=1,2,3] [-Pseconds=60]
tasks.register<JavaExec>("match") {
    group = "application"
    description = "Flies the model pilot and the baseline on the same seeds and prints the result."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.loupe.game.desktop.MatchMainKt")
    systemProperty("loupe.models.dir", modelsDir)
    maxHeapSize = "1g"
    args(
        (project.findProperty("seeds") as String?) ?: "1,2,3,4,5",
        (project.findProperty("seconds") as String?) ?: "60",
    )
}

// Off-screen screenshots of the real UI: ./gradlew :game-desktop:snapshot -Pout=<dir> [-Pcompare]
tasks.register<JavaExec>("snapshot") {
    group = "application"
    description = "Renders the game UI off-screen to PNG files."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.loupe.game.desktop.SnapshotMainKt")
    systemProperty("loupe.models.dir", modelsDir)
    maxHeapSize = "1g"
    args(
        (project.findProperty("out") as String?) ?: layout.buildDirectory.dir("snapshots").get().asFile.absolutePath,
        (project.findProperty("seconds") as String?) ?: "12",
        if (project.hasProperty("compare")) "compare" else "single",
    )
}
