package com.fotoxplorr.app.receive

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the share-receive screen is showing. */
sealed interface ReceiveStage {
    data object Preparing : ReceiveStage

    /** The choice sheet. */
    data class Choice(val plan: ReceivePlan) : ReceiveStage

    /** Nothing usable was shared. */
    data class NothingUsable(val plan: ReceivePlan) : ReceiveStage

    data class Viewing(val plan: ReceivePlan) : ReceiveStage

    /** Saving, and after it ends the result; [ReceiveViewModel.saving] tells which. */
    data class Saving(val plan: ReceivePlan, val session: ReceiveSession) : ReceiveStage
}

/** One shared uri before it is checked; the activity extracts these from the intent. */
data class ReceivedUri(val uri: String)

/**
 * Holds the share-receive run so a rotation or a Cancel does not lose it: preparing the plan (every
 * read of a shared file happens here, on [Dispatchers.IO]) and the save run. [writer] and [guard]
 * are parameters so tests can stand in for them.
 */
class ReceiveViewModel @JvmOverloads constructor(
    application: Application,
    private val guard: suspend (String) -> Boolean = { ReceiveGuard(application).mayUse(it) },
    private val writer: ReceiveWriter = ReceiveWriter(application),
) : AndroidViewModel(application) {
    private val _stage = MutableStateFlow<ReceiveStage>(ReceiveStage.Preparing)
    val stage: StateFlow<ReceiveStage> = _stage.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _cancelled = MutableStateFlow(false)
    val cancelled: StateFlow<Boolean> = _cancelled.asStateFlow()

    private var prepared = false
    private var saveJob: Job? = null
    private val usedNames = HashSet<String>()

    /** Builds the plan once; a rotation calls it again and it does nothing. */
    fun prepare(received: List<ReceivedUri>, typeHint: String?) {
        if (prepared) return
        prepared = true
        viewModelScope.launch {
            val plan = withContext(Dispatchers.IO) { buildPlan(received, typeHint) }
            _stage.value = if (plan.isEmpty) ReceiveStage.NothingUsable(plan) else ReceiveStage.Choice(plan)
        }
    }

    private suspend fun buildPlan(received: List<ReceivedUri>, typeHint: String?): ReceivePlan {
        val resolver = getApplication<Application>().contentResolver
        val looked = received.take(MAX_LOOKED_AT)
        val candidates = looked.map { r ->
            val uri = runCatching { Uri.parse(r.uri) }.getOrNull()
            val contentUri = uri != null && r.uri.startsWith("content://", ignoreCase = true)
            val allowed = contentUri && runCatching { guard(r.uri) }.getOrDefault(false)
            if (uri == null || !allowed) {
                ReceiveCandidate(r.uri, readable = false)
            } else {
                var name: String? = null
                runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
                    }
                }
                val readable = runCatching { resolver.openInputStream(uri)?.use { true } ?: false }.getOrDefault(false)
                ReceiveCandidate(
                    uri = r.uri,
                    mimeType = runCatching { resolver.getType(uri) }.getOrNull(),
                    displayName = name,
                    typeHint = typeHint,
                    readable = readable,
                )
            }
        }
        val plan = planReceive(candidates)
        val unseen = (received.size - looked.size).coerceAtLeast(0)
        return if (unseen > 0) plan.copy(overCap = plan.overCap + unseen) else plan
    }

    fun view() {
        val plan = (_stage.value as? ReceiveStage.Choice)?.plan ?: return
        _stage.value = ReceiveStage.Viewing(plan)
    }

    fun save() {
        val current = _stage.value
        val (plan, session) = when (current) {
            is ReceiveStage.Choice -> current.plan to ReceiveSession(current.plan.items)
            is ReceiveStage.Saving -> current.plan to current.session
            else -> return
        }
        if (_saving.value) return
        _stage.value = ReceiveStage.Saving(plan, session)
        _cancelled.value = false
        _saving.value = true
        saveJob = viewModelScope.launch {
            try {
                session.run { item -> writer.save(item, usedNames) }
            } catch (e: CancellationException) {
                _cancelled.value = true
            } finally {
                _saving.value = false
            }
        }
    }

    /** Stops the run at the item in flight; its row is deleted by the writer, saved ones stay. */
    fun cancel() {
        saveJob?.cancel()
    }

    private companion object {
        /** More than this many uris are not even looked at; they are counted as left out. */
        const val MAX_LOOKED_AT = 2_000
    }
}
