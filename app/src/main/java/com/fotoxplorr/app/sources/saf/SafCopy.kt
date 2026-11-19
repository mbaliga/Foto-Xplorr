package com.fotoxplorr.app.sources.saf

/** The sentences the folder-source access layer can put in front of a person. One short sentence each. */
object SafCopy {
    const val GRANT_REFUSED = "Android would not keep access to that folder."
    const val NEAR_GRANT_LIMIT = "Android only lets Fotoz keep access to so many folders, so remove one before adding another."
    const val REVOKED = "Fotoz lost access to this folder, so pick it again to bring it back."
    const val UNREACHABLE = "This folder is not available right now, maybe because the drive is not connected."
    const val ERROR = "The app that manages this folder did not answer, so nothing was changed."
}
