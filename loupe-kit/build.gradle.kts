import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

// LoupeKit: the iOS-facing umbrella over :engine and :templates, shipped as one XCFramework.
// `./gradlew :loupe-kit:assembleLoupeKitXCFramework` (macOS only; see docs/BUILD.md).
val appleHost = System.getProperty("os.name").startsWith("Mac")

kotlin {
    // A JVM target keeps the module an ordinary participant in `./gradlew build` on Linux CI.
    jvm()

    if (appleHost) {
        val xcf = XCFramework("LoupeKit")
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "LoupeKit"
                isStatic = true
                export(project(":engine"))
                export(project(":templates"))
                xcf.add(this)
            }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":engine"))
            api(project(":templates"))
        }
    }
}
