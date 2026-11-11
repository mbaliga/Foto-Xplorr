// :core:metadata -- ADR-010 §1. `MetadataStripper` (commonMain, behind ByteSink) is native-ready;
// `XmpPacket` (jvmMain: org.w3c.dom + javax.xml) is JVM-only for now (ADR-010 consequences: a
// native XMP parser is Phase 8 prep, not Phase 1).
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
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // JUnit4, not kotlin.test: XmpPacketTest is jvmMain-only content (jvmMain itself is
        // org.w3c.dom/javax.xml), moved here unchanged per ADR-010 §3 ("JUnit4 tests go to
        // jvmTest unchanged") rather than ported, since it has nowhere else to run.
        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
        }
    }
}
