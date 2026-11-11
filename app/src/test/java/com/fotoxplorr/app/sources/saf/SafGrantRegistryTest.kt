package com.fotoxplorr.app.sources.saf

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.core.index.walker.TreeFailure
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafGrantRegistryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun tree(n: Int): Uri = Uri.parse("content://fotoz.test.docs/tree/vol$n%3AFolder")

    @Test
    fun takeListAndForgetRoundTrip() {
        val registry = SafGrantRegistry(context.contentResolver)
        val uri = tree(1)
        assertFalse(registry.isHeld(uri))
        val outcome = registry.take(uri) as GrantOutcome.Taken
        assertEquals(1, outcome.held)
        assertEquals(512, outcome.limit)
        assertTrue(registry.isHeld(uri))
        assertEquals(listOf(uri.toString()), registry.list().map { it.treeUri })
        assertTrue(registry.forget(uri))
        assertFalse(registry.isHeld(uri))
        assertEquals(0, registry.count())
    }

    @Test
    fun nearTheCapANewFolderIsRefusedWithAPlainSentence() {
        val registry = SafGrantRegistry(context.contentResolver, sdkInt = 29) // limit 128
        repeat(128 - SafGrantPool.RESERVE) { assertTrue(registry.take(tree(it)) is GrantOutcome.Taken) }
        val refused = registry.take(tree(9999)) as GrantOutcome.Refused
        assertEquals(SafCopy.NEAR_GRANT_LIMIT, refused.reason)
        // A grant already held can always be taken again.
        assertTrue(registry.take(tree(0)) is GrantOutcome.Taken)
    }

    @Test
    fun poolLimits() {
        assertEquals(128, SafGrantPool.limitFor(29))
        assertEquals(512, SafGrantPool.limitFor(30))
        assertTrue(SafGrantPool.mayTakeAnother(119, 128))
        assertFalse(SafGrantPool.mayTakeAnother(120, 128))
    }

    @Test
    fun revokedIsOnlyReportedWhenTheGrantIsReallyGone() {
        assertEquals(TreeFailure.REVOKED, SafAccess.classify(SecurityException(), grantHeld = false))
        assertEquals(TreeFailure.ERROR, SafAccess.classify(SecurityException(), grantHeld = true))
        assertEquals(TreeFailure.UNREACHABLE, SafAccess.classify(FileNotFoundException(), grantHeld = true))
        assertEquals(TreeFailure.ERROR, SafAccess.classify(java.io.IOException(), grantHeld = true))
        assertEquals(SafHealth.REVOKED, SafAccess.healthAfter(null, grantHeld = false))
        assertEquals(SafHealth.REVOKED, SafAccess.healthAfter(TreeFailure.ERROR, grantHeld = false))
        assertEquals(SafHealth.ERROR, SafAccess.healthAfter(TreeFailure.REVOKED, grantHeld = true))
        assertEquals(SafHealth.OFFLINE, SafAccess.healthAfter(TreeFailure.UNREACHABLE, grantHeld = true))
        assertEquals(SafHealth.ONLINE, SafAccess.healthAfter(null, grantHeld = true))
    }
}
