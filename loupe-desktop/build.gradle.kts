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
    implementation(project(":templates"))
    implementation(project(":sources-desktop"))
    implementation(project(":backend-onnx"))
    // "Watch it think": the Riverflight window, reused as a library. It also supplies ModelLoader.
    implementation(project(":game-desktop"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    // Reads the app's own JSON files back. Already on the runtime classpath through DJL, same
    // version, licence-checked in docs/LICENSING.md; declared so persistence does not lean on a
    // transitive dependency.
    implementation("com.google.code.gson:gson:2.13.1")

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

val modelsDir: String = rootProject.file("models").absolutePath

compose.desktop {
    application {
        mainClass = "dev.loupe.desktop.MainKt"
        // Where the app looks for the gitignored Laya weights; absent, model features are disabled.
        jvmArgs += listOf("-Dloupe.models.dir=$modelsDir", "-Xmx1g")
    }
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
    systemProperty("pdfbox.fontcache", layout.buildDirectory.dir("pdfbox-cache").get().asFile.absolutePath)
    // PDFBox falls back to the home directory when the folder does not exist, so create it first.
    doFirst { layout.buildDirectory.dir("pdfbox-cache").get().asFile.mkdirs() }
    maxHeapSize = "1g"
    testLogging {
        showStandardStreams = true
    }
}

// Off-screen screenshots of every screen: ./gradlew :loupe-desktop:snapshot -Pout=<dir>
// Uses a throwaway app home (never ~/Library/Application Support/Loupe) and the sample dataset.
tasks.register<JavaExec>("snapshot") {
    group = "application"
    description = "Renders every Loupe screen off-screen to PNG files, over the sample dataset."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.loupe.desktop.SnapshotMainKt")
    systemProperty("loupe.models.dir", modelsDir)
    maxHeapSize = "1g"
    args(
        (project.findProperty("out") as String?) ?: layout.buildDirectory.dir("snapshots").get().asFile.absolutePath,
        layout.buildDirectory.dir("snapshot-home").get().asFile.absolutePath,
    )
}

// Third-party notices (build risk 11): :generateThirdPartyNotices writes
// src/main/resources/THIRD_PARTY_NOTICES.txt (shipped in the app jar) and third-party/notices.lock;
// :checkThirdPartyNotices, part of `check`, fails when either is stale. See docs/LICENSING.md.
apply(from = rootProject.file("gradle/third-party-notices.gradle.kts"))

tasks.test {
    // ThirdPartyNoticesTest re-derives the manifest from the jars that actually resolve.
    val runtime = configurations.getByName("runtimeClasspath")
    inputs.files(runtime).withPropertyName("thirdPartyRuntimeClasspath")
    inputs.dir(rootProject.file("third-party")).withPropertyName("thirdPartyTables")
    systemProperty("loupe.thirdParty.dir", rootProject.file("third-party").absolutePath)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            val jars = runtime.incoming.artifactView { lenient(false) }.artifacts.artifacts
                .mapNotNull { a ->
                    (a.id.componentIdentifier as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)
                        ?.let { "${it.group}:${it.module}:${it.version}=${a.file.absolutePath}" }
                }
                .sorted()
            listOf("-Dloupe.thirdParty.jars=" + jars.joinToString("|"))
        },
    )
}
