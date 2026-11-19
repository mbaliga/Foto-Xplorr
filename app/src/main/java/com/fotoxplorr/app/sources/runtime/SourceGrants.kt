package com.fotoxplorr.app.sources.runtime

import android.net.Uri
import com.fotoxplorr.app.sources.saf.GrantOutcome
import com.fotoxplorr.app.sources.saf.SafGrantRegistry

/**
 * The persisted folder grants a source needs, in plain strings so a JVM test can fake them. The
 * production one is [SafSourceGrants] over [SafGrantRegistry]. None of it can read, write or
 * delete a document.
 */
interface SourceGrants {
    /** True while the platform still lets the app read [treeUri]. */
    fun isHeld(treeUri: String): Boolean

    /** Keeps the grant the folder picker just returned. */
    fun take(treeUri: String): GrantOutcome

    /** Gives the platform its slot back. Only the runtime's remove rule decides when. */
    fun forget(treeUri: String): Boolean

    /** True when another feature (sync, sidecars, backup) holds a write grant on the same tree, so it must stay. */
    fun usedByOtherFeatures(treeUri: String): Boolean
}

class SafSourceGrants(private val registry: SafGrantRegistry) : SourceGrants {
    override fun isHeld(treeUri: String): Boolean = registry.isHeld(treeUri)

    override fun take(treeUri: String): GrantOutcome = registry.take(Uri.parse(treeUri))

    override fun forget(treeUri: String): Boolean = registry.forget(Uri.parse(treeUri))

    // Sync, the sidecar route and the backup destination take a read AND write grant; a source takes
    // read only. A write bit on the tree therefore means someone else is using it.
    override fun usedByOtherFeatures(treeUri: String): Boolean =
        registry.list().any { it.treeUri == treeUri && it.canWrite }
}
