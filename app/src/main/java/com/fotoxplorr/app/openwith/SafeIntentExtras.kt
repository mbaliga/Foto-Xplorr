package com.fotoxplorr.app.openwith

import android.content.Intent
import android.os.Bundle

/**
 * Makes reading this incoming intent's extras safe, and returns it.
 *
 * Another app controls what an exported activity's intent carries, and the extras stay packed
 * until the first `getXExtra` touches them: a hostile one (a class this app cannot load, a
 * parcelable that throws) then surfaces as a `BadParcelableException` from an innocent-looking
 * `getLongExtra`, in `onCreate`, every time the activity is relaunched from Recents. So the whole
 * bundle is unpacked here once, inside a guard, and if that fails the extras are dropped: the
 * request is read as carrying none, which every caller already treats as "nothing asked".
 *
 * The unpacking is done on the copy `extras` hands back, so the intent itself is untouched when
 * the extras are fine.
 */
fun Intent.withSafeExtras(): Intent {
    val readable = runCatching {
        extras?.let { bundle ->
            for (key in bundle.keySet()) {
                @Suppress("DEPRECATION")
                bundle.get(key)
            }
        }
    }.isSuccess
    if (!readable) replaceExtras(null as Bundle?)
    return this
}
