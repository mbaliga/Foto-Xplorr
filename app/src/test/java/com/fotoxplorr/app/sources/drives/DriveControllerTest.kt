package com.fotoxplorr.app.sources.drives

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePort : DriveSourcePort {
    val known = MutableStateFlow<Set<String>>(emptySet())
    val calls = java.util.Collections.synchronizedList(ArrayList<String>())
    override fun knownVolumes(): Flow<Set<String>> = known
    override suspend fun setReachable(uuid: String, reachable: Boolean, indexNow: Boolean) {
        calls += "reachable $uuid $reachable $indexNow"
    }
    override suspend fun stopIndex(uuid: String) {
        calls += "stop $uuid"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DriveControllerTest {
    private val sd = DriveVolume("1234-ABCD", "SD")
    private val store = InMemoryDriveSettingsStore()
    private val port = FakePort()
    private val controller = DriveController(store, port, CoroutineScope(Dispatchers.Unconfined)).also { it.start() }

    private suspend fun settled(expected: Int) = withTimeout(5_000) {
        while (port.calls.size < expected) kotlinx.coroutines.delay(5)
    }

    @Test
    fun `nothing is asked of the sources side until the person answers`() = runBlocking {
        controller.dispatch(DriveEvent.Snapshot(listOf(sd)))
        assertEquals(listOf(sd), controller.offers.value)
        assertTrue(port.calls.isEmpty())
        assertTrue(store.all().value.isEmpty())
    }

    @Test
    fun `add emits one picker request and saves that the drive was asked`() = runBlocking {
        val seen = ArrayList<DriveVolume>()
        val job = CoroutineScope(Dispatchers.Unconfined).launchCollect(controller, seen)
        controller.dispatch(DriveEvent.Snapshot(listOf(sd)))
        controller.choose(sd.uuid, DriveChoice.ADD)
        withTimeout(5_000) { while (seen.isEmpty()) kotlinx.coroutines.delay(5) }
        assertEquals(listOf(sd), seen)
        assertTrue(store.get(sd.uuid).asked)
        job.cancel()
    }

    @Test
    fun `never is saved and then listed as ignored until asked again`() = runBlocking {
        controller.dispatch(DriveEvent.Snapshot(listOf(sd)))
        controller.choose(sd.uuid, DriveChoice.NEVER)
        withTimeout(5_000) { while (!store.get(sd.uuid).never) kotlinx.coroutines.delay(5) }
        assertEquals(listOf(IgnoredDrive(sd.uuid, "SD")), controller.ignored().first())
        controller.askAgain(sd.uuid)
        withTimeout(5_000) { while (store.get(sd.uuid).never) kotlinx.coroutines.delay(5) }
        assertTrue(controller.ignored().first().isEmpty())
        assertEquals(listOf(sd), controller.offers.value)
    }

    @Test
    fun `unplug and replug of a known drive reaches the port in order`() = runBlocking {
        port.known.value = setOf(sd.uuid)
        withTimeout(5_000) { while (true) { if (port.calls.isNotEmpty()) break; kotlinx.coroutines.delay(5) } }
        port.calls.clear()
        controller.dispatch(DriveEvent.Snapshot(listOf(sd)))
        controller.dispatch(DriveEvent.Snapshot(emptyList()))
        controller.dispatch(DriveEvent.Snapshot(listOf(sd)))
        settled(4)
        assertEquals(
            listOf("reachable ${sd.uuid} true false", "stop ${sd.uuid}", "reachable ${sd.uuid} false false", "reachable ${sd.uuid} true false"),
            port.calls.toList(),
        )
        assertTrue("a recognised drive is never offered", controller.offers.value.isEmpty())
    }
}

private fun CoroutineScope.launchCollect(c: DriveController, into: MutableList<DriveVolume>) =
    this.launch { c.pickerRequests.collect { into += it } }
