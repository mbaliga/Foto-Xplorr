package com.fotoxplorr.app.ui

/**
 * The words that explain the app to the person using it: the line under a switch, the sentence
 * under an empty screen, the privacy statement. Trimmed on purpose (owner feedback #6, "the help
 * text is too verbose"), and kept in one place so it stays trimmed.
 *
 * The rule: one short line, plain words, say what it does and stop. No "embedding", no "tflite",
 * no "hillshade". If a caption only repeats its label, it is not written at all (the row's
 * caption is nullable for that reason). `HelpCopyTest` holds each group to a length ceiling so
 * verbosity cannot creep back in a later edit.
 *
 * What belongs here: copy that is shared by more than one screen, copy that makes a claim about
 * privacy or data safety (so it can be found and reviewed in one place), and the rest of the
 * trimmed help text. What does not: labels, buttons, and messages built from live values (those
 * stay at their call site, composed from the constants here). Every sentence that states a fact
 * about what leaves the device keeps that fact; trimming shortens the words, never the claim.
 *
 * Strings must not contain the tokens the offline source scan denies (docs/TRAPS.md #12), and use
 * a full stop or colon where older copy used a double hyphen.
 */
internal object HelpCopy {

    /** Under a row or switch, beside its label. At most 60 characters. */
    object Caption {
        const val FILMSTRIP = "Thumbnails along the bottom of a photo."
        const val LOOP_ANIMATIONS = "Play GIFs and animated images."
        const val AUTOPLAY = "Start videos as soon as they open."
        const val SHUFFLE = "Random order."
        const val BLUR_GRID_ONLY = "Grid only. Photos you open are never blurred."
        const val HIDE_FROM_GRID = "Removed from the grid. They stay in their album."
        const val BLUR_OR_HIDE = "Blur or hide in the grid."
        const val KEEP_SCREEN_ON = "While a photo is open. Uses more battery."
        const val FILL_TILE = "Fill each square. Off shows the whole photo."
        const val TIMELINE = "Date orders only. The photos make room for the strip."
        const val SCROLL_AXIS = "Across suits a top or bottom timeline. Stamps stay vertical."
        const val LONG_PRESS = "What holding a photo does. While selecting, a hold adds it."
        const val PINCH_ON = "Pinch in: details. Pinch out: actions. No pinch zoom."
        const val PINCH_OFF = "Pinch zooms the photo."
        const val PARTIAL_ACCESS = "Fotoz only sees the photos you picked."

        const val SELECT_WHILE_SELECTING = "Tap photos to add them."
        const val SELECT_TO_START = "Then tap photos."
        const val NEW_COLLECTION = "Make your own album."
        const val RESCAN = "Find photos added or removed elsewhere."
        const val TRASH_ALSO = "Or use the bin at the bottom right."
        const val SHARE_DEFAULT = "Location and camera data removed by default."
        const val SHARE_OPTIONS = "Pick frame, watermark and privacy."
        const val COUNTS_VIEWING = "Counts what you are viewing now."

        const val TAGS_SUGGESTED = "Outlined tags are suggestions. Removing one is permanent."
        const val CAPTION_MACHINE = "Written by on-device recognition. Edit it to make it yours."
        const val NO_GPS_REASON = "Chat apps and websites usually remove location."

        const val RULES_SWITCH = "On: the rules below apply. Off: Android decides when."
        const val IDLE_RULE = "Android decides when the phone is idle. It can take hours."
        const val CHARGING_RULE = "Wait for the charger."
        const val HOURS_ALWAYS = "Same start and end means always."
        const val HOURS_SAME_DAY = "Within one day."
        const val WIFI_OFFLINE = "Not available: the offline build has no network."
        const val STATUS_CHECKED = "Checked about every 30 minutes."

        const val PRO_ON = "No Fotoz mark on your shares. Thank you!"
        const val PRO_OFF = "Shares carry a small Fotoz mark. Pro removes it."
        const val PRO_ONLY_MARK = "Pro only removes the Fotoz mark. Nothing else is locked."
        const val SUPPORT = "Something wrong, or an idea? Write to us."
        const val FAMILY = "One of a family of apps that never phone home."

        const val SHARE_NOTE = "Shown along the bottom. Optional."
        const val SHARE_SEAL = "A few letters, stamped in the corner."
        const val SAVE_COPY = "A new photo. The original is untouched."
        const val SAVE_REPLACE = "Replaces the photo. The original can't be recovered."
        const val FRAME_WHITE = "White border, room for a caption"
        const val FRAME_PERFORATED = "Perforated edge, optional seal"
    }

    /**
     * One line of instruction, an empty state or a status. At most 80 characters. Includes the
     * lines that state a privacy fact in short form.
     */
    object Hint {
        const val PROTECTED_FOLDERS = "Set up protected folders from an album's menu. Passwords are never stored here."
        const val BACKUP = "Tags, collections, captions, favourites, sensitive marks. Not your photos."

        /** The plain file's one privacy fact, shown wherever it is offered: it is readable by anyone who has it. */
        const val BACKUP_NOT_ENCRYPTED = "Not encrypted. Anyone who gets the file can read your captions and tags."
        const val BACKUP_RESTORE = "Restoring adds to what you have. Replacing needs a second confirmation."

        /** The encrypted backup's own lines. "Not your photos" above is about the plain file only. */
        const val BACKUP_ENCRYPTED = "Photos, videos, tags and locks, encrypted with your passphrase."
        const val BACKUP_ENCRYPTED_ADDS = "Adds what is new each time. Photos you delete from the phone stay in it."
        const val BACKUP_UNDERSTAND = "I understand: Fotoz cannot recover this backup without the passphrase"
        const val BACKUP_CHECK = "Reads the whole backup and proves every file is intact."
        const val BACKUP_RESTORE_ENCRYPTED = "Puts photos and tags back from an encrypted backup. Replaces nothing."
        const val RESCAN_AUTO = "Fotoz spots new photos by itself. Use this to force a check."
        const val BILLING = "Nothing is charged yet. This only remembers Pro on this device."

        const val READS_GPS_LOCALLY = "Reads photo locations on this device. Nothing is uploaded."
        const val LOCATION_ONLY_HERE = "Uses your location only here. Nothing leaves the device."
        const val LOCATION_DENIED = "Location permission was not granted."
        const val NO_LOCATION_IN_PHOTO = "No location in this photo. ${Caption.NO_GPS_REASON}"
        const val NO_PHOTO_LOCATION = "No photo has a location. ${Caption.NO_GPS_REASON}"
        const val ADD_BY_HAND = "${Caption.NO_GPS_REASON} Drag a photo down to add one."
        const val WIDER_RANGE = "Try a wider range or longer period."
        const val LOCATION_SAVED_IN_FOTOZ = "Saved in Fotoz only. The file changes only if you write it below."
        const val COMPASS_GOOD = "Compass is good. Turn to look around."
        const val COMPASS_FAIR = "Compass is fair. Wave the phone in a figure-eight."
        const val COMPASS_POOR = "Compass is poor. Move away from magnets and wave in a figure-eight."
        const val COMPASS_UNAVAILABLE = "Compass unavailable. Drag to look around."

        const val EXPLORE_HEADER = "Optional extras. The gallery works without them."
        const val EXPLORE_SIMILARITY = "Group look-alike photos"
        const val EXPLORE_WALL = "Walk through a 3D wall of your photos"
        const val EXPLORE_PREVIEW = "Browse photos in depth"
        const val EXPLORE_COMPASS = "Turn toward photos around you"
        const val EXPLORE_AI = "On-device model and optional AI keys"
        const val WALL_GESTURES = "Drag to look · pinch for depth · tap a photo"

        const val SIMILARITY_SUBTITLE = "Groups of look-alike photos"
        const val SIMILARITY_SETUP_TITLE = "Set up the image model"
        const val SIMILARITY_DOWNLOAD = "Downloads a small model once. Your photos stay on this device."
        const val SIMILARITY_DOWNLOADING = "Downloading model…"
        const val SIMILARITY_EMPTY_TITLE = "No map yet"
        const val SIMILARITY_EMPTY_BODY = "Index your photos to build it."
        const val SIMILARITY_START = "Start indexing"
        const val IMPORT_MODEL = "Import a model file"

        const val AI_NOT_SET_UP = "Not set up."
        const val AI_NOT_SET_UP_OFFLINE = "Not set up. The offline build can't download it. Import a file."
        const val AI_DEVICE_OK = "This device can run it."
        const val AI_DEVICE_LIMITED = "This device is too limited for it."
        const val AI_PICK_ONE = "Pick one. You fill in details next."
        const val AI_KEY_SOURCE = "From the service's API keys page"
        const val AI_TIMEOUT = "Seconds to wait for a reply"
        const val AI_FIND_LOOKALIKES = "Find look-alike photos, fully on this device. Needs a small model file."
        const val REMOTE_AI_OFFLINE = "Not available in the offline build."

        const val PERMISSION_DENIED = "Fotoz can't see your photos. Allow it in Android settings."
        const val PERMISSION_ASK = "Choose the photos Fotoz may use. Nothing is uploaded."
        const val SCANNING = "Scanning…"
        const val NOTHING_FOUND = "No photos or videos found."
        const val PICKED_ONLY = "Showing only the photos you picked."
        const val PICKER_PERMISSION = "Allow access to pick photos."
        const val NO_MATCHES = "Nothing matches your filters"
        const val EXPERIMENTAL_CARD = "Draw fish, try pictures on your wall, and more"
        const val TAG_FIRST = "Select photos, then Add tag."
        const val SCANNING_DESTINATION = "Scanning on this device…"
        const val NO_PETS = "No pets found"
        const val NO_FACES = "No faces found"
        const val NO_ID_DOCUMENTS = "No ID documents found"
        const val TRASH_NEEDS_ANDROID_11 = "Trash needs Android 11 or newer."

        const val THEME_PASTE = "Paste a theme file's JSON. You'll preview it first."
        const val THEME_REVIEW = "This exact text will be saved. Check it first."

        const val CROP = "Drag a handle to resize, or the box to move it."
        const val HEAL = "Tap a blemish to heal it. Heal after cropping."
        const val UNSAVED_FROM_LAST_TIME = "This photo has unsaved changes from last time."
        const val ORIGINAL_TOO_LARGE = "The original is too large to replace."
        const val FORMAT_NOT_REPLACEABLE = "This format can't be replaced."
        const val CHANGE_IN_SETTINGS = "Change this in Settings › Media."
        const val LOOK_ON_TOP = "A look on top of your crop and rotation."
        const val STRIP_METADATA = "On by default. Removes GPS, camera and time info from the copy."
        const val WORK_RULES_OFF = "Rules are off: heavy work runs whenever the system schedules it."
        const val STATUS_RULES_OFF = "Rules are off: indexing runs whenever the system schedules it."
    }

    /**
     * A paragraph that has to carry a fact: a privacy or legal statement, a data-safety warning,
     * a gesture guide. At most 160 characters.
     */
    object Note {
        /**
         * What this build can reach, stated where a user can check it. "Offline" is this app's
         * central claim and a claim the user cannot verify is only marketing; the offline build's
         * manifest genuinely carries no INTERNET permission (the OS refuses it a socket, and the
         * manifest and classpath gates fail the build otherwise), which is a stronger statement
         * than any promise about intent.
         */
        const val OFFLINE_STATEMENT = "This build has no INTERNET permission, so Android blocks it from going online."

        /**
         * The Connect build's counterpart. The statement above is false there (its manifest does
         * hold INTERNET), and the About tab used to show it in both builds.
         */
        const val CONNECT_STATEMENT = "This build can go online, but only for features that need it, like the " +
            "Street map and AI services you add."

        const val BATTERY_FLOOR = "Charging always counts. Android won't run background work below about 15-20% battery."

        /**
         * The Experimental screen's privacy boundary, one per flavour (see [experimentalPrivacy]).
         * Neither may claim more than its build does: the Connect list is every network use
         * reachable from that screen (the similarity model download is offered there; the street
         * map is Map mode's, not this screen's), and the Offline build names nothing that could go
         * online.
         */
        const val EXPERIMENTAL_PRIVACY_OFFLINE = "Everything here runs on this device. Nothing here goes online or " +
            "uploads your photos."
        const val EXPERIMENTAL_PRIVACY_CONNECT = "Nothing here uploads your photos. Only the one-time similarity " +
            "model download and AI services you add go online."
        const val EXPERIMENTAL_LOCATION = "Location is used only by Compass view."

        /**
         * What a protected folder is. It is an in-app gate; the files stay where they were, readable
         * by any other app with photo access (README, "private folders ... not encryption"). Without
         * this line the word "protected" reads as a vault.
         */
        const val PROTECTED_NOT_ENCRYPTED = "Protected folders lock access inside Fotoz. They do not encrypt your files."

        /**
         * The encrypted backup has no recovery of any kind, by design (ADR-014). This sentence is
         * shown in the passphrase dialog and under the Settings entry, and `HelpCopyTest` pins its
         * words: a later trim of this copy must not be able to delete the warning.
         */
        const val BACKUP_NO_RECOVERY = "Fotoz never stores this passphrase. If you forget it, nobody, including us, " +
            "can open the backup. There is no reset."
        const val AI_SERVICE = "Optional. Use your own ChatGPT, Claude or similar account. Nothing is sent until " +
            "you turn it on and ask. Your key stays encrypted on this device."
        const val RENAME_TOKENS = "Tokens: {counter} {counter:3} {orig} {yyyy} {yy} {MM} {dd} {HH} {mm} {ss}. The extension is kept."
        const val KEY_PRIVACY = "Your key is encrypted here and sent only to the address below, only when used."
        const val KEY_PRIVACY_KEEP = "$KEY_PRIVACY Leave blank to keep your saved key."
        const val SIMILARITY_OFFLINE = "The offline build can't download. Import a model file. " +
            "Your photos stay on this device."

        const val GESTURES_PINCH_ON = "Two fingers to turn. Swipe between photos. Drag down for details, up for " +
            "actions, from the right for view options. Pinch does the first two."
        const val GESTURES_PINCH_OFF = "Pinch to zoom. Two fingers to turn. Swipe between photos. Drag down for " +
            "details, up for actions, from the right for view options."
    }

    /** The Experimental privacy paragraph for this build ([networkFeatures] is `BuildConfig.NETWORK_FEATURES`). */
    fun experimentalPrivacy(networkFeatures: Boolean): String =
        if (networkFeatures) Note.EXPERIMENTAL_PRIVACY_CONNECT else Note.EXPERIMENTAL_PRIVACY_OFFLINE

    /** "3 protected · in-app lock only": the destination card, short form of [Note.PROTECTED_NOT_ENCRYPTED]. */
    fun protectedCount(count: Int): String = "$count protected · in-app lock only"

    /** "Overnight, 22:00 to 06:00." */
    fun overnight(from: String, to: String): String = "Overnight, $from to $to."

    /** "Saved a copy. The original is too large to replace." with the size note in between. */
    fun savedCopyTooLarge(sizeNote: String): String = "Saved a copy$sizeNote. ${Hint.ORIGINAL_TOO_LARGE}"

    /** "Saved a copy. This format can't be replaced." */
    fun savedCopyFormat(): String = "Saved a copy. ${Hint.FORMAT_NOT_REPLACEABLE}"
}
