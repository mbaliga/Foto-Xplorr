// :ios:core -- the Swift-facing facade over the pure :core:* modules (iOS port, docs/handoff/
// IOS-PORT.md). Everything Swift can call is a String-in/String-out JSON function in commonMain
// (FotozCore.kt), so the whole surface is exercised by plain jvmTest on any host; the Apple
// targets only add the XCFramework wrapper ("FotozCore.xcframework") the SwiftUI shell links.
//
// Apple targets are opt-in (-Pfotoz.ios=true, needs a macOS host: Kotlin/Native cannot build
// Apple targets elsewhere), matching every :core:* module's own opt-in -- see ios/README.md.
// Toolchain: stays on the repo's Kotlin 2.1.0 pin (ADR-009). The program's OQ-38 "Kotlin 2.2.21 +
// CMP 1.9.3" ruling is about Compose Multiplatform iOS, which this port does not use (SwiftUI
// shell); bumping is an owner decision tied to the constellation toolchain upgrade.
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    jvm()

    if (providers.gradleProperty("fotoz.ios").isPresent) {
        val xcf = XCFramework("FotozCore")
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "FotozCore"
                // Static: one self-contained slice per platform, no embed-and-sign dance for a
                // dynamic framework, and nothing for the app to dlopen at runtime.
                isStatic = true
                xcf.add(this)
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:formats"))
            implementation(project(":core:search"))
            implementation(project(":core:organize"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
