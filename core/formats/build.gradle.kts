// :core:formats -- ADR-010 §1. Magic-byte/MIME format classification (MediaFormat, RawVariant,
// AnimationSniffer). GifDecoder/GifEncoder/GifTrimmer/GifFrame/Lzw stay in :app: they use
// java.io.ByteArrayOutputStream and are not pure, and moving only their shared LzwGif/GifFrame
// helpers here would fragment one tightly-coupled cluster across two modules for no benefit
// (see MASTER-PROGRESS.md's WP1.2 Decisions).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm()

    if (providers.gradleProperty("fotoz.native").isPresent) {
        linuxX64()
        linuxArm64()
    }

    // iOS targets are opt-in (-Pfotoz.ios=true) for the same reason as the native ones above:
    // Apple Kotlin/Native targets only compile on a macOS host, so declaring them unconditionally
    // would warn-and-disable on Linux and pull Apple toolchains into a plain sync. The
    // `ios.yml` workflow (macos-latest) turns this on. See ios/README.md.
    if (providers.gradleProperty("fotoz.ios").isPresent) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
