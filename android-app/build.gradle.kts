import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
    // The Compose compiler ships with Kotlin since 2.0; its plugin version is the Kotlin version.
    kotlin("plugin.compose")
}

repositories {
    google()
    mavenCentral()
}

// Loupe for Android (docs/ANDROID-PLAN.md): a Jetpack Compose app over the shared KMP modules,
// which reach it through :loupe-kit as they reach the iPhone app through LoupeKit.
//
// Size budget: no model in the APK (the Loupe Decision Model is a consented download, A2), and no
// dependency beyond Compose and what the shared modules need.
android {
    namespace = "com.loupeai.android"
    // Google Play requires new apps and updates to target API 36 from 2026-08-31.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.loupeai.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-a0"
    }

    buildTypes {
        release {
            // Shrinking is on so a release build shows the real size; signing comes at A9.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // The AndroidX pins follow Kotlin 2.1.0 (see dependencies); lint's "newer version available"
        // would ask for libraries built with a newer Kotlin than the repo's.
        disable += "GradleDependency"
    }

    packaging {
        resources {
            // License texts that several AndroidX/Kotlin jars carry under the same path.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Compose's one tiny native library (androidx.graphics.path, ~10 KB) ships stripped;
            // without an NDK AGP cannot strip it again and says so on every build.
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // engine, templates, persistence, sources-common and game, as api dependencies of loupe-kit.
    implementation(project(":loupe-kit"))

    // Jetpack Compose. The BOM pins the 1.7 line, the Jetpack release Compose Multiplatform 1.7.3
    // (the desktop game's) is built on, compiled by Kotlin 2.1.0's Compose compiler.
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
