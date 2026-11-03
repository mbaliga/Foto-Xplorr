package com.fotoxplorr.app.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the connectivity platform says about the active network (Connect build only; Offline answers [NONE]). */
enum class SyncNetwork { NONE, METERED, UNMETERED }

/**
 * The guard that runs before a run and before every file. Wi-Fi only is the default: a metered or
 * unknown connection is refused unless the person allowed mobile data for THIS run.
 */
object SyncGuard {
    /** Null when the run may go ahead, else the refusal. */
    fun check(needsNetwork: Boolean, wifiOnly: Boolean, allowMobileData: Boolean, network: SyncNetwork): SyncStartRefusal? = when {
        !needsNetwork -> null
        network == SyncNetwork.NONE -> SyncStartRefusal.NO_NETWORK
        network == SyncNetwork.METERED && wifiOnly && !allowMobileData -> SyncStartRefusal.WAITING_FOR_WIFI
        else -> null
    }
}

enum class SyncStartRefusal(val sentence: String) {
    NO_DESTINATION(SyncCopy.NO_DESTINATION),
    NOTHING_CHOSEN(SyncCopy.NOTHING_CHOSEN),
    ALREADY_RUNNING(SyncCopy.ALREADY_RUNNING),
    WAITING_FOR_WIFI(SyncCopy.WIFI_NEEDED),
    NO_NETWORK(SyncCopy.NO_NETWORK),
    PASSPHRASE_NEEDED(SyncCopy.ENCRYPT_PASSPHRASE_NEEDED),
    NEEDS_CONNECT_BUILD(SyncCopy.NEEDS_CONNECT_BUILD),
    NO_JOB_RUNNER("Sync can't start from here."),
}

sealed interface SyncStartResult {
    data object Started : SyncStartResult
    data class Refused(val reason: SyncStartRefusal) : SyncStartResult
}

/** What a run would do now, worked out from the library and the manifest without touching the destination. */
data class SyncPreview(
    val toSend: Int,
    val bytesToSend: Long,
    val alreadyThere: Int,
    val deferredByCap: Int,
    val tooBigForCap: Int,
    /** Lines of the manifest that could not be read (damage); 0 normally. */
    val unreadableRecordLines: Int,
)

/**
 * THE SEAM FOR THE UI UNIT (stage 2). Everything the settings screen, the Start button and the status
 * line need, and nothing about providers, manifests or HTTP.
 *
 * Rules the UI must keep (they are the product's, not this interface's):
 *  - Nothing is configured, planned or started by opening a screen. [start] is called from an explicit
 *    tap and from nowhere else; it never runs on app open, on a timer or from a broadcast.
 *  - Show [supportedKinds]: in the Offline build only [SyncDestinationKind.FOLDER] is offered, and the
 *    other kinds are explained with [SyncCopy.NEEDS_CONNECT_BUILD], not hidden.
 *  - Say [SyncCopy.ONE_WAY], [SyncCopy.NEVER_PROTECTED], and, for an encrypted destination,
 *    [SyncCopy.ENCRYPT_NAMES_VISIBLE] and [SyncCopy.ENCRYPT_NO_RECOVERY].
 *  - Secrets (a password, a secret key, a passphrase) are passed as CharArray, are zeroed by the service
 *    when it is done with them, and are never shown again, logged or put in saved state.
 *  - The run is a job (`ActivityKind.SYNCING`), so progress and Cancel are also in the activity shade;
 *    [pause] is the same as cancelling it there.
 */
interface SyncService {
    /** Kinds this build can use. */
    val supportedKinds: Set<SyncDestinationKind>

    /** The saved destination (never including its secret), or null when none is set up. */
    val config: StateFlow<SyncDestinationConfig?>

    /** Idle until the person starts a run. */
    val state: StateFlow<SyncRunState>

    /** A fresh id for a NEW destination (new manifest). Keep the old id to edit the same one. */
    fun newDestinationId(): String

    /**
     * Saves [config]; [secret] (a WebDAV app password or an S3 secret key) is stored encrypted by the
     * Android Keystore when non-null, and the stored one is kept when null. Does not touch the network.
     * Fails with a sentence the person can read.
     */
    suspend fun saveDestination(config: SyncDestinationConfig, secret: CharArray?): Result<Unit>

    /** Reaches the destination with these settings (and the stored secret when [secret] is null). Nothing is saved or sent. */
    suspend fun testConnection(config: SyncDestinationConfig, secret: CharArray?): Result<String>

    /** How many files a run would send now. Reads the library and the manifest only. */
    suspend fun preview(): Result<SyncPreview>

    /**
     * Starts a run for the saved destination: an EXPLICIT tap. [passphrase] is required when the
     * destination encrypts. [allowMobileData] lets this one run use a metered network despite Wi-Fi
     * only. Returns at once; progress is in [state].
     */
    fun start(passphrase: CharArray? = null, allowMobileData: Boolean = false): SyncStartResult

    /** Stops the run at the next chunk; what was recorded stays and [start] continues from there. */
    fun pause()

    /** Forgets the destination and its stored secret. Nothing remote or local is deleted. */
    fun forgetDestination()

    /**
     * Google Drive / Dropbox only: from the person's tap on Sign out. Forgets the stored sign-in on this phone
     * FIRST (and the account name in the saved settings), then asks the provider to end the grant, best effort:
     * a failure of that call is not reported, the local forgetting has already happened. [unsavedToken] is a
     * sign-in the person made on the screen but never saved (with its [kind] and [clientId]); it is revoked too and zeroed. Nothing remote is deleted.
     */
    suspend fun signOut(unsavedToken: CharArray? = null, kind: SyncDestinationKind? = null, clientId: String? = null): Result<Unit> {
        unsavedToken?.fill('\u0000')
        return Result.success(Unit)
    }
}

/**
 * An in-memory [SyncService] for UI tests and previews: no files, no network, no job. A test drives it
 * by setting [state] and reading [calls].
 */
class FakeSyncService(
    override val supportedKinds: Set<SyncDestinationKind> = SyncDestinationKind.entries.toSet(),
    initial: SyncDestinationConfig? = null,
) : SyncService {
    private val _config = MutableStateFlow(initial)
    override val config: StateFlow<SyncDestinationConfig?> = _config.asStateFlow()
    private val _state = MutableStateFlow<SyncRunState>(SyncRunState.Idle)
    override val state: StateFlow<SyncRunState> = _state.asStateFlow()

    val calls = ArrayList<String>()
    var nextTestResult: Result<String> = Result.success(SyncCopy.connectedTo("test"))
    var nextPreview: Result<SyncPreview> = Result.success(SyncPreview(0, 0, 0, 0, 0, 0))
    var nextStart: SyncStartResult = SyncStartResult.Started
    private var counter = 0

    fun setState(state: SyncRunState) {
        _state.value = state
    }

    override fun newDestinationId(): String = "fake-${++counter}"

    override suspend fun saveDestination(config: SyncDestinationConfig, secret: CharArray?): Result<Unit> {
        calls += "save:${config.id}:secret=${secret != null}"
        secret?.fill('\u0000')
        _config.value = config
        return Result.success(Unit)
    }

    override suspend fun testConnection(config: SyncDestinationConfig, secret: CharArray?): Result<String> {
        calls += "test:${config.id}"
        secret?.fill('\u0000')
        return nextTestResult
    }

    override suspend fun preview(): Result<SyncPreview> {
        calls += "preview"
        return nextPreview
    }

    override fun start(passphrase: CharArray?, allowMobileData: Boolean): SyncStartResult {
        calls += "start:mobile=$allowMobileData"
        passphrase?.fill('\u0000')
        if (nextStart == SyncStartResult.Started) _state.value = SyncRunState.Preparing
        return nextStart
    }

    override fun pause() {
        calls += "pause"
        (_state.value as? SyncRunState.Running)?.let { _state.value = SyncRunState.Paused(it.tally, it.filesTotal - it.filesDone) }
    }

    override suspend fun signOut(unsavedToken: CharArray?, kind: SyncDestinationKind?, clientId: String?): Result<Unit> {
        calls += "signout:unsaved=${unsavedToken != null}"
        unsavedToken?.fill('\u0000')
        _config.value = _config.value?.let { c -> c.copy(oauth = c.oauth?.copy(accountLabel = "")) }
        return Result.success(Unit)
    }

    override fun forgetDestination() {
        calls += "forget"
        _config.value = null
        _state.value = SyncRunState.Idle
    }
}
