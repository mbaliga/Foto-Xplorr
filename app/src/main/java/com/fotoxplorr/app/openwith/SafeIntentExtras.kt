package com.fotoxplorr.app.openwith

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.os.BundleCompat
import com.fotoxplorr.app.FotoXplorrActivity

/**
 * Makes reading this incoming intent's extras safe, and returns it.
 *
 * Another app controls what an exported activity's intent carries, and the extras stay packed
 * until the first `getXExtra` touches them: a hostile one (a class this app cannot load, a
 * parcelable that throws) then surfaces as a `BadParcelableException` from an innocent-looking
 * `getLongExtra`, in `onCreate`, every time the activity is relaunched from Recents.
 *
 * So the extras are rebuilt from an ALLOWLIST: only the keys the three exported activities really
 * read ([FotoXplorrActivity.EXTRA_OPEN_MEDIA_ID], [Intent.EXTRA_ALLOW_MULTIPLE] and
 * [Intent.EXTRA_STREAM], as one [Uri] or a list of them) are read, each through a typed, guarded
 * getter, into a fresh small bundle that replaces the original. Every other key is dropped
 * unread, so a parcelable under an unknown key is never instantiated by this app, however hostile.
 * If reading an allowed key fails, the extras are dropped altogether: the request is read as
 * carrying none, which every caller already treats as "nothing asked".
 *
 * Before API 33 a `Bundle` unpacks every value the first time anything touches it, so there a
 * hostile value under any key still fails here (and the extras are dropped), rather than staying
 * unread; from 33 values are unpacked one key at a time, and the allowlist keeps the rest packed.
 *
 * The intent's data, action and clip data are untouched.
 */
fun Intent.withSafeExtras(): Intent {
    val rebuilt: Bundle? = runCatching { extras?.let(::allowlisted) }.getOrNull()
    // On any failure, and for an intent that carried nothing, there is nothing to keep.
    replaceExtras(rebuilt)
    return this
}

/** A new bundle holding only the allowlisted keys of [source], or null if there are none. Throws on a hostile value. */
private fun allowlisted(source: Bundle): Bundle? {
    val safe = Bundle()
    val openMediaId = source.getLong(FotoXplorrActivity.EXTRA_OPEN_MEDIA_ID, ABSENT_ID)
    if (openMediaId != ABSENT_ID) safe.putLong(FotoXplorrActivity.EXTRA_OPEN_MEDIA_ID, openMediaId)
    // Absent reads as false, so only a true needs carrying over.
    if (source.getBoolean(Intent.EXTRA_ALLOW_MULTIPLE, false)) safe.putBoolean(Intent.EXTRA_ALLOW_MULTIPLE, true)
    // One Uri (ACTION_SEND) or a list of them (ACTION_SEND_MULTIPLE); a value of the other shape
    // or of another type reads as null from the typed getter, which is "no stream".
    val single = BundleCompat.getParcelable(source, Intent.EXTRA_STREAM, Uri::class.java)
    if (single != null) {
        safe.putParcelable(Intent.EXTRA_STREAM, single)
    } else {
        BundleCompat.getParcelableArrayList(source, Intent.EXTRA_STREAM, Uri::class.java)
            ?.let { list -> safe.putParcelableArrayList(Intent.EXTRA_STREAM, ArrayList(list.filterNotNull())) }
    }
    return safe.takeUnless { it.isEmpty }
}

/** Not a row id MediaStore hands out; the same default the reader of the extra uses. */
private const val ABSENT_ID = -1L
