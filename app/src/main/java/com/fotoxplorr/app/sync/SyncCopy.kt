package com.fotoxplorr.app.sync

import java.util.Locale

/**
 * The words of cloud sync: one short sentence each, no file names and no counts of protected photos
 * (the notification and the shade show only [JOB_TITLE]). Kept apart from any screen so the UI unit and
 * the tests share them.
 */
object SyncCopy {
    const val SECTION_LABEL = "Send copies to the cloud"
    const val JOB_TITLE = "Sending copies"
    const val OFF_BY_DEFAULT = "Nothing is sent until you set it up and tap Start."
    const val ONE_WAY = "Fotoz only sends copies. It never deletes anything, here or there."
    const val NEVER_PROTECTED = "Protected, sensitive, archived and bin photos are never sent."
    const val WIFI_ONLY = "Send only on Wi-Fi"
    const val NEEDS_CONNECT_BUILD = "This needs the Connect build of Fotoz. The Offline build has no network."
    const val FOLDER_NEEDS_NO_NETWORK = "A folder works in both builds. Fotoz itself never goes online for it."
    const val FOLDER_CLOUD_APP = "If another app syncs this folder, that app moves the files, not Fotoz."
    const val ENCRYPT_LABEL = "Encrypt before sending"
    const val ENCRYPT_NAMES_VISIBLE = "File names and sizes stay visible; only the photo itself is hidden."
    const val ENCRYPT_NO_RECOVERY = "If you forget the passphrase, the encrypted copies cannot be opened."
    const val ENCRYPT_PASSPHRASE_NEEDED = "Enter your passphrase to start."
    const val WRONG_PASSPHRASE = "That is not the passphrase this destination was started with."
    const val NOTHING_CHOSEN = "Choose what to send first."
    const val NO_DESTINATION = "Choose where to send copies first."
    const val ALREADY_RUNNING = "A sync is already running."
    const val WIFI_NEEDED = "Waiting for Wi-Fi. Connect to Wi-Fi, or allow mobile data, then start again."
    const val NO_NETWORK = "There is no connection right now."
    const val CAP_REACHED = "This run reached its size limit. Start again to send more."
    const val ALL_SENT = "Everything chosen is already there."
    const val CANCELLED = "Stopped. Start again to carry on where it stopped."
    const val FOLDER_GONE = "That folder is not available any more. Choose it again."
    const val FOLDER_OK = "The folder is ready."
    const val FOLDER_FALLBACK_LABEL = "Chosen folder"
    const val WEBDAV_BAD_ADDRESS = "That address does not look right. Use the https:// address of your server."
    const val WEBDAV_NOT_FOUND = "The server answered, but not at that address."
    const val WEBDAV_FAILED = "The server refused the request"
    const val S3_FAILED = "The storage service refused the request"
    const val S3_BAD_ENDPOINT = "The service address does not look right."
    const val S3_BAD_BUCKET = "The bucket name does not look right."
    const val S3_BAD_REGION = "Enter the region of the bucket."
    const val S3_BAD_ACCESS_KEY = "Enter the access key."
    const val S3_WRONG_ENDPOINT = "The service points somewhere else. Check the region and the address."
    const val S3_NO_BUCKET = "There is no bucket with that name."
    const val S3_CLOCK = "This phone's clock is too far off for the service. Fix the time and try again."
    const val PLAIN_HTTP_LOCAL_ONLY = "Plain http is only allowed for an address on your own network."

    fun connectedTo(name: String) = "Connected to $name."

    fun failureSentence(failure: SyncFailure): String = when (failure) {
        SyncFailure.AUTH -> "The login was refused. Check the name and password."
        SyncFailure.NO_SPACE -> "The destination is full."
        SyncFailure.TRANSIENT -> "The connection kept failing. Start again later."
        SyncFailure.REJECTED -> "The destination refused a file."
        SyncFailure.UNSAFE -> "The server tried to send you somewhere else, so Fotoz stopped."
        SyncFailure.UNAVAILABLE_BUILD -> NEEDS_CONNECT_BUILD
        SyncFailure.DESTINATION_GONE -> FOLDER_GONE
    }

    /** "12 sent, 3 already there" and so on, from counts only. */
    fun summary(tally: SyncTally): String {
        val parts = ArrayList<String>()
        if (tally.uploaded > 0) parts += "${tally.uploaded} sent"
        val there = tally.upToDate + tally.adopted
        if (there > 0) parts += "$there already there"
        if (tally.failed > 0) parts += "${tally.failed} could not be sent"
        if (tally.skipped > 0) parts += "${tally.skipped} left out"
        if (tally.deferredByCap > 0) parts += "${tally.deferredByCap} waiting for the next run"
        return if (parts.isEmpty()) ALL_SENT else parts.joinToString(", ").replaceFirstChar { it.titlecase(Locale.ROOT) } + "."
    }
}
