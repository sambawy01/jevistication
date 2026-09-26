import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

// Loupe for Android (docs/ANDROID-PLAN.md): an Android library target beside JVM and iOS, only where
// settings.gradle.kts found an Android SDK (it says so when it did not).
val androidHost = gradle.extra["loupe.android"] as Boolean
if (androidHost) apply(plugin = "com.android.library")

repositories {
    mavenCentral()
    google()
}

// iOS targets only on macOS; see engine/build.gradle.kts.
val appleHost = System.getProperty("os.name").startsWith("Mac")

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    if (androidHost) {
        androidTarget {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
    if (appleHost) {
        iosArm64()
        iosSimulatorArm64()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // The template library is pure Kotlin over the engine's types and adds no third-party
            // code: a template is data plus the authoring path every user judgment goes through.
            api(project(":engine"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("org.junit.jupiter:junit-jupiter:5.11.4")
            runtimeOnly("org.junit.platform:junit-platform-launcher")
        }
    }
}

if (androidHost) {
    extensions.configure<com.android.build.api.dsl.LibraryExtension> {
        namespace = "dev.loupe.templates"
        compileSdk = 36
        defaultConfig {
            minSdk = 29
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
