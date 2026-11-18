package com.fotoxplorr.ios

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin half of the Swift<->Kotlin wire contract. The JSON files in `ios/contract/` are the exact bytes
 * this facade emits (the `.response.json` ones) for inputs the Swift package also uses (the `.request.json` ones,
 * built there from `LibraryAsset`). `ios/FotozKit`'s tests decode the SAME files, so a change to
 * either side's JSON shape fails a test on the side that did not change.
 *
 * Regenerate after an intended change: `FOTOZ_REGEN_CONTRACT=1 ./gradlew :ios:core:jvmTest`,
 * then review the diff -- it is the API change.
 */
class ContractFixturesTest {
    private val dir = File("../contract").also { assertTrue(it.isDirectory, "run from :ios:core (cwd=${File(".").absolutePath})") }
    private val regen = System.getenv("FOTOZ_REGEN_CONTRACT") == "1"

    private fun check(name: String, actual: String) {
        val file = File(dir, name)
        if (regen) { file.writeText(actual + "\n"); return }
        assertEquals(file.readText().trimEnd(), actual, "contract drift in $name -- regenerate and review")
    }

    private fun req(name: String) = File(dir, name).readText().trim()

    @Test fun parseQuery() =
        check("parse_query.response.json", FotozCore.parseQuery("flowers or folder:holiday not type:video August 2025", "UTC", "2026-11-17"))

    @Test fun search() =
        check("search.response.json", FotozCore.search("folder:holiday size:>1mb", "UTC", "2026-11-17", req("documents.request.json")))

    @Test fun classify() =
        check("classify.response.json", FotozCore.classify("image/heic", "IMG_0001.HEIC"))

    @Test fun planRename() =
        check("plan_rename.response.json", FotozCore.planRename("Trip_{counter:2}", req("rename_subjects.request.json"), 1, """["Trip_01.HEIC"]""", "UTC"))

    @Test fun errorEnvelope() =
        check("error.response.json", FotozCore.planRename("", "[]", 1, "[]", "UTC"))
}
