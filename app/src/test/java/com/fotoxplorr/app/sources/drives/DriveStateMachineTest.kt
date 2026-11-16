package com.fotoxplorr.app.sources.drives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveStateMachineTest {
    private val sd = DriveVolume("1234-ABCD", "Holiday SD")
    private val usb = DriveVolume("ABCD-0001", "Backup stick")
    private val sameLabel = DriveVolume("FFFF-9999", "Holiday SD")

    /** Replays [events] and returns the final state with every effect, in order. */
    private fun replay(start: DriveState = DriveState(), vararg events: DriveEvent): Pair<DriveState, List<DriveEffect>> {
        var s = start
        val all = ArrayList<DriveEffect>()
        for (e in events) {
            val step = DriveStateMachine.reduce(s, e)
            s = step.state
            all += step.effects
        }
        return s to all
    }

    private fun connected(vararg v: DriveVolume) = DriveEvent.Snapshot(v.toList())

    @Test
    fun `an unknown drive is offered once and nothing else happens`() {
        val (s, fx) = replay(events = arrayOf(connected(sd)))
        assertEquals(listOf(sd), s.offers)
        assertTrue("no scan, no picker, no write before an answer", fx.isEmpty())
    }

    @Test
    fun `the same snapshot twice offers once`() {
        val (s, _) = replay(events = arrayOf(connected(sd), connected(sd)))
        assertEquals(listOf(sd), s.offers)
    }

    @Test
    fun `add opens the picker for that drive and remembers it was asked`() {
        val (s, fx) = replay(events = arrayOf(connected(sd), DriveEvent.Chose(sd.uuid, DriveChoice.ADD)))
        assertTrue(s.offers.isEmpty())
        assertEquals(listOf(DriveEffect.SaveSettings(sd.uuid, DriveSettings(label = "Holiday SD", asked = true)), DriveEffect.OpenPicker(sd)), fx)
        assertFalse("indexing on connect stays off", s.settings.getValue(sd.uuid).indexOnConnect)
    }

    @Test
    fun `not now closes the card and asks again only after a replug`() {
        val (s, fx) = replay(events = arrayOf(connected(sd), DriveEvent.Chose(sd.uuid, DriveChoice.NOT_NOW), connected(sd)))
        assertTrue("not offered again while it stays plugged in", s.offers.isEmpty())
        assertTrue(fx.none { it is DriveEffect.OpenPicker })
        val (again, _) = replay(s, connected(), connected(sd))
        assertEquals(listOf(sd), again.offers)
    }

    @Test
    fun `never for this drive is remembered across replugs and across a restart`() {
        val (s, _) = replay(events = arrayOf(connected(sd), DriveEvent.Chose(sd.uuid, DriveChoice.NEVER)))
        assertTrue(s.settings.getValue(sd.uuid).never)
        val (replugged, fx) = replay(s, connected(), connected(sd))
        assertTrue(replugged.offers.isEmpty())
        assertTrue(fx.isEmpty())
        // A fresh process with only the saved settings behaves the same.
        val (restarted, _) = replay(DriveState(), DriveEvent.SettingsLoaded(s.settings), connected(sd))
        assertTrue(restarted.offers.isEmpty())
    }

    @Test
    fun `never for one drive does not silence another`() {
        val (s, _) = replay(events = arrayOf(connected(sd, usb), DriveEvent.Chose(sd.uuid, DriveChoice.NEVER)))
        assertEquals(listOf(usb), s.offers)
    }

    @Test
    fun `ask again after never offers a plugged drive again`() {
        val (s, _) = replay(events = arrayOf(connected(sd), DriveEvent.Chose(sd.uuid, DriveChoice.NEVER), DriveEvent.AskAgain(sd.uuid)))
        assertEquals(listOf(sd), s.offers)
        assertFalse(s.settings.getValue(sd.uuid).never)
    }

    @Test
    fun `two drives queue in plug order and each is answered on its own`() {
        val (s, _) = replay(events = arrayOf(connected(sd), connected(sd, usb)))
        assertEquals(listOf(sd, usb), s.offers)
        val (after, fx) = replay(s, DriveEvent.Chose(usb.uuid, DriveChoice.ADD))
        assertEquals(listOf(sd), after.offers)
        assertEquals(listOf(DriveEffect.SaveSettings(usb.uuid, DriveSettings(label = "Backup stick", asked = true)), DriveEffect.OpenPicker(usb)), fx)
    }

    @Test
    fun `a different drive with the same label is a new drive`() {
        val known = DriveState(known = setOf(sd.uuid))
        val (s, fx) = replay(known, connected(sameLabel))
        assertEquals("offered as a new source", listOf(sameLabel), s.offers)
        assertTrue("and the first drive's source is not touched", fx.none { it is DriveEffect.SetReachable && it.uuid == sd.uuid && it.reachable })
    }

    @Test
    fun `a drive with a source is recognised on replug and nothing is offered`() {
        val (s, fx) = replay(DriveState(known = setOf(sd.uuid)), connected(sd))
        assertTrue(s.offers.isEmpty())
        assertEquals(listOf<DriveEffect>(DriveEffect.SetReachable(sd.uuid, reachable = true, indexNow = false)), fx)
    }

    @Test
    fun `unplug marks the source offline and stops a read and deletes nothing`() {
        val (s, fx) = replay(DriveState(known = setOf(sd.uuid)), connected(sd), connected())
        assertEquals(listOf(DriveEffect.SetReachable(sd.uuid, true), DriveEffect.StopIndex(sd.uuid), DriveEffect.SetReachable(sd.uuid, false)), fx)
        assertTrue(fx.all { it is DriveEffect.SetReachable || it is DriveEffect.StopIndex })
        assertTrue("the source is still known", sd.uuid in s.known)
    }

    @Test
    fun `unplug mid index then replug comes back online and reads only if the person asked`() {
        val known = DriveState(known = setOf(sd.uuid))
        val (_, plain) = replay(known, connected(sd), connected(), connected(sd))
        assertEquals(DriveEffect.SetReachable(sd.uuid, true, indexNow = false), plain.last())
        val asked = known.copy(settings = mapOf(sd.uuid to DriveSettings(indexOnConnect = true)))
        val (_, withIndex) = replay(asked, connected(sd), connected(), connected(sd))
        assertEquals(DriveEffect.SetReachable(sd.uuid, true, indexNow = true), withIndex.last())
    }

    @Test
    fun `unplugging an offered drive withdraws the card`() {
        val (s, fx) = replay(events = arrayOf(connected(sd), connected()))
        assertTrue(s.offers.isEmpty())
        assertTrue("it had no source, so nothing to mark", fx.isEmpty())
    }

    @Test
    fun `a source known after the first look meets its drive as it is`() {
        // The runtime's list arrives after the first snapshot: the offer is withdrawn and the state fixed.
        val (s, fx) = replay(events = arrayOf(connected(sd), DriveEvent.SourcesChanged(setOf(sd.uuid))))
        assertTrue(s.offers.isEmpty())
        assertEquals(listOf<DriveEffect>(DriveEffect.SetReachable(sd.uuid, true)), fx)
        val (_, gone) = replay(events = arrayOf(connected(), DriveEvent.SourcesChanged(setOf(sd.uuid))))
        assertEquals(listOf<DriveEffect>(DriveEffect.SetReachable(sd.uuid, false)), gone)
    }

    @Test
    fun `removing a source does not make the plugged drive ask again until it is replugged`() {
        val (s, _) = replay(DriveState(known = setOf(sd.uuid)), connected(sd), DriveEvent.SourcesChanged(emptySet()))
        assertTrue(s.offers.isEmpty())
        val (again, _) = replay(s, connected(), connected(sd))
        assertEquals(listOf(sd), again.offers)
    }

    @Test
    fun `a cancelled picker is not now, an added one is done`() {
        val (cancelled, _) = replay(events = arrayOf(connected(sd), DriveEvent.Chose(sd.uuid, DriveChoice.ADD), DriveEvent.PickerClosed(sd.uuid, added = false), connected(sd)))
        assertTrue(cancelled.offers.isEmpty())
        val (added, _) = replay(events = arrayOf(connected(sd), DriveEvent.Chose(sd.uuid, DriveChoice.ADD), DriveEvent.PickerClosed(sd.uuid, added = true), DriveEvent.SourcesChanged(setOf(sd.uuid))))
        assertTrue(added.picking.isEmpty())
        assertTrue(added.offers.isEmpty())
    }

    @Test
    fun `ids compare by upper case and the internal volume is never a drive`() {
        assertEquals("1234-ABCD", DriveIds.normalise(" 1234-abcd "))
        assertEquals(null, DriveIds.normalise("primary"))
        assertEquals(null, DriveIds.normalise(null))
        assertEquals(null, DriveIds.normalise("  "))
        val (s, _) = replay(DriveState(known = setOf("1234-ABCD")), DriveEvent.SourcesChanged(setOf("1234-abcd")))
        assertEquals(setOf("1234-ABCD"), s.known)
    }

    @Test
    fun `only mounted removable drives with a uuid are drives`() {
        val raw = listOf(
            RawVolume("1234-abcd", "SD card", removable = true, primary = false, mounted = true),
            RawVolume("1234-ABCD", "SD card again", removable = true, primary = false, mounted = true),
            RawVolume(null, "Internal", removable = false, primary = true, mounted = true),
            RawVolume("AAAA-0000", "Ejected", removable = true, primary = false, mounted = false),
            RawVolume(null, "No id", removable = true, primary = false, mounted = true),
            RawVolume("BBBB-1111", "", removable = true, primary = false, mounted = true),
        )
        assertEquals(listOf(DriveVolume("1234-ABCD", "SD card"), DriveVolume("BBBB-1111", "BBBB-1111")), DriveVolumes.fromRaw(raw))
    }
}
