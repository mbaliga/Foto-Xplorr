package com.fotoxplorr.app.backup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.fotoxplorr.app.BuildConfig
import com.fotoxplorr.app.LibraryRuntime
import com.fotoxplorr.app.editor.RecipeStore
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.app.spatial.GeoMetadataRepository
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class BackupPurpose { BACKUP, CHECK, RESTORE }

/** Where the person is in "Back up everything, encrypted", "Check a backup" or the encrypted restore. */
internal sealed interface BackupStage {
    data object Idle : BackupStage

    /** Something is being read or worked out; the person can stop it. */
    class Working(val message: String) : BackupStage

    /** [newSet]: the folder holds no backup, so the person is choosing a passphrase, not entering one. */
    class Passphrase(val purpose: BackupPurpose, val newSet: Boolean, val folderLabel: String, val wrong: Boolean) : BackupStage

    /** The plan, shown before anything is written. */
    class Preflight(
        val preparation: BackupPreparation,
        val folderLabel: String,
        val onPhoneStorage: Boolean,
        val managedByAnotherApp: Boolean,
        val freeBytes: Long?,
        val leftoversConfirm: Boolean = false,
        /** This phone's tags, collections, favourites or locks are fewer than the newest snapshot holds. */
        val sectionsFewer: Boolean = false,
    ) : BackupStage {
        fun withLeftoversConfirm(confirm: Boolean) =
            Preflight(preparation, folderLabel, onPhoneStorage, managedByAnotherApp, freeBytes, confirm, sectionsFewer)
    }

    class Message(val title: String, val text: String) : BackupStage

    /** The backup is open: what it holds, and the choice of what to put back. */
    class RestoreChoose(val set: RestoreSet, val folderLabel: String) : BackupStage

    /** What the chosen restore would do on this phone, before anything is written. */
    class RestoreReview(val preparation: RestorePreparation, val folderLabel: String, val freeBytes: Long?) : BackupStage
}

/**
 * The state machine behind the two Settings rows. It owns nothing the rest of the app reads:
 * no `GalleryActions` field, no `GalleryUiState` field. It reaches the process's stores itself,
 * at the moment a button is tapped, so what it sees is current, and it launches its job on the
 * activity's `JobRunner` so the notification, the shade and Cancel come for free.
 *
 * The passphrase is held as typed only in the dialog's text state and, while it is derived, in a
 * `CharArray` that [BackupKeys.derive] wipes. It is never written to a Bundle, a preference, a
 * file or a log: nothing here uses `rememberSaveable`, so a rotation or process death asks for it
 * again. The derived key lives in [keys] until the job (which takes it over) or [dispose] closes it.
 */
internal class BackupFlow(
    context: Context,
    private val scope: CoroutineScope,
    private val jobRunner: () -> JobRunner?,
) {
    private val appContext = context.applicationContext

    var stage: BackupStage by mutableStateOf(BackupStage.Idle)
        private set

    /** Bumped when the location permission may have changed, so the preflight card re-reads it. */
    var permissionRevision by mutableIntStateOf(0)
        private set

    /** Set by the screen: opens the system folder picker. */
    var pickFolder: () -> Unit = {}

    private var purpose = BackupPurpose.BACKUP
    private var treeUri: Uri? = null
    private var grantTaken = false
    private var destination: SafBackupDestination? = null
    private var setState: SetState = SetState.Empty
    private var keys: BackupKeys? = null
    private var snapshot: List<MediaAsset> = emptyList()
    private var work: Job? = null

    /** The activity's job runner, or null where there is no activity (a preview). */
    fun runner(): JobRunner? = jobRunner()

    fun isRunning(): Boolean = jobRunner()?.jobs?.value?.any { isBackupJob(it.title) } == true

    fun begin(chosen: BackupPurpose) {
        if (isRunning()) {
            stage = BackupStage.Message(BackupCopy.OPTION_LABEL, BackupCopy.ALREADY_RUNNING)
            return
        }
        purpose = chosen
        pickFolder()
    }

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        releaseFolder()
        // The grant of a picked folder is otherwise tied to this screen; a backup outlives it. A grant
        // the app already held for this folder (another feature's) is left alone and never released here.
        val resolver = appContext.contentResolver
        val alreadyHeld = runCatching {
            resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        }.getOrDefault(false)
        grantTaken = !alreadyHeld && runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.isSuccess
        treeUri = uri
        val folder = SafBackupDestination(appContext, uri)
        destination = folder
        stage = BackupStage.Working(BackupCopy.CHECKING_FOLDER)
        work = scope.launch {
            try {
                val state = withContext(Dispatchers.IO) { BackupSetLoader.inspect(folder) }
                setState = state
                stage = when {
                    state is SetState.Unreadable -> BackupStage.Message(BackupCopy.OPTION_LABEL, state.reason)
                    state is SetState.Empty && purpose != BackupPurpose.BACKUP ->
                        BackupStage.Message(labelFor(purpose), "This folder has no backup in it.")
                    else -> BackupStage.Passphrase(purpose, newSet = state is SetState.Empty, folderLabel = folder.label, wrong = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e, "The folder could not be read.")
            }
        }
    }

    /** The passphrase form was submitted. [passphrase] is wiped by the key derivation. */
    fun submit(passphrase: CharArray) {
        val folder = destination ?: return
        val state = setState
        stage = BackupStage.Working(BackupCopy.DERIVING)
        work = scope.launch {
            var opened: BackupKeys? = null
            try {
                opened = withContext(Dispatchers.Default) { openKeys(passphrase, state) }
                // The derivation cannot be interrupted, so a screen that went away meanwhile is noticed here.
                ensureActive()
                keys = opened
                when (purpose) {
                    BackupPurpose.BACKUP -> plan(folder, opened)
                    BackupPurpose.CHECK -> check(folder, opened)
                    BackupPurpose.RESTORE -> openForRestore(folder, opened)
                }
            } catch (e: CancellationException) {
                opened?.close()
                throw e
            } catch (e: Exception) {
                closeKeys()
                opened?.close()
                fail(e, "The backup could not be opened.")
            }
        }
    }

    /** Opens the set (the passphrase is proved here, not inside the job) and then runs the check as a job. */
    private suspend fun check(folder: SafBackupDestination, opened: BackupKeys) {
        stage = BackupStage.Working(BackupCopy.CHECKING_FOLDER)
        val loaded = try {
            withContext(Dispatchers.IO) { BackupSetLoader.load(folder, opened) }
        } catch (e: BackupException.WrongPassphraseOrDamaged) {
            closeKeys()
            stage = BackupStage.Passphrase(purpose, newSet = false, folderLabel = folder.label, wrong = true)
            return
        }
        startCheck(folder, opened, loaded)
    }

    /** The keys for [state]'s set, or a [BackupRefused] with an honest sentence when this phone's crypto fails its self-check. */
    private suspend fun openKeys(passphrase: CharArray, state: SetState): BackupKeys {
        val check = BackupSelfCheck.run()
        if (check != BackupGoldenVector.Result.PASSED) {
            passphrase.fill('\u0000')
            throw BackupRefused(
                if (check == BackupGoldenVector.Result.KEY_DERIVATION) BackupCopy.KEY_SELF_CHECK_FAILED else BackupCopy.CIPHER_SELF_CHECK_FAILED,
            )
        }
        return when (state) {
            is SetState.Existing -> BackupKeys.derive(passphrase, state.header.setSalt, state.header.iterations)
            else -> BackupKeys.newSet(passphrase)
        }
    }

    private suspend fun plan(folder: SafBackupDestination, opened: BackupKeys) {
        stage = BackupStage.Working(BackupCopy.PLANNING)
        val assets = LibraryRuntime.get(appContext).repository.awaitLoaded().filterNot { it.isExternal }
        if (assets.isEmpty()) {
            closeKeys()
            stage = BackupStage.Message(BackupCopy.OPTION_LABEL, BackupCopy.NO_CATALOGUE)
            return
        }
        snapshot = assets
        val locationReadable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && locationGranted()
        val prepared = try {
            withContext(Dispatchers.IO) {
                val locked = PrivateFolderStore(appContext).observeLockedFolders().value
                val sensitive = SensitiveStore(appContext).observe().value
                val archived = LibraryStore.get(appContext).observe().value.archivedIds
                val candidates = BackupCandidates.from(assets, locked, sensitive, archived)
                val byId = assets.associateBy { it.id.value }
                BackupPreparer.prepare(
                    folder, opened, candidates, BackupOptions(), MediaStoreBackupSource(appContext, byId),
                    locationReadable = locationReadable,
                )
            }
        } catch (e: BackupException.WrongPassphraseOrDamaged) {
            closeKeys()
            stage = BackupStage.Passphrase(purpose, newSet = false, folderLabel = folder.label, wrong = true)
            return
        }
        val free = withContext(Dispatchers.IO) { folder.freeBytes() }
        // The job writes the previous snapshot's tags and locks into the new one anyway; this only says so up front.
        val newest = prepared.loaded.manifest?.sections
        val fewer = newest != null && SectionMerge.isFewer(sectionSource().gather(assets, System.currentTimeMillis()), newest)
        stage = BackupStage.Preflight(
            preparation = prepared,
            folderLabel = folder.label,
            onPhoneStorage = folder.isOnPhoneStorage,
            managedByAnotherApp = folder.isManagedByAnotherApp,
            freeBytes = free,
            sectionsFewer = fewer,
        )
    }

    /** The facts the preflight card is drawn from, with the two that can change under it read now. */
    fun factsFor(preflight: BackupStage.Preflight): BackupCopy.PreflightFacts {
        val prep = preflight.preparation
        val plan = prep.plan
        val tooLarge = plan.skippedFor(SkipReason.TOO_LARGE_FOR_PACK)
        return BackupCopy.PreflightFacts(
            newFiles = plan.copies.size,
            newSourceBytes = plan.bytesToCopy,
            estimatedBytes = prep.estimatedBytes,
            packs = plan.packs.size,
            alreadyBackedUp = plan.unchanged.size + plan.moved.size,
            tooLargeNames = tooLarge.map { it.candidate.displayName },
            tooLargeCount = tooLarge.size,
            leftoverCount = prep.loaded.leftovers.size,
            leftoverBytes = prep.loaded.leftoverBytes,
            freeBytes = preflight.freeBytes,
            photosSeen = prep.candidateCount,
            locationApplicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            locationGranted = locationGranted(),
            onPhoneStorage = preflight.onPhoneStorage,
            managedByAnotherApp = preflight.managedByAnotherApp,
            batteryLowAndNotCharging = batteryLowAndNotCharging(),
            newSet = prep.isNewSet,
            locationRecopies = plan.locationRecopies,
            foreignCount = prep.loaded.foreignFiles.size,
            sectionsFewer = preflight.sectionsFewer,
            unverifiedCount = prep.loaded.unverified.size,
            leftoversRemovable = prep.loaded.leftoversRemovable,
        )
    }

    fun locationGranted(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun permissionMayHaveChanged() {
        permissionRevision++
    }

    /** Hands the plan to the job runner. From here the notification is the progress display. */
    fun start() {
        val current = stage as? BackupStage.Preflight ?: return
        val runner = jobRunner()
        val folder = destination
        val opened = keys
        if (runner == null || folder == null || opened == null) return
        val assets = snapshot
        val prep = current.preparation
        val uri = treeUri.takeIf { grantTaken }
        keys = null
        destination = null
        treeUri = null
        stage = BackupStage.Idle
        val byId = assets.associateBy { it.id.value }
        runner.launch(title = BackupCopy.JOB_TITLE, kind = ActivityKind.BACKING_UP) { onProgress ->
            val wake = holdWakeLock()
            try {
                attempt {
                    val job = BackupJob(
                        destination = folder,
                        source = MediaStoreBackupSource(appContext, byId),
                        keys = opened,
                        appVersion = BuildConfig.VERSION_NAME,
                        sections = { sectionSource().gather(assets, System.currentTimeMillis()) },
                    )
                    val outcome = job.run(prep) { onProgress(it.fraction) }
                    // A clean run is a passing notice. One that left files out says so in a dialog, which stays.
                    if (outcome.needsAttention) throw BackupRefused(BackupCopy.done(outcome)) else BackupCopy.done(outcome)
                }
            } finally {
                opened.close()
                wake?.let { runCatching { if (it.isHeld) it.release() } }
                releaseGrant(uri)
            }
        }
    }

    private fun startCheck(folder: SafBackupDestination, opened: BackupKeys, loaded: LoadedSet) {
        val runner = jobRunner()
        val uri = treeUri.takeIf { grantTaken }
        if (runner == null) {
            closeKeys()
            return
        }
        keys = null
        destination = null
        treeUri = null
        stage = BackupStage.Idle
        runner.launch(title = BackupCopy.CHECK_JOB_TITLE, kind = ActivityKind.BACKING_UP) { onProgress ->
            val wake = holdWakeLock()
            try {
                attempt {
                    val report = BackupVerifier.verify(folder, opened, onProgress = { onProgress(it) }, loaded = loaded)
                    when {
                        !report.isClean -> throw BackupRefused(BackupCopy.verifyProblems(report))
                        // A check that fell back or left files unread says so in a dialog, which stays.
                        report.hasCaveats -> throw BackupRefused(BackupCopy.verifyClean(report))
                        else -> BackupCopy.verifyClean(report)
                    }
                }.recoverWrongPassphrase()
            } finally {
                opened.close()
                wake?.let { runCatching { if (it.isHeld) it.release() } }
                releaseGrant(uri)
            }
        }
    }

    /** Opens the set (the passphrase is proved here) and shows what it holds, with the choice of what to restore. */
    private suspend fun openForRestore(folder: SafBackupDestination, opened: BackupKeys) {
        stage = BackupStage.Working(BackupCopy.CHECKING_FOLDER)
        val loaded = try {
            withContext(Dispatchers.IO) { BackupSetLoader.load(folder, opened) }
        } catch (e: BackupException.WrongPassphraseOrDamaged) {
            closeKeys()
            stage = BackupStage.Passphrase(purpose, newSet = false, folderLabel = folder.label, wrong = true)
            return
        }
        val earlier = withContext(Dispatchers.IO) { BackupSetLoader.olderSections(folder, opened, loaded) }
        val set = RestoreSet.from(loaded, earlier)
        if (set.isEmpty) {
            closeKeys()
            releaseFolder()
            stage = BackupStage.Message(RestoreCopy.ROW_LABEL, RestoreCopy.NOTHING_IN_BACKUP)
            return
        }
        stage = BackupStage.RestoreChoose(set, folder.label)
    }

    /** The person chose what to restore: work out what that would do on this phone and show it. */
    fun prepareRestore(what: RestoreScope) {
        val current = stage as? BackupStage.RestoreChoose ?: return
        stage = BackupStage.Working(RestoreCopy.LOOKING)
        work = scope.launch {
            try {
                val assets = LibraryRuntime.get(appContext).repository.awaitLoaded()
                snapshot = assets
                val prepared = RestorePreparer.prepare(current.set, what, AndroidRestore.target(appContext, assets))
                val free = withContext(Dispatchers.IO) {
                    runCatching { Environment.getExternalStorageDirectory().usableSpace }.getOrNull()?.takeIf { it > 0 }
                }
                stage = BackupStage.RestoreReview(prepared, current.folderLabel, free)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeKeys()
                fail(e, "The photos on this phone could not be read.")
            }
        }
    }

    fun backToRestoreChoice() {
        val current = stage as? BackupStage.RestoreReview ?: return
        val folder = destination ?: return
        stage = BackupStage.RestoreChoose(current.preparation.set, folder.label)
    }

    fun reviewFacts(review: BackupStage.RestoreReview): RestoreCopy.ReviewFacts = RestoreCopy.ReviewFacts(
        freeBytes = review.freeBytes,
        locationApplicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        locationGranted = locationGranted(),
    )

    /** Hands the prepared restore to the job runner. From here the notification is the progress display. */
    fun startRestore() {
        val current = stage as? BackupStage.RestoreReview ?: return
        val runner = jobRunner()
        val folder = destination
        val opened = keys
        if (runner == null || folder == null || opened == null) return
        val assets = snapshot
        val prep = current.preparation
        val uri = treeUri.takeIf { grantTaken }
        keys = null
        destination = null
        treeUri = null
        stage = BackupStage.Idle
        runner.launch(title = RestoreCopy.JOB_TITLE, kind = ActivityKind.BACKING_UP) { onProgress ->
            val wake = holdWakeLock()
            try {
                attempt {
                    val job = RestoreJob(folder, opened, AndroidRestore.target(appContext, assets), AndroidRestore.applier(appContext))
                    val outcome = job.run(prep) { onProgress(it) }
                    AndroidRestore.rescan(appContext)
                    val text = RestoreCopy.done(outcome)
                    // A clean restore is a passing notice. One that left something out says so in a dialog, which stays.
                    if (outcome.needsAttention) throw BackupRefused(text) else text
                }
            } finally {
                opened.close()
                wake?.let { runCatching { if (it.isHeld) it.release() } }
                releaseGrant(uri)
            }
        }
    }

    /**
     * The person chose to remove the leftovers they were shown. The folder is listed and classified
     * again first and only the shown files that are STILL leftovers are deleted ([LeftoverRemoval]):
     * a folder that finished syncing since the preflight may show some of them to be the real set's.
     */
    fun removeLeftovers() {
        val current = stage as? BackupStage.Preflight ?: return
        val folder = destination ?: return
        val opened = keys ?: return
        val names = current.preparation.loaded.leftovers.map { it.name }
        stage = BackupStage.Working(BackupCopy.PLANNING)
        work = scope.launch {
            try {
                withContext(Dispatchers.IO) { LeftoverRemoval.remove(folder, opened, names) }
                plan(folder, opened)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeKeys()
                fail(e, "The leftover files could not be removed.")
            }
        }
    }

    fun askRemoveLeftovers() {
        val current = stage as? BackupStage.Preflight ?: return
        stage = current.withLeftoversConfirm(true)
    }

    fun keepLeftovers() {
        val current = stage as? BackupStage.Preflight ?: return
        stage = current.withLeftoversConfirm(false)
    }

    /** Back to the passphrase form after a wrong one; the folder stays picked. */
    fun cancel() {
        work?.cancel()
        work = null
        closeKeys()
        releaseFolder()
        stage = BackupStage.Idle
    }

    fun dismissMessage() {
        if (stage is BackupStage.Message) stage = BackupStage.Idle
    }

    fun dispose() {
        work?.cancel()
        closeKeys()
        if (stage !is BackupStage.Idle) releaseFolder()
    }

    private fun fail(error: Exception, fallback: String) {
        releaseFolder()
        val text = when {
            error is BackupRefused -> error.message ?: fallback
            error is BackupException.WrongPassphraseOrDamaged -> BackupCopy.WRONG_PASSPHRASE
            error is BackupException.AnotherBackupHere -> error.message ?: fallback
            error is FolderNotReadyException -> error.message ?: fallback
            error is BackupException -> "${error.message}. ${BackupCopy.CHOOSE_ANOTHER_FOLDER}"
            error is SecurityException -> "Android would not let Fotoz use that folder."
            error is IOException -> fallback
            else -> fallback
        }
        stage = BackupStage.Message(labelFor(purpose), text)
    }

    private fun labelFor(purpose: BackupPurpose): String = when (purpose) {
        BackupPurpose.BACKUP -> BackupCopy.OPTION_LABEL
        BackupPurpose.CHECK -> BackupCopy.CHECK_LABEL
        BackupPurpose.RESTORE -> RestoreCopy.ROW_LABEL
    }

    private fun closeKeys() {
        keys?.close()
        keys = null
    }

    private fun releaseFolder() {
        val uri = treeUri.takeIf { grantTaken }
        treeUri = null
        destination = null
        grantTaken = false
        releaseGrant(uri)
    }

    private fun releaseGrant(uri: Uri?) {
        if (uri == null) return
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun sectionSource() = BackupSectionSource(
        libraryStore = LibraryStore.get(appContext),
        favoriteStore = FavoriteStore(appContext),
        sensitiveStore = SensitiveStore(appContext),
        privateFolderStore = PrivateFolderStore(appContext),
        geoRepository = GeoMetadataRepository(appContext),
        recipeStore = RecipeStore(appContext),
    )

    /**
     * Keeps the CPU running while the screen is off: a copy that stops when the screen turns off is
     * not a backup. Released in the job's `finally`, and on its own after [WAKE_LOCK_LIMIT_MILLIS].
     */
    private fun holdWakeLock(): PowerManager.WakeLock? = runCatching {
        appContext.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fotoz:backup")
            ?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_LIMIT_MILLIS)
            }
    }.getOrNull()

    private fun batteryLowAndNotCharging(): Boolean {
        val status = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val state = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL
        if (level < 0 || scale <= 0) return false
        return level * 100 / scale < LOW_BATTERY_PERCENT && !charging
    }

    private companion object {
        const val LOW_BATTERY_PERCENT = 20
        const val WAKE_LOCK_LIMIT_MILLIS = 6L * 60 * 60 * 1000
    }
}

/** True for the title of any job the backup flow starts: a backup, a check, or a restore. */
internal fun isBackupJob(title: String): Boolean =
    title == BackupCopy.JOB_TITLE || title == BackupCopy.CHECK_JOB_TITLE || title == RestoreCopy.JOB_TITLE

/**
 * The crypto self-check, run once per process on the first backup, check or restore (ADR-014): it
 * proves this phone's PBKDF2 turns the passphrase into the same key every other phone gets, that
 * the cipher opens a known encrypted file, and that what it seals opens again (and a damaged copy
 * does not). It is the release build's only on-device proof of the decrypt side. A failure means a
 * backup made here might not open later, so none is started.
 */
internal object BackupSelfCheck {
    @Volatile
    private var result: BackupGoldenVector.Result? = null

    suspend fun run(): BackupGoldenVector.Result = result ?: withContext(Dispatchers.Default) {
        BackupGoldenVector.run()
    }.also { result = it }
}

/** A wrong passphrase in a check is the same sentence the passphrase form uses. */
private fun Result<String>.recoverWrongPassphrase(): Result<String> = exceptionOrNull()?.let { error ->
    if (error is BackupException.WrongPassphraseOrDamaged) Result.failure(BackupRefused(BackupCopy.WRONG_PASSPHRASE)) else this
} ?: this
