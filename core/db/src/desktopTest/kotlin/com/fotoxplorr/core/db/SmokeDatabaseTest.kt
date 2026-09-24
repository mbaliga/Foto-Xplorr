package com.fotoxplorr.core.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WP1.2's whole acceptance for this module (ADR-010): Room + KSP + BundledSQLiteDriver actually
 * work end to end on the JVM. Nothing here is the real catalogue v2 schema -- see SmokeDatabase's
 * own doc.
 */
class SmokeDatabaseTest {

    @Test
    fun `insert then read back round-trips through Room and BundledSQLiteDriver`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<SmokeDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

        db.dao().insert(SmokeRow(id = 1, value = "hello"))
        val row = db.dao().get(1)

        assertEquals(SmokeRow(1, "hello"), row)
        db.close()
    }
}
