package com.fotoxplorr.app.sync

import com.fotoxplorr.app.backup.BackupKeys
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobHandle
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.MediaAsset
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Makes the provider for a saved destination. The Connect/Offline difference lives in what it does for network kinds. */
fun interface SyncProviderFactory {
    /** Throws [SyncException] ([SyncFailure.UNAVAILABLE_BUILD] in the Offline build for a network kind). Zeroes [secret]. */
    fun create(config: SyncDestinationConfig, secret: CharArray?): SyncProvider
}

/**
 * [SyncService] over the pieces above. No Android type in the constructor, so it is tested on the JVM
 * with fakes; the Android pieces are assembled in [SyncAndroid].
 *
 * [library] must return the BROWSABLE list (TRAPS #33) and the SAME list instance until the library or
 * the protection state changes (derive it once from the state flows; computing a new list per call
 * makes the per-file re-check quadratic). One run at a time.
 */
class DefaultSyncService(
    private val configStore: SyncConfigStore,
    private val secrets: SyncSecrets,
    private val manifestStores: (destinationId: String) -> SyncManifestStore,
    private val library: () -> List<MediaAsset>,
    private val fileSource: (config: SyncDestinationConfig, library: List<MediaAsset>) -> SyncFileSource,
    private val providers: SyncProviderFactory,
    private val network: () -> SyncNetwork,
    private val runner: () -> JobRunner?,
    override val supportedKinds: Set<SyncDestinationKind>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val retry: SyncRetryPolicy = SyncRetryPolicy(),
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val revoker: OAuthRevoker = OAuthRevoker.NONE,
) : SyncService {
    private val _config = MutableStateFlow(configStore.load())
    override val config: StateFlow<SyncDestinationConfig?> = _config.asStateFlow()
    private val _state = MutableStateFlow<SyncRunState>(SyncRunState.Idle)
    override val state: StateFlow<SyncRunState> = _state.asStateFlow()
    private var handle: JobHandle? = null
    private val stateLock = Any()

    /** Names the current run; [forgetDestination] moves it on, so a run that outlives it can no longer write the state or the settings. */
    @Volatile private var generation = 0

    override fun newDestinationId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    override suspend fun saveDestination(config: SyncDestinationConfig, secret: CharArray?): Result<Unit> = try {
        validate(config)
        withContext(io) {
            configStore.save(config)
            if (secret != null) secrets.put(config.id, secret)
        }
        _config.value = config
        Result.success(Unit)
    } catch (e: SyncException) {
        Result.failure(e)
    } catch (e: java.io.IOException) {
        Result.failure(SyncException(SyncFailure.REJECTED, e.message ?: "The settings could not be saved", e))
    } catch (e: java.security.GeneralSecurityException) {
        Result.failure(SyncException(SyncFailure.REJECTED, SyncCopy.SECRET_STORE_FAILED, e))
    } catch (e: java.security.ProviderException) {
        Result.failure(SyncException(SyncFailure.REJECTED, SyncCopy.SECRET_STORE_FAILED, e))
    } finally {
        secret?.fill('\u0000')
    }

    override suspend fun testConnection(config: SyncDestinationConfig, secret: CharArray?): Result<String> = try {
        validate(config)
        withContext(io) {
            val key = secret ?: secrets.get(config.id)
            providers.create(config, key).use { provider ->
                Result.success(provider.testConnection(Cancellation.NEVER))
            }
        }
    } catch (e: SyncException) {
        Result.failure(e)
    } finally {
        secret?.fill('\u0000')
    }

    override suspend fun preview(): Result<SyncPreview> = withContext(io) {
        runCatching {
            val config = _config.value ?: throw SyncException(SyncFailure.REJECTED, SyncCopy.NO_DESTINATION)
            val candidates = SyncCandidates.from(library(), config.selection)
            val manifest = manifestStores(config.id).load()
            val plan = SyncPlanner.plan(candidates, manifest, if (config.encryptEnabled) SyncEncryption.SUFFIX else "", config.byteCapPerRun)
            SyncPreview(plan.uploads.size, plan.totalBytes, plan.upToDate, plan.deferredByCap, plan.tooBigForCap, manifest.ignoredLines)
        }
    }

    override fun start(passphrase: CharArray?, allowMobileData: Boolean): SyncStartResult {
        // The job runs later, on another thread, and the caller wipes its buffer as soon as this returns:
        // keep our own copy for the job and wipe the caller's now.
        val owned = passphrase?.copyOf()
        passphrase?.fill('\u0000')
        fun refuse(reason: SyncStartRefusal): SyncStartResult {
            owned?.fill('\u0000')
            return SyncStartResult.Refused(reason)
        }
        val config = _config.value ?: return refuse(SyncStartRefusal.NO_DESTINATION)
        if (isRunning()) return refuse(SyncStartRefusal.ALREADY_RUNNING)
        if (config.kind !in supportedKinds) return refuse(SyncStartRefusal.NEEDS_CONNECT_BUILD)
        if (config.selection.isEmpty) return refuse(SyncStartRefusal.NOTHING_CHOSEN)
        if (config.encryptEnabled && !usablePassphrase(owned)) return refuse(SyncStartRefusal.PASSPHRASE_NEEDED)
        SyncGuard.check(config.kind.needsNetwork, config.wifiOnly, allowMobileData, network())?.let { return refuse(it) }
        val jobs = runner() ?: return refuse(SyncStartRefusal.NO_JOB_RUNNER)

        val gen = synchronized(stateLock) {
            _state.value = SyncRunMachine.reduce(SyncRunState.Idle, SyncRunEvent.Start)
            ++generation
        }
        handle = jobs.launch(SyncCopy.JOB_TITLE, ActivityKind.SYNCING) { onProgress ->
            try {
                val finalState = withContext(io) { execute(gen, config, owned, allowMobileData, onProgress) }
                Result.success(finalState)
            } finally {
                owned?.fill('\u0000')
            }
        }
        return SyncStartResult.Started
    }

    /** Empty, or only NULs (a buffer already wiped), is not a passphrase: a key made from it would be one anyone can guess. */
    private fun usablePassphrase(p: CharArray?): Boolean = p != null && p.isNotEmpty() && p.any { it != '\u0000' }

    override suspend fun signOut(unsavedToken: CharArray?, kind: SyncDestinationKind?, clientId: String?): Result<Unit> = withContext(io) {
        val config = _config.value
        // Local first: the stored token is gone and the account name cleared before the provider is even asked.
        val stored = config?.let { secrets.get(it.id) }
        if (config != null) {
            secrets.remove(config.id)
            val cleared = config.copy(oauth = config.oauth?.copy(accountLabel = ""))
            runCatching { configStore.save(cleared) }
            _config.value = cleared
        }
        // Best effort, on this tap only. Whatever happens the person is signed out here.
        stored?.let { token ->
            val savedId = config?.oauth?.clientId
            if (config != null && savedId != null) runCatching { revoker.revoke(config.kind, savedId, token) }
            token.fill('\u0000')
        }
        unsavedToken?.let { token ->
            val k = kind ?: config?.kind
            val id = clientId ?: config?.oauth?.clientId
            if (k != null && id != null) runCatching { revoker.revoke(k, id, token) }
            token.fill('\u0000')
        }
        Result.success(Unit)
    }

    override fun pause() {
        handle?.cancel()
    }

    override fun forgetDestination() {
        // Move the generation on first: the run being cancelled may still be on its way out on another
        // thread, and from here on whatever it does to the state or the settings is ignored.
        synchronized(stateLock) {
            generation++
            handle?.cancel()
            _config.value?.let { secrets.remove(it.id) }
            configStore.clear()
            _config.value = null
            _state.value = SyncRunState.Idle
        }
    }

    private fun isRunning(): Boolean = _state.value.let { it is SyncRunState.Preparing || it is SyncRunState.Running }

    private suspend fun execute(
        gen: Int,
        config: SyncDestinationConfig,
        passphrase: CharArray?,
        allowMobileData: Boolean,
        onProgress: (Float?) -> Unit,
    ): SyncRunState {
        var keys: BackupKeys? = null
        try {
            val encryption = if (config.encryptEnabled) {
                val opened = try {
                    openKeys(gen, config, passphrase ?: CharArray(0))
                } catch (e: SyncException) {
                    return fail(gen, e.failure, e.message.orEmpty())
                }
                keys = opened
                SyncEncryption(opened)
            } else {
                null
            }
            val snapshot = library()
            val provider = try {
                providers.create(config, secrets.get(config.id))
            } catch (e: SyncException) {
                return fail(gen, e.failure, e.message.orEmpty())
            }
            provider.use {
                val gate = BrowsableGate(library, config.selection)
                val engine = SyncEngine(
                    provider = provider,
                    files = fileSource(config, snapshot),
                    store = manifestStores(config.id),
                    gate = gate,
                    encryption = encryption,
                    retry = retry,
                    random = random,
                    clock = clock,
                    sleep = sleep,
                    betweenFiles = {
                        SyncGuard.check(provider.needsNetwork, config.wifiOnly, allowMobileData, network())?.let {
                            throw SyncException(SyncFailure.TRANSIENT, it.sentence)
                        }
                    },
                )
                val candidates = SyncCandidates.from(snapshot, config.selection)
                val result = engine.run(candidates, config.byteCapPerRun) { event ->
                    val now = update(gen) { SyncRunMachine.reduce(it, event) }
                    (now as? SyncRunState.Running)?.let { onProgress(it.progress) }
                }
                update(gen) { result.state }
                return result.state
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            update(gen) { SyncRunMachine.reduce(it, SyncRunEvent.Cancelled) }
            throw e
        } catch (e: Exception) {
            return fail(gen, SyncFailure.REJECTED, e.message ?: "Sync stopped unexpectedly")
        } finally {
            keys?.close()
        }
    }

    /** Changes the state only while [gen] is still the current run; returns the state after. */
    private fun update(gen: Int, change: (SyncRunState) -> SyncRunState): SyncRunState = synchronized(stateLock) {
        if (gen == generation) _state.value = change(_state.value)
        _state.value
    }

    private fun fail(gen: Int, failure: SyncFailure, message: String): SyncRunState =
        update(gen) { SyncRunMachine.reduce(it, SyncRunEvent.Aborted(failure, message)) }

    /** Derives the keys (slow); on the first run of a set, starts it and saves salt and check value; later, proves the passphrase. */
    private fun openKeys(gen: Int, config: SyncDestinationConfig, passphrase: CharArray): BackupKeys {
        if (!usablePassphrase(passphrase)) throw SyncException(SyncFailure.REJECTED, SyncCopy.ENCRYPT_PASSPHRASE_NEEDED)
        val known = config.encryption
        if (known == null) {
            val keys = BackupKeys.newSet(passphrase)
            val settings = SyncEncryptionSettings(SyncEncryption.hex(keys.setSalt), keys.iterations, SyncEncryption.keyCheck(keys))
            val updated = config.copy(encryption = settings)
            synchronized(stateLock) {
                if (gen != generation) {
                    keys.close()
                    throw kotlinx.coroutines.CancellationException("The destination was forgotten")
                }
                configStore.save(updated)
                _config.value = updated
            }
            return keys
        }
        val keys = BackupKeys.derive(passphrase, SyncEncryption.unhex(known.saltHex), known.iterations)
        if (!MessageDigest.isEqual(SyncEncryption.keyCheck(keys).toByteArray(), known.keyCheckHex.toByteArray())) {
            keys.close()
            throw SyncException(SyncFailure.REJECTED, SyncCopy.WRONG_PASSPHRASE)
        }
        return keys
    }

    private fun validate(config: SyncDestinationConfig) {
        if (config.kind !in supportedKinds) throw SyncException(SyncFailure.UNAVAILABLE_BUILD, SyncCopy.NEEDS_CONNECT_BUILD)
        val ok = when (config.kind) {
            SyncDestinationKind.FOLDER -> !config.folderTreeUri.isNullOrBlank()
            SyncDestinationKind.WEBDAV -> config.webDav != null && config.webDav.username.isNotBlank()
            SyncDestinationKind.S3 -> config.s3 != null
            SyncDestinationKind.GDRIVE, SyncDestinationKind.DROPBOX -> config.oauth?.clientId?.isNotBlank() == true
        }
        if (!ok) throw SyncException(SyncFailure.REJECTED, SyncCopy.NO_DESTINATION)
    }
}

/**
 * The upload-time re-check (TRAPS #33): a candidate may go only if it is still in the browsable list now
 * AND still matches the selection (a photo un-favourited since the plan was made is not sent).
 */
class BrowsableGate(private val browsable: () -> List<MediaAsset>, private val selection: SyncSelection) : UploadGate {
    private var seen: List<MediaAsset>? = null
    private var allowed: Set<Long> = emptySet()

    override fun mayUpload(candidate: SyncCandidate): Boolean {
        val now = browsable()
        if (now !== seen) {
            seen = now
            allowed = SyncCandidates.from(now, selection).mapTo(HashSet()) { it.mediaId }
        }
        return candidate.mediaId in allowed
    }
}
