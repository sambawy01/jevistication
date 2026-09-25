import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

// The agent tier (docs/AGENT.md): the paid layer that turns what the local engine already decided
// into prepared actions a person approves. Shared Kotlin, so the desktop and the iPhone run the
// same guard and the same workflow rather than two drifting copies.
//
// It depends on :loupe-kit for the Review queue (the agent's only output channel) and, through it,
// on :engine. It adds no third-party dependency: the wire format is JSON, and :persistence already
// carries the hand-rolled reader and writer the iOS side needs.
val appleHost = System.getProperty("os.name").startsWith("Mac")

kotlin {
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
            api(project(":loupe-kit"))
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

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
