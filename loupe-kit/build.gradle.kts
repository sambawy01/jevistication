import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

// LoupeKit: the iOS-facing umbrella over :engine, :templates and :game, shipped as one XCFramework.
// `./gradlew :loupe-kit:assembleLoupeKitXCFramework` (macOS only; see docs/BUILD.md).
val appleHost = System.getProperty("os.name").startsWith("Mac")
// Laya on iOS (ORT + the Rust tokenizer) is exported when settings.gradle.kts included it, i.e. once
// ios-native/build.sh has run. Without it LoupeKit builds as before and the app cannot link Laya.
val layaIos = rootProject.findProject(":backend-onnx-ios")

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
                export(project(":game"))
                if (layaIos != null) {
                    export(layaIos)
                    export(project(":backend-laya-common"))
                }
                xcf.add(this)
            }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":engine"))
            api(project(":templates"))
            // The game (Riverflight) for the iPhone app: rules, pilots, GameSessions, HostedDecider.
            api(project(":game"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        if (appleHost && layaIos != null) {
            iosMain {
                // Only compiled when Laya for iOS is present, since it calls into it.
                kotlin.srcDir("src/iosLaya/kotlin")
                dependencies {
                    api(layaIos)
                    api(project(":backend-laya-common"))
                }
            }
        }
    }
}
