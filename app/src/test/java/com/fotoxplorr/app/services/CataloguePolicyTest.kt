package com.fotoxplorr.app.services

import com.fotoxplorr.app.services.catalogue.ArtifactCheck
import com.fotoxplorr.app.services.catalogue.CataloguePolicy
import com.fotoxplorr.app.services.catalogue.NoozCatalogueParser
import com.fotoxplorr.app.services.catalogue.NoozKind
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CataloguePolicyTest {
    private val all = NoozCatalogueParser.parseModels(CatalogueFixtures.models).getOrThrow().models
    private val tiers = NoozCatalogueParser.parseFreeTiers(CatalogueFixtures.tiers).getOrThrow()

    @Test fun defaultShowsOnlyPolicySafe() {
        val shown = CataloguePolicy.visible(all, showAll = false)
        assertTrue(shown.isNotEmpty())
        assertTrue(shown.all { it.policySafe == true })
        assertTrue(shown.none { it.id.contains("abliterated") })
    }

    @Test fun showAllAddsRemixesButNeverTts() {
        val shown = CataloguePolicy.visible(all, showAll = true)
        assertTrue(shown.any { it.id.contains("abliterated") })
        assertTrue(shown.none { it.kind == NoozKind.TTS_ONNX })
        assertTrue(shown.any { it.kind == NoozKind.SD_IMAGE })
        assertEquals(all.count { it.kind != NoozKind.TTS_ONNX }, shown.size)
        assertTrue(CataloguePolicy.hiddenCount(all, showAll = false) > 0)
        assertEquals(0, CataloguePolicy.hiddenCount(all, showAll = true))
    }

    @Test fun nullPolicySafeIsHiddenByDefault() {
        val m = all.first().copy(policySafe = null)
        assertTrue(CataloguePolicy.visible(listOf(m), false).isEmpty())
        assertEquals(1, CataloguePolicy.visible(listOf(m), true).size)
    }

    @Test fun noModelIsOfferedForDownloadBecauseFotozCannotRunAny() {
        assertTrue(all.none(CataloguePolicy::canOfferDownload))
        val nullUrl = all.first { it.id == "gemma3-4b-q4" }
        assertFalse(CataloguePolicy.canOfferDownload(nullUrl))
    }

    @Test fun snapshotAgeText() {
        val today = LocalDate.parse("2026-11-03")
        assertEquals("Catalogue from 2026-07-22, 104 days old.", CataloguePolicy.snapshotAgeText("2026-07-22", today))
        assertEquals("Catalogue from 2026-11-03, today.", CataloguePolicy.snapshotAgeText("2026-11-03", today))
        assertEquals("Catalogue from 2026-11-02, 1 day old.", CataloguePolicy.snapshotAgeText("2026-11-02", today))
        assertEquals("Catalogue date unknown.", CataloguePolicy.snapshotAgeText(null, today))
        assertEquals("Catalogue date unknown.", CataloguePolicy.snapshotAgeText("soon", today))
    }

    @Test fun sizeAndProvenanceNeverInventNumbers() {
        assertEquals("Size unknown", CataloguePolicy.sizeText(null))
        assertEquals("Size unknown", CataloguePolicy.sizeText(0))
        assertEquals("About 1.1 GB", CataloguePolicy.sizeText(1_100_000_000L))
        assertEquals("About 87 MB", CataloguePolicy.sizeText(86_800_000L))
        val m = all.first { it.id == "qwen2.5-1.5b-instruct-q4" }
        assertTrue(CataloguePolicy.provenanceText(m).contains("Qwen/Qwen2.5-1.5B-Instruct-GGUF"))
        assertTrue(CataloguePolicy.provenanceText(m).contains("checked 2026-07-11"))
        assertEquals("Source not stated.", CataloguePolicy.provenanceText(m.copy(hfRepo = null, hfFile = null, verifiedAt = null)))
    }

    @Test fun freeTierLineIsDatedAndNotAPromise() {
        val groq = CataloguePolicy.freeTierFor(tiers.tiers, "groq")
        assertNotNull(groq)
        val line = CataloguePolicy.freeTierLine(groq!!, tiers.lastUpdated)
        assertTrue(line.endsWith("(as of 2026-07-12, check the provider)"))
        assertEquals("openai", com.fotoxplorr.app.ai.AiProviderKind.OPENAI_RESPONSES.freeTierId())
        assertEquals(null, com.fotoxplorr.app.ai.AiProviderKind.OPENAI_COMPATIBLE_CHAT.freeTierId())
        assertTrue(CataloguePolicy.freeTierLine(groq, null).contains("date unknown"))
    }

    @Test fun artifactCheckVerifiesWhenStated() {
        val m = all.first { it.id == "qwen2.5-1.5b-instruct-q4" }
        assertEquals(ArtifactCheck.Verdict.Ok, ArtifactCheck.verify(m, 1_100_000_000L, "whatever"))
        assertTrue(ArtifactCheck.verify(m, 10L, null) is ArtifactCheck.Verdict.Bad)
        val withSha = m.copy(sha256 = "ABC123")
        assertEquals(ArtifactCheck.Verdict.Ok, ArtifactCheck.verify(withSha, 1_100_000_000L, "abc123"))
        assertTrue(ArtifactCheck.verify(withSha, 1_100_000_000L, "zzz") is ArtifactCheck.Verdict.Bad)
        assertEquals(ArtifactCheck.Verdict.Ok, ArtifactCheck.verify(m.copy(sizeBytes = null), 5L, null))
    }
}
