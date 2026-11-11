package com.fotoxplorr.app.media

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * How many full-size simple-format decodes hold memory at once ([TileSizing.TILE_INPUT]): up to [slots] light ones
 * (a tile under the tile caps) run [shared], a heavy one (a tile past them, or the viewer) runs [alone], holding
 * every slot. The semaphore is FIFO, so a waiting heavy decode is not starved by tiles that ask after it; heavy
 * decodes queue on [whole] first, so two of them never hold half the slots each.
 */
internal class SimpleDecodeGate(private val slots: Int) {
    private val permits = Semaphore(slots)
    private val whole = Mutex()

    suspend fun <T> shared(block: suspend () -> T): T = permits.withPermit { block() }

    suspend fun <T> alone(block: suspend () -> T): T = whole.withLock {
        var held = 0
        try {
            repeat(slots) {
                permits.acquire()
                held++
            }
            block()
        } finally {
            repeat(held) { permits.release() }
        }
    }
}
