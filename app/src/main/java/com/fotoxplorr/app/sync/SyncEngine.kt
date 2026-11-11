package com.fotoxplorr.app.sync

import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** The result of one run, whatever ended it. */
data class SyncRunResult(val state: SyncRunState)

/**
 * Sends the planned files one at a time and records each only once the destination holds it whole.
 *
 * Per file: ask the gate again (TRAPS #33) -> maybe recognise a renamed twin by SHA-256 (the only
 * place a file is hashed before sending) -> look at the destination (a file already there with the
 * size we would send is ADOPTED, not sent again; a different file there makes our name unique) ->
 * write a `B` line -> send, with bounded jittered retries for transient trouble -> check the size the
 * server reports -> write the `D` line. A cancelled run leaves `B` lines at worst, and a `B` with no
 * `D` is simply sent again next time. Nothing is deleted and nothing but our own half-sent file is
 * ever overwritten.
 *
 * Failure policy: AUTH, NO_SPACE, UNSAFE, DESTINATION_GONE and a missing build stop the run (they
 * will not clear by trying the next file); a REJECTED file is counted and skipped; TRANSIENT trouble
 * that outlasts the retries stops the run as "connection" so the person can start it again later.
 */
class SyncEngine(
    private val provider: SyncProvider,
    private val files: SyncFileSource,
    private val store: SyncManifestStore,
    private val gate: UploadGate,
    private val encryption: SyncEncryption? = null,
    private val retry: SyncRetryPolicy = SyncRetryPolicy(),
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val root: String = RemoteLayout.ROOT,
    /** Called before every file; throws a [SyncException] to stop the run (the connection went away, Wi-Fi was lost). */
    private val betweenFiles: () -> Unit = {},
) {
    /** Plans and runs. [onEvent] is called from the running coroutine. Throws [CancellationException] when cancelled. */
    suspend fun run(candidates: List<SyncCandidate>, byteCap: Long?, onEvent: (SyncRunEvent) -> Unit): SyncRunResult {
        var state: SyncRunState = SyncRunState.Idle
        fun emit(event: SyncRunEvent) {
            state = SyncRunMachine.reduce(state, event)
            onEvent(event)
        }
        emit(SyncRunEvent.Start)
        val manifest = store.load()
        val plan = SyncPlanner.plan(candidates, manifest, encryption?.let { SyncEncryption.SUFFIX }.orEmpty(), byteCap, root)
        var tally = SyncTally(upToDate = plan.upToDate, deferredByCap = plan.deferredByCap, tooBigForCap = plan.tooBigForCap)
        emit(SyncRunEvent.Planned(plan.uploads.size, plan.totalBytes, tally))
        val ensured = HashSet<String>()
        val taken = HashSet(manifest.remotePathsLower())
        try {
            for (item in plan.uploads) {
                currentCoroutineContext().ensureActive()
                betweenFiles()
                val size = item.candidate.sizeBytes
                val outcome = try {
                    sendOne(item, manifest, ensured, taken) { emit(SyncRunEvent.Bytes(it)) }
                } catch (e: SyncException) {
                    // One file the server will not take is that file's problem; every other failure ends the run.
                    if (e.failure != SyncFailure.REJECTED) throw e
                    Outcome.FAILED
                }
                tally = when (outcome) {
                    Outcome.SENT -> tally.copy(uploaded = tally.uploaded + 1, uploadedBytes = tally.uploadedBytes + size)
                    Outcome.ADOPTED -> tally.copy(adopted = tally.adopted + 1)
                    Outcome.SKIPPED -> tally.copy(skipped = tally.skipped + 1)
                    Outcome.FAILED -> tally.copy(failed = tally.failed + 1)
                }
                emit(SyncRunEvent.FileFinished(size, tally))
            }
            emit(SyncRunEvent.Completed)
        } catch (e: CancellationException) {
            emit(SyncRunEvent.Cancelled)
            throw e
        } catch (e: SyncException) {
            emit(SyncRunEvent.Aborted(e.failure, e.message.orEmpty()))
        }
        runCatching { store.compact(manifest) }
        return SyncRunResult(state)
    }

    private enum class Outcome { SENT, ADOPTED, SKIPPED, FAILED }

    private suspend fun sendOne(
        item: PlannedUpload,
        manifest: SyncManifest,
        ensured: MutableSet<String>,
        taken: MutableSet<String>,
        onBytes: (Long) -> Unit,
    ): Outcome {
        val candidate = item.candidate
        if (!gate.mayUpload(candidate)) return Outcome.SKIPPED

        item.lookalike?.let { twin ->
            val hash = hashLocal(candidate) ?: return Outcome.SKIPPED
            if (hash == twin.sha256) {
                record(manifest, doneOf(candidate, twin.remotePath, twin.remoteSize, hash))
                return Outcome.ADOPTED
            }
        }

        val expectedRemote = encryption?.remoteSize(candidate.sizeBytes) ?: candidate.sizeBytes
        var path = item.remotePath
        var overwrite = item.overwriteOwnPartial
        if (!overwrite) {
            // Look at the destination first: the manifest may have been lost, or someone else may own this name.
            var attempts = 0
            while (true) {
                val existing = withRetry { provider.stat(path) } ?: break
                if (!existing.isDirectory && existing.sizeBytes == expectedRemote && sameFile(existing, candidate)) {
                    record(manifest, doneOf(candidate, path, expectedRemote, null))
                    return Outcome.ADOPTED
                }
                // Same name, and nothing says it is the same photo: the name is someone else's. Take another.
                val current = path
                path = RemoteLayout.unique(path) { it.lowercase(Locale.ROOT) == current.lowercase(Locale.ROOT) || it.lowercase(Locale.ROOT) in taken }
                check(++attempts < MAX_NAME_PROBES) { "No free name at the destination" }
            }
        }
        taken += path.lowercase(Locale.ROOT)

        withRetry {
            for (folder in RemoteLayout.foldersOf(path)) if (ensured.add(folder)) provider.ensureFolder(folder)
        }
        manifest.recordBegun(SyncBegun(candidate.key, path, candidate.sizeBytes, candidate.dateModifiedSeconds))
        store.append(SyncManifestCodec.begunLine(manifest.begunFor(candidate.key)!!))

        val cancel = cancellationFromJob()
        var localMismatches = 0
        val sent = withRetry {
            val opened = files.open(candidate) ?: return@withRetry Put.Gone
            opened.use {
                // A fresh digest per attempt: a retry starts the file again from byte zero.
                val digest = MessageDigest.getInstance("SHA-256")
                val plain: InputStream = DigestInputStream(opened.input, digest)
                val body = if (encryption != null) encryption.wrap(plain) else plain
                val entry = try {
                    provider.put(path, body, expectedRemote, overwrite, cancel, onBytes)
                } catch (e: SyncLocalChanged) {
                    // MediaStore's size can be stale or the file may be growing: look once more, then leave this
                    // file out. Retrying it for ever would put it first (newest first) in every later run too.
                    if (++localMismatches >= 2) throw SyncException(SyncFailure.REJECTED, e.message.orEmpty(), e)
                    throw e
                }
                Put.Sent(entry, digest.digest().toHex())
            }
        }
        if (sent !is Put.Sent) return Outcome.SKIPPED
        // Done only when the destination confirms the file: a missing answer (a proxy's 200 with junk, a
        // listing that does not show it yet) is looked at once more, and if it is still not confirmed the
        // file is NOT recorded. The B line stays, so a later run sends it again.
        var entry = sent.entry
        if (!confirms(entry, expectedRemote)) entry = withRetry { provider.stat(path) }
        if (!confirms(entry, expectedRemote) && provider.sizeReportReliable) {
            throw SyncException(SyncFailure.REJECTED, SyncCopy.UNCONFIRMED)
        }
        record(manifest, doneOf(candidate, path, expectedRemote, sent.sha256))
        return Outcome.SENT
    }

    private sealed interface Put {
        data object Gone : Put
        class Sent(val entry: RemoteEntry?, val sha256: String) : Put
    }

    private fun confirms(entry: RemoteEntry?, expected: Long) = entry != null && !entry.isDirectory && entry.sizeBytes == expected

    /**
     * Whether an existing remote file of the right size is this photo: a content hash that matches the
     * local bytes (only for unencrypted copies), or a modified time equal to the local one. Size and name
     * alone are not enough: two different photos can share both.
     */
    private fun sameFile(remote: RemoteEntry, candidate: SyncCandidate): Boolean {
        if (encryption == null && remote.md5Hex != null) {
            return hashLocalMd5(candidate) == remote.md5Hex
        }
        val modified = remote.modifiedMillis ?: return false
        return kotlin.math.abs(modified - candidate.dateModifiedSeconds * 1000L) <= MTIME_TOLERANCE_MILLIS
    }

    private fun hashLocalMd5(candidate: SyncCandidate): String? {
        val opened = files.open(candidate) ?: return null
        return opened.use {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = it.input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            digest.digest().toHex()
        }
    }

    private fun record(manifest: SyncManifest, done: SyncDone) {
        manifest.recordDone(done)
        store.append(SyncManifestCodec.doneLine(done))
    }

    private fun doneOf(candidate: SyncCandidate, path: String, remoteSize: Long, sha: String?) = SyncDone(
        key = candidate.key,
        sizeBytes = candidate.sizeBytes,
        modifiedSeconds = candidate.dateModifiedSeconds,
        remotePath = path,
        remoteSize = remoteSize,
        sha256 = sha,
        atMillis = clock(),
    )

    private fun hashLocal(candidate: SyncCandidate): String? {
        val opened = files.open(candidate) ?: return null
        return opened.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = it.input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            digest.digest().toHex()
        }
    }

    /** Runs [block] up to the policy's attempts while the failure is transient, sleeping the jittered delay between. */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (e: SyncException) {
                if (!retry.shouldRetry(e.failure, attempt)) throw e
                attempt++
                val asked = (e as? SyncRateLimited)?.retryAfterMillis?.coerceIn(0L, MAX_RETRY_AFTER_MILLIS) ?: 0L
                sleep(maxOf(retry.delayBefore(attempt, random), asked))
            }
        }
    }

    private suspend fun cancellationFromJob(): Cancellation {
        val context = currentCoroutineContext()
        return Cancellation { context.ensureActive() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it) }

    private companion object {
        const val MAX_NAME_PROBES = 50
        const val MTIME_TOLERANCE_MILLIS = 2_000L
        const val MAX_RETRY_AFTER_MILLIS = 120_000L
    }
}
