import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

// The app's own files (A5 ledger, corrections, user judgments) and the F4 export, shared by the
// desktop app and the iPhone (epic #7 child 1). The formats are the desktop's, byte for byte; file
// I/O is expect/actual (java.nio on the JVM, POSIX + Foundation on iOS). No third-party code.
val appleHost = System.getProperty("os.name").startsWith("Mac")

kotlin {
    // PlatformFiles and StoreLock are expect/actual declarations (Beta in Kotlin 2.1).
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
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

    sourceSets {
        commonMain.dependencies {
            api(project(":engine"))
            api(project(":templates"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("org.junit.jupiter:junit-jupiter:5.11.4")
            // Test-only: proves the common writer matches the Gson the desktop wrote with.
            implementation("com.google.code.gson:gson:2.13.1")
            runtimeOnly("org.junit.platform:junit-platform-launcher")
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
