package com.fotoxplorr.app.services

/** The words the Services hub owns. One short sentence each. */
internal object ServicesCopy {
    const val TITLE = "Services"
    const val HUB_BLURB = "Optional connections and the files that power smart features."

    const val KEYS_TITLE = "AI keys"
    const val KEYS_BLURB = "Use your own ChatGPT, Claude or Gemini account."
    const val MODELS_TITLE = "Models on this phone"
    const val MODELS_BLURB = "Small files that let Fotoz work out things offline."
    const val SYNC_TITLE = "Sync"
    const val SYNC_BLURB = "Keep a copy of your photos somewhere you choose."
    const val SYNC_COMING = "Sync is coming."

    /**
     * Checked against the code that exists: the only network call made with a key is the Test
     * button (a short fixed line of text, no photo), the key is sealed by an Android Keystore
     * key, and `RemoteProviderHttp` refuses to send it anywhere except HTTPS or a local-network
     * address. No feature sends a photo to an AI service. When one does, this sentence changes.
     */
    const val KEYS_PRIVACY = "Your key stays on this phone, encrypted. It goes only to the service you " +
        "picked, and only when you tap Test. No photo is sent."
    const val KEYS_STORED = "Stored in the Android Keystore."
    const val KEYS_NEEDS_CONNECT = "Needs the Connect build."
    const val KEY_SAVED = "Key saved"
    const val KEY_MISSING = "No key yet."
    const val TEST_KEY = "Test key"
    const val TESTING = "Testing…"
    const val TEST_SENDS = "Test sends one short line of text. No photo."

    const val MODELS_PRIVACY = "Models run on this phone. Nothing about your photos leaves it."
    const val STORAGE_NONE = "No models installed."
    const val DELETE_ALL = "Delete all"
    const val GET = "Get"
    const val IMPORT_FILE = "Import file"
    const val CANCEL = "Cancel"
    const val DELETE = "Delete"
    const val TRY_AGAIN = "Try again"
    const val REPLACE = "Get newer"
    const val STATE_NOT_INSTALLED = "Not installed"
    const val STATE_INSTALLED = "Installed"
    const val STATE_UPDATE = "Update available"
    const val STATE_FAILED = "Did not install"
    const val OFFLINE_NOTE = "This build cannot download. Import a file instead."
    const val CONFIRM_TITLE = "Download on mobile data?"
    const val CONFIRM_GO = "Download"
    const val OPEN_MODELS = "Open Models on this phone"

    fun storageUsed(size: String) = "Models use $size."
    fun downloadSize(size: String) = "Download size about $size."
    fun confirmBody(size: String) = "This is about $size and you are not on Wi-Fi."
    fun licenceLine(licence: String, host: String) = "$licence licence · from $host"
    fun progress(percent: Int) = "Downloading, $percent%."
    fun deleteDescription(title: String) = "Delete $title"
    fun cancelDescription(title: String) = "Cancel downloading $title"
}
