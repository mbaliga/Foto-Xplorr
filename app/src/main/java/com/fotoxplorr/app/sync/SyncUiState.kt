package com.fotoxplorr.app.sync

import java.net.URI
import java.util.Locale

/*
 * What the sync settings screen shows, worked out as plain data so it is tested on the JVM. No Compose and no
 * secret in here: a password, a secret key or a passphrase is never a field of anything below. The screen keeps
 * those in memory only (never in saved state), and hands the validator only WHETHER one was typed.
 */

enum class SyncStage { NOT_SET_UP, DESTINATION_CHOSEN, READY, RUNNING, PAUSED, FINISHED, FAILED }

/** The top card: [title] and one [line]. */
data class SyncStatusCard(val stage: SyncStage, val title: String, val line: String)

/** The choices of "Most to send in one run". */
enum class SyncByteCap(val bytes: Long?, val label: String) {
    NONE(null, SyncCopy.CAP_NONE),
    GB_1(1_000_000_000L, "1 GB"),
    GB_5(5_000_000_000L, "5 GB"),
    GB_20(20_000_000_000L, "20 GB"),
    ;

    companion object {
        /** The nearest choice at or above a saved cap (a cap set some other way still shows as something). */
        fun of(bytes: Long?): SyncByteCap = if (bytes == null) NONE else entries.drop(1).firstOrNull { it.bytes!! >= bytes } ?: GB_20
    }
}

/** A folder the person picked with the system picker: just its address; the name is derived. */
data class SyncPickedFolder(val uri: String)

/**
 * The editable settings, secrets excluded. Survives process death through [toSaveable] / [fromSaveable]
 * (plain strings, booleans and a string list).
 */
data class SyncForm(
    val kind: SyncDestinationKind? = null,
    val folderUri: String = "",
    val webDavUrl: String = "",
    val webDavUser: String = "",
    val webDavPlainHttp: Boolean = false,
    val s3Endpoint: String = "",
    val s3Region: String = "",
    val s3Bucket: String = "",
    val s3Access: String = "",
    val s3PathStyle: Boolean = true,
    val s3Prefix: String = "",
    val s3PlainHttp: Boolean = false,
    val allPhotos: Boolean = false,
    val folderKeys: Set<String> = emptySet(),
    val favourites: Boolean = false,
    val includeVideos: Boolean = true,
    val wifiOnly: Boolean = true,
    val cap: SyncByteCap = SyncByteCap.NONE,
    val encrypt: Boolean = false,
    /** Google client ID / Dropbox app key (not a secret). */
    val oauthClientId: String = "",
    /** The signed-in account as shown; blank when not signed in. The token itself is never here. */
    val oauthAccount: String = "",
) {
    val selection: SyncSelection
        get() = SyncSelection(allPhotos, folderKeys, favourites, includeVideos)

    fun toSaveable(): List<Any> = listOf(
        kind?.code.orEmpty(), folderUri, webDavUrl, webDavUser, webDavPlainHttp,
        s3Endpoint, s3Region, s3Bucket, s3Access, s3PathStyle, s3Prefix, s3PlainHttp,
        allPhotos, ArrayList(folderKeys.sorted()), favourites, includeVideos, wifiOnly, cap.name, encrypt,
        oauthClientId, oauthAccount,
    )

    companion object {
        /** Null when [saved] is not what [toSaveable] wrote. */
        @Suppress("UNCHECKED_CAST")
        fun fromSaveable(saved: List<Any>): SyncForm? = try {
            SyncForm(
                kind = SyncDestinationKind.fromCode((saved[0] as String).ifEmpty { null }),
                folderUri = saved[1] as String,
                webDavUrl = saved[2] as String,
                webDavUser = saved[3] as String,
                webDavPlainHttp = saved[4] as Boolean,
                s3Endpoint = saved[5] as String,
                s3Region = saved[6] as String,
                s3Bucket = saved[7] as String,
                s3Access = saved[8] as String,
                s3PathStyle = saved[9] as Boolean,
                s3Prefix = saved[10] as String,
                s3PlainHttp = saved[11] as Boolean,
                allPhotos = saved[12] as Boolean,
                folderKeys = (saved[13] as List<String>).toSet(),
                favourites = saved[14] as Boolean,
                includeVideos = saved[15] as Boolean,
                wifiOnly = saved[16] as Boolean,
                cap = SyncByteCap.valueOf(saved[17] as String),
                encrypt = saved[18] as Boolean,
                oauthClientId = saved.getOrNull(19) as? String ?: "",
                oauthAccount = saved.getOrNull(20) as? String ?: "",
            )
        } catch (e: RuntimeException) {
            null
        }

        fun from(config: SyncDestinationConfig?): SyncForm {
            if (config == null) return SyncForm()
            return SyncForm(
                kind = config.kind,
                folderUri = config.folderTreeUri.orEmpty(),
                webDavUrl = config.webDav?.baseUrl.orEmpty(),
                webDavUser = config.webDav?.username.orEmpty(),
                webDavPlainHttp = config.webDav?.allowPlainHttpOnLocal ?: false,
                s3Endpoint = config.s3?.endpoint.orEmpty(),
                s3Region = config.s3?.region.orEmpty(),
                s3Bucket = config.s3?.bucket.orEmpty(),
                s3Access = config.s3?.accessKeyId.orEmpty(),
                s3PathStyle = config.s3?.pathStyle ?: true,
                s3Prefix = config.s3?.prefix.orEmpty(),
                s3PlainHttp = config.s3?.allowPlainHttpOnLocal ?: false,
                allPhotos = config.selection.allPhotos,
                folderKeys = config.selection.folderKeys,
                favourites = config.selection.favourites,
                includeVideos = config.selection.includeVideos,
                wifiOnly = config.wifiOnly,
                cap = SyncByteCap.of(config.byteCapPerRun),
                encrypt = config.encryptEnabled,
                oauthClientId = config.oauth?.clientId.orEmpty(),
                oauthAccount = config.oauth?.accountLabel.orEmpty(),
            )
        }
    }
}

/** What the passphrase fields amount to; never the text. */
enum class PassphraseStatus(val sentence: String?) {
    NOT_NEEDED(null),
    EMPTY(SyncCopy.ENCRYPT_PASSPHRASE_NEEDED),
    TOO_SHORT(SyncCopy.PASSPHRASE_SHORT),
    MISMATCH(SyncCopy.PASSPHRASE_MISMATCH),
    OK(null),
}

/** What was typed into the secret fields, as facts only. */
data class SyncSecretFacts(
    /** A password or secret key is in its field now. */
    val secretEntered: Boolean = false,
    /** The saved destination is the same target, so its stored secret will be kept when none is typed. */
    val storedSecretKept: Boolean = false,
    /** Google/Dropbox: an account is shown AND a token is in memory or kept from the saved destination. */
    val signedIn: Boolean = false,
    val passphrase: PassphraseStatus = PassphraseStatus.NOT_NEEDED,
)

/** One sentence per field, or null. Only the fields of the chosen kind are ever set. */
data class SyncFormErrors(
    val folder: String? = null,
    val webDavUrl: String? = null,
    val webDavUser: String? = null,
    val secret: String? = null,
    val s3Endpoint: String? = null,
    val s3Region: String? = null,
    val s3Bucket: String? = null,
    val s3Access: String? = null,
    val oauthClientId: String? = null,
    val selection: String? = null,
    val passphrase: String? = null,
) {
    /** The destination details are complete enough to try. */
    val destinationOk: Boolean
        get() = folder == null && webDavUrl == null && webDavUser == null && secret == null &&
            s3Endpoint == null && s3Region == null && s3Bucket == null && s3Access == null && oauthClientId == null
}

/** Whether Send is allowed and, if not, the one sentence saying why. */
data class SyncStartGate(val enabled: Boolean, val reason: String?)

object SyncUi {
    const val MIN_PASSPHRASE = 8
    private val S3_BUCKET = Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")

    fun stage(config: SyncDestinationConfig?, run: SyncRunState): SyncStage = when (run) {
        SyncRunState.Preparing, is SyncRunState.Running -> SyncStage.RUNNING
        is SyncRunState.Paused -> SyncStage.PAUSED
        is SyncRunState.Finished -> SyncStage.FINISHED
        is SyncRunState.Failed -> SyncStage.FAILED
        SyncRunState.Idle -> when {
            config == null -> SyncStage.NOT_SET_UP
            config.selection.isEmpty -> SyncStage.DESTINATION_CHOSEN
            else -> SyncStage.READY
        }
    }

    fun status(config: SyncDestinationConfig?, run: SyncRunState): SyncStatusCard {
        val stage = stage(config, run)
        val name = config?.displayName?.takeIf { it.isNotBlank() } ?: SyncCopy.FOLDER_FALLBACK_LABEL
        return when (stage) {
            SyncStage.NOT_SET_UP -> SyncStatusCard(stage, SyncCopy.STATUS_OFF, SyncCopy.OFF_EXPLAINED)
            SyncStage.DESTINATION_CHOSEN -> SyncStatusCard(stage, "Destination saved", "Copies will go to $name. ${SyncCopy.NOTHING_CHOSEN}")
            SyncStage.READY -> SyncStatusCard(stage, "Ready to send", "Copies will go to $name. Nothing is sent until you tap Send now.")
            SyncStage.RUNNING -> SyncStatusCard(stage, SyncCopy.JOB_TITLE, progressText(run) ?: "Getting ready…")
            SyncStage.PAUSED -> {
                val paused = run as SyncRunState.Paused
                SyncStatusCard(stage, "Paused", "${filesLeft(paused.filesLeft)} Send again to carry on.")
            }
            SyncStage.FINISHED -> SyncStatusCard(stage, "All done", SyncCopy.summary((run as SyncRunState.Finished).tally))
            SyncStage.FAILED -> {
                val failed = run as SyncRunState.Failed
                SyncStatusCard(stage, "Stopped", failureLine(failed))
            }
        }
    }

    /** REJECTED carries a sentence of its own (a wrong passphrase, a bad bucket); the others are said the same way every time. */
    fun failureLine(failed: SyncRunState.Failed): String =
        if ((failed.failure == SyncFailure.REJECTED && failed.message.isNotBlank()) || (failed.failure == SyncFailure.AUTH && failed.message in SyncCopy.OWN_AUTH_SENTENCES)) failed.message else SyncCopy.failureSentence(failed.failure)

    private fun filesLeft(n: Int) = if (n == 1) "1 file is left." else "$n files are left."

    /** "12 of 340 files, 1.2 GB of 4.5 GB" while running; null otherwise. */
    fun progressText(run: SyncRunState): String? {
        val running = run as? SyncRunState.Running ?: return null
        val files = "${running.filesDone} of ${running.filesTotal} files"
        return if (running.bytesTotal > 0) "$files, ${formatBytes(running.bytesDone)} of ${formatBytes(running.bytesTotal)}" else files
    }

    fun tallyLines(tally: SyncTally): List<String> = buildList {
        if (tally.uploaded > 0) add("Sent: ${tally.uploaded} (${formatBytes(tally.uploadedBytes)})")
        val there = tally.upToDate + tally.adopted
        if (there > 0) add("Already there: $there")
        if (tally.failed > 0) add("Could not be sent: ${tally.failed}")
        if (tally.skipped > 0) add("Left out: ${tally.skipped}")
        if (tally.deferredByCap > 0) add("Waiting for the next run: ${tally.deferredByCap}")
        if (tally.tooBigForCap > 0) add("Bigger than the limit: ${tally.tooBigForCap}")
    }

    fun previewLine(p: SyncPreview): String {
        val files = if (p.toSend == 1) "1 file" else "${p.toSend} files"
        return "$files, ${formatBytes(p.bytesToSend)} to send, ${p.alreadyThere} already there"
    }

    /** Extra preview sentences: what the size limit holds back. */
    fun previewNotes(p: SyncPreview): List<String> = buildList {
        if (p.deferredByCap > 0) add("${p.deferredByCap} wait for the next run because of the size limit.")
        if (p.tooBigForCap > 0) add("${p.tooBigForCap} are bigger than the size limit and will not be sent.")
    }

    /** Decimal units, one decimal from 1 GB up, none below ("820 MB", "1.2 GB"). */
    fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        return when {
            b >= 1_000_000_000L -> String.format(Locale.ROOT, "%.1f GB", b / 1e9)
            b >= 1_000_000L -> String.format(Locale.ROOT, "%d MB", b / 1_000_000L)
            b >= 1_000L -> String.format(Locale.ROOT, "%d KB", b / 1_000L)
            else -> "$b B"
        }
    }

    /** What the person will get for what they typed, or null when it is not an address. A preview, not a promise. */
    fun webDavPreview(form: SyncForm): String? =
        WebDavUrl.normalise(form.webDavUrl, form.webDavPlainHttp)

    /** A readable name for a tree address from the system picker ("primary:DCIM/Backup" gives "Backup"). */
    fun folderName(treeUri: String): String {
        if (treeUri.isBlank()) return ""
        val last = treeUri.substringAfterLast('/')
        val decoded = try {
            java.net.URLDecoder.decode(last.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            last
        }
        return decoded.substringAfterLast('/').substringAfterLast(':').ifBlank { SyncCopy.FOLDER_FALLBACK_LABEL }
    }

    fun passphraseStatus(form: SyncForm, passphrase: CharSequence, confirm: CharSequence, needConfirm: Boolean): PassphraseStatus = when {
        !form.encrypt -> PassphraseStatus.NOT_NEEDED
        passphrase.isEmpty() -> PassphraseStatus.EMPTY
        needConfirm && passphrase.length < MIN_PASSPHRASE -> PassphraseStatus.TOO_SHORT
        needConfirm && passphrase.toString() != confirm.toString() -> PassphraseStatus.MISMATCH
        else -> PassphraseStatus.OK
    }

    /**
     * Field errors. Format errors show as soon as a field has text; "enter this" errors only once the
     * person has tried ([attempted]), so a fresh form is not a wall of red.
     */
    fun validate(form: SyncForm, facts: SyncSecretFacts, attempted: Boolean): SyncFormErrors {
        fun required(value: String, sentence: String) = if (value.isBlank() && attempted) sentence else null
        val secretMissing = !facts.secretEntered && !facts.storedSecretKept
        var errors = SyncFormErrors()
        when (form.kind) {
            SyncDestinationKind.FOLDER -> errors = errors.copy(folder = if (form.folderUri.isBlank() && attempted) SyncCopy.PICK_FOLDER_ERROR else null)
            SyncDestinationKind.WEBDAV -> errors = errors.copy(
                webDavUrl = when {
                    form.webDavUrl.isBlank() -> if (attempted) SyncCopy.WEBDAV_NEED_ADDRESS else null
                    webDavPreview(form) != null -> null
                    isPlainHttpRefused(form) -> SyncCopy.PLAIN_HTTP_LOCAL_ONLY
                    else -> SyncCopy.WEBDAV_BAD_ADDRESS
                },
                webDavUser = required(form.webDavUser, SyncCopy.WEBDAV_NEED_USER),
                secret = if (secretMissing && attempted) SyncCopy.NEED_PASSWORD else null,
            )
            SyncDestinationKind.S3 -> errors = errors.copy(
                s3Endpoint = when {
                    form.s3Endpoint.isBlank() -> if (attempted) SyncCopy.S3_NEED_ENDPOINT else null
                    s3EndpointProblem(form) != null -> s3EndpointProblem(form)
                    else -> null
                },
                s3Region = required(form.s3Region, SyncCopy.S3_BAD_REGION),
                s3Bucket = when {
                    form.s3Bucket.isBlank() -> if (attempted) SyncCopy.S3_BAD_BUCKET else null
                    !S3_BUCKET.matches(form.s3Bucket.trim()) -> SyncCopy.S3_BAD_BUCKET
                    else -> null
                },
                s3Access = required(form.s3Access, SyncCopy.S3_BAD_ACCESS_KEY),
                secret = if (secretMissing && attempted) SyncCopy.S3_NEED_SECRET else null,
            )
            SyncDestinationKind.GDRIVE, SyncDestinationKind.DROPBOX -> errors = errors.copy(
                oauthClientId = when {
                    form.oauthClientId.isBlank() -> if (attempted) SyncCopy.NEED_CLIENT_ID else null
                    clientIdProblem(form) != null -> clientIdProblem(form)
                    else -> null
                },
                secret = if (!facts.signedIn && attempted) SyncCopy.NEED_SIGN_IN else null,
            )
            null -> Unit
        }
        errors = errors.copy(
            selection = if (form.selection.isEmpty && attempted) SyncCopy.NOTHING_CHOSEN else null,
            passphrase = facts.passphrase.sentence.takeIf { attempted || facts.passphrase != PassphraseStatus.EMPTY },
        )
        return errors
    }

    /** Google ids end in `.apps.googleusercontent.com`; a Dropbox key is letters and digits. No spaces in either. */
    fun clientIdProblem(form: SyncForm): String? {
        val id = form.oauthClientId.trim()
        if (id.isEmpty()) return null
        return when {
            id.any { it.isWhitespace() } -> if (form.kind == SyncDestinationKind.GDRIVE) SyncCopy.BAD_GOOGLE_CLIENT_ID else SyncCopy.BAD_APP_KEY
            form.kind == SyncDestinationKind.GDRIVE && !id.endsWith(".apps.googleusercontent.com") -> SyncCopy.BAD_GOOGLE_CLIENT_ID
            form.kind == SyncDestinationKind.DROPBOX && !id.all { it.isLetterOrDigit() } -> SyncCopy.BAD_APP_KEY
            else -> null
        }
    }

    private fun isPlainHttpRefused(form: SyncForm): Boolean {
        val text = form.webDavUrl.trim()
        return text.startsWith("http://", ignoreCase = true)
    }

    /** Same rules as the S3 provider's endpoint check: https (or plain http on the home network when allowed), a host, no path. */
    private fun s3EndpointProblem(form: SyncForm): String? {
        val text = form.s3Endpoint.trim().trimEnd('/').let { if (it.contains("://")) it else "https://$it" }
        val uri = try {
            URI(text)
        } catch (e: java.net.URISyntaxException) {
            return SyncCopy.S3_BAD_ENDPOINT
        }
        val host = uri.host
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (host.isNullOrBlank() || uri.rawUserInfo != null || (uri.rawPath ?: "").isNotEmpty()) return SyncCopy.S3_BAD_ENDPOINT
        if (scheme == "https") return null
        if (scheme == "http") return if (form.s3PlainHttp && HostRules.isLocalHost(host)) null else SyncCopy.PLAIN_HTTP_LOCAL_ONLY
        return SyncCopy.S3_BAD_ENDPOINT
    }

    /** The values that decide WHICH destination this is: a change of any of them is a new destination with a fresh manifest. */
    private fun targetOf(config: SyncDestinationConfig): List<Any?> = listOf(
        config.kind, config.folderTreeUri,
        config.webDav?.baseUrl, config.webDav?.username,
        config.s3?.endpoint, config.s3?.bucket, config.s3?.accessKeyId, config.s3?.prefix,
        config.oauth?.clientId,
    )

    fun sameTarget(a: SyncDestinationConfig, b: SyncDestinationConfig): Boolean = targetOf(a) == targetOf(b)

    /**
     * The configuration the form describes, or null when the destination details are not usable. Keeps
     * [existing]'s id and encryption set when the target is the same, else uses [newId].
     */
    fun buildConfig(form: SyncForm, existing: SyncDestinationConfig?, newId: () -> String): SyncDestinationConfig? {
        val kind = form.kind ?: return null
        val draft = SyncDestinationConfig(
            id = "",
            kind = kind,
            displayName = "",
            selection = form.selection,
            wifiOnly = form.wifiOnly,
            byteCapPerRun = form.cap.bytes,
            encryptEnabled = form.encrypt,
        )
        val shaped = when (kind) {
            SyncDestinationKind.FOLDER -> {
                if (form.folderUri.isBlank()) return null
                draft.copy(folderTreeUri = form.folderUri, displayName = folderName(form.folderUri))
            }
            SyncDestinationKind.WEBDAV -> {
                val base = webDavPreview(form) ?: return null
                if (form.webDavUser.isBlank()) return null
                val host = runCatching { URI(base).host }.getOrNull().orEmpty()
                draft.copy(
                    webDav = WebDavSettings(base, form.webDavUser.trim(), form.webDavPlainHttp),
                    displayName = host.ifBlank { SyncCopy.KIND_WEBDAV },
                )
            }
            SyncDestinationKind.S3 -> {
                if (s3EndpointProblem(form) != null || !S3_BUCKET.matches(form.s3Bucket.trim())) return null
                if (form.s3Region.isBlank() || form.s3Access.isBlank()) return null
                draft.copy(
                    s3 = S3Settings(
                        endpoint = form.s3Endpoint.trim(),
                        region = form.s3Region.trim(),
                        bucket = form.s3Bucket.trim(),
                        accessKeyId = form.s3Access.trim(),
                        pathStyle = form.s3PathStyle,
                        prefix = form.s3Prefix.trim().trim('/'),
                        allowPlainHttpOnLocal = form.s3PlainHttp,
                    ),
                    displayName = form.s3Bucket.trim(),
                )
            }
            SyncDestinationKind.GDRIVE, SyncDestinationKind.DROPBOX -> {
                if (form.oauthClientId.isBlank() || clientIdProblem(form) != null) return null
                val account = form.oauthAccount.trim()
                val name = if (kind == SyncDestinationKind.GDRIVE) SyncCopy.KIND_DRIVE else SyncCopy.KIND_DROPBOX
                draft.copy(
                    oauth = OAuthSettings(form.oauthClientId.trim(), account),
                    displayName = if (account.isEmpty()) name else "$name ($account)",
                )
            }
        }
        val same = existing != null && sameTarget(existing, shaped)
        return shaped.copy(
            id = if (same) existing!!.id else newId(),
            encryption = if (same) existing!!.encryption else null,
        )
    }

    /** Whether Send is allowed now, with the exact reason when not ([SyncStartRefusal] sentences where they exist). */
    fun startGate(
        supported: Set<SyncDestinationKind>,
        form: SyncForm,
        errors: SyncFormErrors,
        facts: SyncSecretFacts,
        run: SyncRunState,
    ): SyncStartGate {
        fun no(sentence: String) = SyncStartGate(false, sentence)
        val kind = form.kind
        return when {
            run is SyncRunState.Preparing || run is SyncRunState.Running -> no(SyncStartRefusal.ALREADY_RUNNING.sentence)
            kind == null -> no(SyncStartRefusal.NO_DESTINATION.sentence)
            kind !in supported -> no(SyncStartRefusal.NEEDS_CONNECT_BUILD.sentence)
            !errors.destinationOk -> no(SyncCopy.FIX_DETAILS_FIRST)
            form.selection.isEmpty -> no(SyncStartRefusal.NOTHING_CHOSEN.sentence)
            facts.passphrase == PassphraseStatus.EMPTY -> no(SyncStartRefusal.PASSPHRASE_NEEDED.sentence)
            facts.passphrase == PassphraseStatus.TOO_SHORT || facts.passphrase == PassphraseStatus.MISMATCH -> no(facts.passphrase.sentence!!)
            else -> SyncStartGate(true, null)
        }
    }
}
