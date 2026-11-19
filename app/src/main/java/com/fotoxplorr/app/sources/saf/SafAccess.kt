package com.fotoxplorr.app.sources.saf

import com.fotoxplorr.core.index.walker.TreeFailure
import java.io.FileNotFoundException

/** What a folder source's access looks like right now. None of these ever means "delete the photos". */
enum class SafHealth(val message: String?) {
    ONLINE(null),
    OFFLINE(SafCopy.UNREACHABLE),
    REVOKED(SafCopy.REVOKED),
    ERROR(SafCopy.ERROR),
}

/** Pure rules that turn what went wrong into a failure kind and a health. */
object SafAccess {
    /**
     * REVOKED only when the persisted grant is really gone. A provider that throws a
     * [SecurityException] while the grant is still held is an ordinary provider problem (an app
     * that is signed out, a drive that refuses), and calling it REVOKED would tell the person to
     * pick a folder again for no reason. Which exceptions an unmounted drive throws has not been
     * seen on a device; [FileNotFoundException] and [IllegalArgumentException] are the two a
     * missing document or volume raises in the platform's own providers.
     */
    fun classify(error: Throwable, grantHeld: Boolean): TreeFailure = when {
        !grantHeld -> TreeFailure.REVOKED
        error is FileNotFoundException || error is IllegalArgumentException -> TreeFailure.UNREACHABLE
        else -> TreeFailure.ERROR
    }

    /**
     * The health to show after a walk (or any read) ended with [failure] (null = it succeeded).
     * The registry has the last word on REVOKED: a walker failure that says REVOKED while the grant
     * is held is shown as ERROR, and a grant that is gone is REVOKED whatever the walker said.
     */
    fun healthAfter(failure: TreeFailure?, grantHeld: Boolean): SafHealth = when {
        !grantHeld -> SafHealth.REVOKED
        failure == null -> SafHealth.ONLINE
        failure == TreeFailure.UNREACHABLE -> SafHealth.OFFLINE
        else -> SafHealth.ERROR
    }
}
