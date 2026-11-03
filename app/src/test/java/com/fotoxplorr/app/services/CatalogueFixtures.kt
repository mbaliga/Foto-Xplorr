package com.fotoxplorr.app.services

import java.io.File

/** The REAL bundled snapshot files, read from the module's assets so the tests break if a refresh breaks the parser. */
internal object CatalogueFixtures {
    private fun asset(name: String) = File("src/main/assets/ai-catalogue/$name")
    val models: String get() = asset("models.json").readText()
    val tiers: String get() = asset("free-tiers.json").readText()
}
