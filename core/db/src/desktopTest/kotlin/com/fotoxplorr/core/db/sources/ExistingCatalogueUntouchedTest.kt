package com.fotoxplorr.core.db.sources

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-020: adding fotoz-sources.db must not change fotoz.db or fotoz-vectors.db. Pins the
 * declared versions, the exported schema versions and the exported identity hashes, so any
 * change to those two databases (which belongs to the Room cut-over, not to Phase 3) fails here.
 */
class ExistingCatalogueUntouchedTest {

    private val moduleDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "schemas").isDirectory && File(it, "src/commonMain").isDirectory }

    private fun schema(db: String, version: Int) = File(moduleDir, "schemas/com.fotoxplorr.core.db.$db/$version.json").readText()

    @Test
    fun `fotoz db stays at version 1 with its exported schema`() {
        assertEquals(listOf("1.json"), File(moduleDir, "schemas/com.fotoxplorr.core.db.FotozDatabase").list()!!.sorted())
        val json = schema("FotozDatabase", 1)
        assertTrue(Regex("\"version\":\\s*1,").containsMatchIn(json))
        assertTrue(json.contains("\"identityHash\": \"dfc23a93e5802f26e1bab8a0ecda64ed\""))
        val src = File(moduleDir, "src/commonMain/kotlin/com/fotoxplorr/core/db/FotozDatabase.kt").readText()
        assertTrue(Regex("version = 1,").containsMatchIn(src))
        // The new database's entities are not part of it.
        assertTrue(!json.contains("\"source_asset\"") && !json.contains("\"index_run\""))
    }

    @Test
    fun `fotoz vectors db stays at version 1 with its exported schema`() {
        assertEquals(listOf("1.json"), File(moduleDir, "schemas/com.fotoxplorr.core.db.FotozVectorsDatabase").list()!!.sorted())
        assertTrue(Regex("\"version\":\\s*1,").containsMatchIn(schema("FotozVectorsDatabase", 1)))
    }

    @Test
    fun `sources db has its own schema directory at version 1`() {
        assertEquals(listOf("1.json"), File(moduleDir, "schemas/com.fotoxplorr.core.db.sources.FotozSourcesDatabase").list()!!.sorted())
        val json = schema("sources.FotozSourcesDatabase", 1)
        assertTrue(json.contains("\"source_asset\"") && json.contains("\"index_run\"") && json.contains("\"source_folder\""))
    }
}
