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
) : SyncService {
    private val _config = MutableStateFlow(configStore.load())
    override val config: StateFlow<SyncDestinationConfig?> = _config.asStateFlow()
    private val _state = MutableStateFlow<SyncRunState>(SyncRunState.Idle)
    override val state: StateFlow<SyncRunState> = _state.asStateFlow()
    private var handle: JobHandle? = null

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
        fun refuse(reason: SyncStartRefusal): SyncStartResult {
            passphrase?.fill('\u0000')
            return SyncStartResult.Refused(reason)
        }
        val config = _config.value ?: return refuse(SyncStartRefusal.NO_DESTINATION)
        if (isRunning()) return refuse(SyncStartRefusal.ALREADY_RUNNING)
        if (config.kind !in supportedKinds) return refuse(SyncStartRefusal.NEEDS_CONNECT_BUILD)
        if (config.selection.isEmpty) return refuse(SyncStartRefusal.NOTHING_CHOSEN)
        if (config.encryptEnabled && (passphrase == null || passphrase.isEmpty())) return refuse(SyncStartRefusal.PASSPHRASE_NEEDED)
        SyncGuard.check(config.kind.needsNetwork, config.wifiOnly, allowMobileData, network())?.let { return refuse(it) }
        val jobs = runner() ?: return refuse(SyncStartRefusal.NO_JOB_RUNNER)

        _state.value = SyncRunMachine.reduce(SyncRunState.Idle, SyncRunEvent.Start)
        handle = jobs.launch(SyncCopy.JOB_TITLE, ActivityKind.SYNCING) { onProgress ->
            try {
                val finalState = withContext(io) { execute(config, passphrase, allowMobileData, onProgress) }
                Result.success(finalState)
            } finally {
                passphrase?.fill('\u0000')
            }
        }
        return SyncStartResult.Started
    }

    override fun pause() {
        handle?.cancel()
    }

    override fun forgetDestination() {
        handle?.cancel()
        _config.value?.let { secrets.remove(it.id) }
        configStore.clear()
        _config.value = null
        _state.value = SyncRunState.Idle
    }

    private fun isRunning(): Boolean = _state.value.let { it is SyncRunState.Preparing || it is SyncRunState.Running }

    private suspend fun execute(
        config: SyncDestinationConfig,
        passphrase: CharArray?,
        allowMobileData: Boolean,
        onProgress: (Float?) -> Unit,
    ): SyncRunState {
        var keys: BackupKeys? = null
        try {
            val encryption = if (config.encryptEnabled) {
                val opened = try {
                    openKeys(config, passphrase ?: CharArray(0))
                } catch (e: SyncException) {
                    return fail(e.failure, e.message.orEmpty())
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
                return fail(e.failure, e.message.orEmpty())
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
                    _state.value = SyncRunMachine.reduce(_state.value, event)
                    (_state.value as? SyncRunState.Running)?.let { onProgress(it.progress) }
                }
                _state.value = result.state
                return result.state
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = SyncRunMachine.reduce(_state.value, SyncRunEvent.Cancelled)
            throw e
        } catch (e: Exception) {
            return fail(SyncFailure.REJECTED, e.message ?: "Sync stopped unexpectedly")
        } finally {
            keys?.close()
        }
    }

    private fun fail(failure: SyncFailure, message: String): SyncRunState {
        val failed = SyncRunMachine.reduce(_state.value, SyncRunEvent.Aborted(failure, message))
        _state.value = failed
        return failed
    }

    /** Derives the keys (slow); on the first run of a set, starts it and saves salt and check value; later, proves the passphrase. */
    private fun openKeys(config: SyncDestinationConfig, passphrase: CharArray): BackupKeys {
        val known = config.encryption
        if (known == null) {
            val keys = BackupKeys.newSet(passphrase)
            val settings = SyncEncryptionSettings(SyncEncryption.hex(keys.setSalt), keys.iterations, SyncEncryption.keyCheck(keys))
            val updated = config.copy(encryption = settings)
            configStore.save(updated)
            _config.value = updated
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
