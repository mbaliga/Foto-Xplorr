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

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
