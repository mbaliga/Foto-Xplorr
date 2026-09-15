package com.fotoxplorr.app.organize

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.fotoxplorr.app.media.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

data class MediaCollection(
    val id: String,
    val name: String,
    val mediaIds: Set<MediaId>,
    val createdAtMillis: Long,
)

data class LibraryState(
    val collections: List<MediaCollection> = emptyList(),
    val tagsByMediaId: Map<MediaId, Set<String>> = emptyMap(),
    val archivedIds: Set<MediaId> = emptySet(),
    /**
     * The subset of [tagsByMediaId], per photo, that `com.fotoxplorr.app.curate.AutoTagger`
     * applied rather than a person -- a SEPARATE stored set rather than a name prefix on the tag
     * text itself, so an auto tag is never a different-looking string from the identical tag a
     * person types by hand, and nothing about it leaks into any UI that just renders tag names.
     * See [LibraryStore.addAutoTags] for how a tag ends up marked here and
     * [LibraryStore.clearAutoTags] for the bulk-undo this exists to make possible.
     */
    val autoTagsByMediaId: Map<MediaId, Set<String>> = emptyMap(),
    /** One caption per photo, of either provenance -- see [machineCaptionIds] for which. */
    val captionByMediaId: Map<MediaId, String> = emptyMap(),
    /**
     * Which entries in [captionByMediaId] were written by `com.fotoxplorr.app.curate.AutoAnnotator`
     * rather than typed by a person. A photo absent from [captionByMediaId] entirely has no
     * caption of either kind -- this set only ever discriminates among photos that DO have one.
     */
    val machineCaptionIds: Set<MediaId> = emptySet(),
    /**
     * Every id ever removed from [archivedIds] by an explicit un-archive -- permanent, never
     * pruned back out by a later re-archive. This is half of `curate.ArchiveAdvisor`'s "a
     * suggestion the user rejected must not come back" memory; see
     * [isDismissedFromArchiveSuggestions] for the other half and [LibraryStore.setArchived] for
     * where this is populated.
     */
    val everUnarchivedIds: Set<MediaId> = emptySet(),
    /**
     * Every id explicitly dismissed from an archive-suggestion review queue via
     * [LibraryStore.rejectArchiveSuggestions] -- the other half of the memory described on
     * [everUnarchivedIds]. Kept as a separate set rather than merged into it because the two
     * happen through completely different user actions (un-archiving something already archived,
     * versus declining a suggestion for something that was never archived at all); a caller
     * building `curate.ArchiveAdvisor.ArchiveCandidate.previouslyDismissed` is expected to treat
     * them as one union, via [isDismissedFromArchiveSuggestions], but this file keeps their
     * histories distinct in storage.
     */
    val rejectedArchiveSuggestionIds: Set<MediaId> = emptySet(),
    /**
     * Per photo, auto tags a person removed by hand. The curation pass treats these like tags the
     * photo already carries, so it never re-applies one -- without this memory a removed auto tag
     * came straight back on the next pass, which is the same as not being able to remove it.
     * Populated by [LibraryStore.removeTag] and [LibraryStore.clearAutoTags]; withdrawn for a photo
     * by [LibraryStore.addTag] when a person types the same tag back themselves.
     */
    val rejectedAutoTagsByMediaId: Map<MediaId, Set<String>> = emptyMap(),
    /**
     * Photos whose machine-written caption a person deliberately cleared. [LibraryStore.applyMachineCaption]
     * refuses these, for the same reason as [rejectedAutoTagsByMediaId]: an empty caption slot the
     * annotator may refill on every pass is one the person can never actually empty.
     */
    val suppressedMachineCaptionIds: Set<MediaId> = emptySet(),
) {
    val allTags: List<String>
        get() = tagsByMediaId.values.flatten().distinct().sortedBy(String::lowercase)

    fun tagsFor(id: MediaId): Set<String> = tagsByMediaId[id].orEmpty()

    /** Tags on [id] that are machine-provenance -- always a subset of [tagsFor]. */
    fun autoTagsFor(id: MediaId): Set<String> = autoTagsByMediaId[id].orEmpty()

    /** Empty when [id] has no caption at all, same convention as [tagsFor]. */
    fun captionFor(id: MediaId): String = captionByMediaId[id].orEmpty()

    /** Meaningless when [captionFor] is empty -- there is no caption to have a provenance. */
    fun isMachineCaption(id: MediaId): Boolean = id in machineCaptionIds

    /** True when [id] must never be offered by `curate.ArchiveAdvisor` again. See [everUnarchivedIds]. */
    fun isDismissedFromArchiveSuggestions(id: MediaId): Boolean =
        id in everUnarchivedIds || id in rejectedArchiveSuggestionIds

    /** Auto tags a person removed from [id]; the curation pass must never propose these again. */
    fun rejectedAutoTagsFor(id: MediaId): Set<String> = rejectedAutoTagsByMediaId[id].orEmpty()

    /** True when a person cleared the annotator's caption on [id]; it must stay cleared. */
    fun isMachineCaptionSuppressed(id: MediaId): Boolean = id in suppressedMachineCaptionIds
}

/**
 * One instance per process -- see [get].
 *
 * The constructor is private because two instances over the same SharedPreferences file are a
 * data-loss bug waiting for a schedule: every mutation here is a read-modify-write of a whole
 * set key (all tag names, all archived ids, all caption ids), and two instances writing from two
 * threads -- the Activity on Main, the background job on Default -- can each read the same
 * starting set and the second apply() silently drops whatever the first added. Every instance
 * also keeps its own StateFlow, refreshed only by its own writes, so a caller reading
 * `observe().value` on one instance after the other wrote sees a stale library. A single
 * instance with every mutation `@Synchronized` removes both failure modes at once.
 *
 * The constructor is `internal` rather than private for exactly one caller: tests, which need an
 * isolated instance per test method over the fresh preferences Robolectric hands each one. App
 * code must go through [get] -- there are two call sites today, and both do.
 */
class LibraryStore internal constructor(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    fun observe(): StateFlow<LibraryState> = state.asStateFlow()

    @Synchronized
    fun createCollection(name: String): MediaCollection? {
        val cleanName = name.trim().takeIf { it.isNotEmpty() } ?: return null
        val collection = MediaCollection(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            mediaIds = emptySet(),
            createdAtMillis = System.currentTimeMillis(),
        )
        preferences.edit()
            .putStringSet(KEY_COLLECTION_IDS, collectionIds() + collection.id)
            .putString(collectionNameKey(collection.id), collection.name)
            .putStringSet(collectionMediaKey(collection.id), emptySet())
            .putLong(collectionCreatedKey(collection.id), collection.createdAtMillis)
            .apply()
        refresh()
        return collection
    }

    @Synchronized
    fun renameCollection(collectionId: String, name: String): Boolean {
        val cleanName = name.trim().takeIf { it.isNotEmpty() } ?: return false
        if (collectionId !in collectionIds()) return false
        preferences.edit().putString(collectionNameKey(collectionId), cleanName).apply()
        refresh()
        return true
    }

    @Synchronized
    fun deleteCollection(collectionId: String) {
        preferences.edit()
            .putStringSet(KEY_COLLECTION_IDS, collectionIds() - collectionId)
            .remove(collectionNameKey(collectionId))
            .remove(collectionMediaKey(collectionId))
            .remove(collectionCreatedKey(collectionId))
            .apply()
        refresh()
    }

    @Synchronized
    fun addToCollection(collectionId: String, ids: Set<MediaId>) {
        if (ids.isEmpty() || collectionId !in collectionIds()) return
        val updated = collectionMedia(collectionId) + ids
        preferences.edit()
            .putStringSet(collectionMediaKey(collectionId), encodeIds(updated))
            .apply()
        refresh()
    }

    @Synchronized
    fun removeFromCollection(collectionId: String, ids: Set<MediaId>) {
        if (ids.isEmpty()) return
        preferences.edit()
            .putStringSet(collectionMediaKey(collectionId), encodeIds(collectionMedia(collectionId) - ids))
            .apply()
        refresh()
    }

    @Synchronized
    fun setArchived(ids: Set<MediaId>, archived: Boolean) {
        if (ids.isEmpty()) return
        val current = decodeIds(preferences.getStringSet(KEY_ARCHIVED_IDS, emptySet()))
        val updated = if (archived) current + ids else current - ids
        val editor = preferences.edit().putStringSet(KEY_ARCHIVED_IDS, encodeIds(updated))
        if (!archived) {
            // Un-archiving is the user overriding a decision -- their own original archive, or an
            // accepted curate.ArchiveAdvisor suggestion, makes no difference -- and that override
            // is remembered permanently, not just reflected in the current archived/not split.
            // Restricted to ids that were ACTUALLY archived a moment ago: calling this with
            // archived=false on something that was never archived is a no-op today (see `updated`
            // above) and must stay one here too, not a back door to blacklist a photo from ever
            // being suggested without the user ever having seen a suggestion for it.
            val actuallyUnarchived = ids.intersect(current)
            if (actuallyUnarchived.isNotEmpty()) {
                editor.putStringSet(
                    KEY_EVER_UNARCHIVED_IDS,
                    encodeIds(everUnarchivedIds() + actuallyUnarchived),
                )
            }
        }
        editor.apply()
        refresh()
    }

    /**
     * Permanently dismisses [ids] from `curate.ArchiveAdvisor`'s review queue -- the "Reject"
     * action in `curate.ArchiveSuggestionsReview`. Deliberately does not touch [LibraryState.archivedIds]
     * in either direction: rejecting a suggestion means "leave this alone", not an archive-state
     * change, which is exactly what distinguishes this from [setArchived]. See
     * [LibraryState.everUnarchivedIds] for the other half of this memory.
     */
    @Synchronized
    fun rejectArchiveSuggestions(ids: Set<MediaId>) {
        if (ids.isEmpty()) return
        preferences.edit()
            .putStringSet(
                KEY_REJECTED_ARCHIVE_SUGGESTION_IDS,
                encodeIds(rejectedArchiveSuggestionIds() + ids),
            )
            .apply()
        refresh()
    }

    @Synchronized
    fun addTag(ids: Set<MediaId>, tag: String) {
        val cleanTag = tag.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() } ?: return
        if (ids.isEmpty()) return
        val tagNames = tagNames() + cleanTag
        val updatedIds = tagMedia(cleanTag) + ids
        val editor = preferences.edit()
            .putStringSet(KEY_TAG_NAMES, tagNames)
            .putStringSet(tagMediaKey(cleanTag), encodeIds(updatedIds))
        // A human just asserted this tag on these photos, explicitly, right now -- so from this
        // point on it is a user tag even if AutoTagger had previously applied identical text.
        // [addAutoTags]'s own guard refuses to convert an EXISTING user tag into an auto one, but
        // that guard is worthless if a tag can still read as auto after a person typed it
        // themselves, which is exactly what would let [clearAutoTags] delete something they wrote.
        val remainingAuto = autoTagMedia(cleanTag) - ids
        if (remainingAuto.isEmpty()) {
            editor.remove(autoTagMediaKey(cleanTag))
        } else {
            editor.putStringSet(autoTagMediaKey(cleanTag), encodeIds(remainingAuto))
        }
        // Typing the tag back by hand withdraws any earlier rejection of it for these photos.
        forgetRejectedAutoTag(editor, cleanTag, ids)
        editor.apply()
        refresh()
    }

    @Synchronized
    fun removeTag(ids: Set<MediaId>, tag: String) {
        if (ids.isEmpty()) return
        val remaining = tagMedia(tag) - ids
        val editor = preferences.edit()
        if (remaining.isEmpty()) {
            editor
                .putStringSet(KEY_TAG_NAMES, tagNames() - tag)
                .remove(tagMediaKey(tag))
        } else {
            editor.putStringSet(tagMediaKey(tag), encodeIds(remaining))
        }
        // The tag is gone for these ids now, possibly for everyone -- either way it is no longer
        // machine-provenance for ids that no longer carry it at all. Left uncleaned, a user who
        // removes an auto tag and later retypes the SAME text by hand would find their manual tag
        // still counted in a bulk clearAutoTags sweep, silently deleting something they wrote.
        val wasAuto = autoTagMedia(tag).intersect(ids)
        val remainingAuto = autoTagMedia(tag) - ids
        if (remainingAuto.isEmpty()) {
            editor.remove(autoTagMediaKey(tag))
        } else {
            editor.putStringSet(autoTagMediaKey(tag), encodeIds(remainingAuto))
        }
        // A person removing a tag the app applied is the one signal the app gets that it guessed
        // wrong, so it is remembered: the next curation pass must not put the same tag straight
        // back. Only the ids where the tag WAS machine-applied are recorded -- removing a tag you
        // typed yourself says nothing about the auto-tagger.
        if (wasAuto.isNotEmpty()) rememberRejectedAutoTag(editor, tag, wasAuto)
        editor.apply()
        refresh()
    }

    /**
     * Adds [tags] to [id] exactly as [addTag] would, except each one is ALSO marked
     * machine-provenance so it can be told apart from a user's own tags and cleared in bulk by
     * [clearAutoTags]. `com.fotoxplorr.app.curate.AutoTagger.tagsFor` is expected to have already
     * excluded anything duplicating a tag [id] already carries (its own `existingTags`
     * parameter) -- this is the second, storage-side guard for that same rule, not the first: a
     * tag already present for [id], from ANY source, is left exactly as it was and never newly
     * marked auto here. Skipping this guard would let a caller that forgot to pass
     * `existingTags` (or a race between two calls) silently convert a tag the user typed
     * themselves into one flagged machine-written and eligible for [clearAutoTags] to delete out
     * from under them -- the precise failure mode "never emit a tag that duplicates one the user
     * already applied" was written to prevent.
     */
    @Synchronized
    fun addAutoTags(id: MediaId, tags: Set<String>) {
        if (tags.isEmpty()) return
        val cleanTags = tags.mapNotNullTo(linkedSetOf()) { raw ->
            raw.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }
        }
        if (cleanTags.isEmpty()) return
        var tagNamesUpdated = tagNames()
        val editor = preferences.edit()
        cleanTags.forEach { tag ->
            val currentMedia = tagMedia(tag)
            if (id in currentMedia) return@forEach
            // The person already said no to this one on this photo -- see rejectedAutoTagsByMediaId.
            if (id in rejectedAutoTagMedia(tag)) return@forEach
            tagNamesUpdated = tagNamesUpdated + tag
            editor.putStringSet(tagMediaKey(tag), encodeIds(currentMedia + id))
            editor.putStringSet(autoTagMediaKey(tag), encodeIds(autoTagMedia(tag) + id))
        }
        editor.putStringSet(KEY_TAG_NAMES, tagNamesUpdated)
        editor.apply()
        refresh()
    }

    /**
     * Undoes auto-tagging at once -- "reviewed and bulk-undone", from this feature's own brief.
     * Restricted to [ids] when given; every auto-tagged photo in the library when `null`, for a
     * single "undo all auto-tagging" action. Removes the TAG ITSELF for the affected (photo,
     * tag) pairs, not merely the provenance marker: an auto tag with its marker cleared but the
     * text left behind would look, to every other part of this app, exactly like a tag a person
     * typed -- that is not an undo, it is a disguise.
     */
    @Synchronized
    fun clearAutoTags(ids: Set<MediaId>? = null) {
        val editor = preferences.edit()
        var tagNamesUpdated = tagNames()
        var changed = false
        val rejectedNames = rejectedAutoTagNames().toMutableSet()
        tagNamesUpdated.toList().forEach { tag ->
            val autoIds = autoTagMedia(tag)
            val toRemove = if (ids == null) autoIds else autoIds.intersect(ids)
            if (toRemove.isEmpty()) return@forEach
            changed = true
            // Bulk-undoing is the strongest "no" there is; without remembering it the next pass
            // would re-apply every tag just cleared and the undo would have done nothing. The
            // per-tag set is written here; the NAMES index is written once, below, because each
            // putStringSet of the same key inside one editor replaces the last -- written per
            // tag from the not-yet-applied preferences, only the final tag's name survived.
            editor.putStringSet(rejectedAutoTagMediaKey(tag), encodeIds(rejectedAutoTagMedia(tag) + toRemove))
            rejectedNames += tag
            val remainingTagged = tagMedia(tag) - toRemove
            if (remainingTagged.isEmpty()) {
                tagNamesUpdated = tagNamesUpdated - tag
                editor.remove(tagMediaKey(tag))
            } else {
                editor.putStringSet(tagMediaKey(tag), encodeIds(remainingTagged))
            }
            val remainingAuto = autoIds - toRemove
            if (remainingAuto.isEmpty()) {
                editor.remove(autoTagMediaKey(tag))
            } else {
                editor.putStringSet(autoTagMediaKey(tag), encodeIds(remainingAuto))
            }
        }
        if (!changed) return
        editor.putStringSet(KEY_TAG_NAMES, tagNamesUpdated)
        editor.putStringSet(KEY_REJECTED_AUTO_TAG_NAMES, rejectedNames)
        editor.apply()
        refresh()
    }

    /**
     * A person explicitly writes, edits or clears this photo's caption. Always wins: this is the
     * one write path in this file allowed to replace ANY existing caption, human or machine,
     * because it IS the human writing it right now. Clearing (blank [caption]) removes the
     * stored text entirely rather than leaving an empty string behind, the same convention
     * [removeTag] uses for a tag with zero photos left.
     */
    @Synchronized
    fun setCaption(id: MediaId, caption: String) {
        val clean = caption.trim()
        val editor = preferences.edit()
        if (clean.isEmpty()) {
            editor
                .remove(captionKey(id))
                .putStringSet(KEY_CAPTION_MEDIA_IDS, encodeIds(captionMediaIds() - id))
            // Deleting the annotator's sentence is a decision about it, and one that has to
            // stick: applyMachineCaption treats an empty slot as fillable, so without this the
            // same sentence came back on the next pass and the caption could never be emptied.
            if (id in machineCaptionIds()) {
                editor.putStringSet(
                    KEY_SUPPRESSED_MACHINE_CAPTION_IDS,
                    encodeIds(suppressedMachineCaptionIds() + id),
                )
            }
        } else {
            editor
                .putString(captionKey(id), clean)
                .putStringSet(KEY_CAPTION_MEDIA_IDS, encodeIds(captionMediaIds() + id))
        }
        // A human just spoke for this photo -- whatever AutoAnnotator wrote before, if anything,
        // is no longer machine-written in any sense worth tracking.
        editor.putStringSet(KEY_MACHINE_CAPTION_IDS, encodeIds(machineCaptionIds() - id))
        editor.apply()
        refresh()
    }

    /**
     * `com.fotoxplorr.app.curate.AutoAnnotator`'s write path. Re-checks, right here, whether
     * writing is actually safe -- the same belt-and-suspenders shape [addAutoTags] uses for tags
     * and `com.fotoxplorr.app.moments.VideoMomentStore.replaceAuto` uses for video moments: the
     * pure function upstream ([com.fotoxplorr.app.curate.AutoAnnotator.apply]) is trusted to have
     * decided this once already, and it is verified again at the point of writing, because a
     * storage layer that blindly does what it is told is one bad call site away from the exact
     * mistake this feature exists to make impossible -- see [AutoAnnotator]'s own KDoc for why
     * that mistake is unrecoverable and therefore not one worth a single point of failure.
     *
     * @return whether this actually wrote a caption, so a caller sweeping the whole library can
     *   count how many photos it annotated without keeping its own separate tally.
     */
    @Synchronized
    fun applyMachineCaption(id: MediaId, caption: String): Boolean {
        val clean = caption.trim()
        if (clean.isEmpty()) return false
        if (id in suppressedMachineCaptionIds()) return false
        val current = preferences.getString(captionKey(id), null).orEmpty()
        val currentIsMachine = id in machineCaptionIds()
        if (current.isNotEmpty() && !currentIsMachine) return false
        preferences.edit()
            .putString(captionKey(id), clean)
            .putStringSet(KEY_CAPTION_MEDIA_IDS, encodeIds(captionMediaIds() + id))
            .putStringSet(KEY_MACHINE_CAPTION_IDS, encodeIds(machineCaptionIds() + id))
            .apply()
        refresh()
        return true
    }

    /**
     * Clears every currently machine-written caption at once -- restricted to [ids] when given,
     * every machine-captioned photo in the library when `null`. Only ever removes captions still
     * marked machine AT THE TIME THIS RUNS: one a person has since edited via [setCaption] has
     * already lost its marker there, so it is correctly left alone here with no extra check
     * needed -- there is no way, by construction, for a caption to be both person-edited and
     * still flagged machine.
     */
    @Synchronized
    fun clearMachineCaptions(ids: Set<MediaId>? = null) {
        val current = machineCaptionIds()
        val toClear = if (ids == null) current else current.intersect(ids)
        if (toClear.isEmpty()) return
        val editor = preferences.edit()
        toClear.forEach { editor.remove(captionKey(it)) }
        editor
            .putStringSet(KEY_CAPTION_MEDIA_IDS, encodeIds(captionMediaIds() - toClear))
            .putStringSet(KEY_MACHINE_CAPTION_IDS, encodeIds(current - toClear))
            // Same reasoning as the clearing branch of setCaption: an undo that the next pass
            // reverses is not an undo.
            .putStringSet(KEY_SUPPRESSED_MACHINE_CAPTION_IDS, encodeIds(suppressedMachineCaptionIds() + toClear))
            .apply()
        refresh()
    }

    /**
     * Everything one curation pass wants to write, committed as ONE edit.
     *
     * The per-photo methods above each do a read-modify-write, an apply() and a full [refresh] --
     * fine for a person tapping one chip, ruinous for a pass over twenty thousand photos: that is
     * twenty thousand StateFlow emissions, each of which recomposes every screen observing the
     * library. This applies exactly the same guards as [addAutoTags] and [applyMachineCaption],
     * per item, and emits once.
     *
     * @return how many photos actually changed. Zero is the normal answer for a library that has
     *   already been through this, which is why a proposal identical to what is stored is not
     *   counted -- a pass that reports "changed 20,000 photos" every night while changing nothing
     *   is a status line nobody can trust.
     */
    @Synchronized
    fun applyCuration(autoTags: Map<MediaId, Set<String>>, captions: Map<MediaId, String>): Int {
        val editor = preferences.edit()
        val changed = HashSet<MediaId>()

        var tagNamesUpdated = tagNames()
        // Working copies per tag, so two photos gaining the same tag in one pass compose instead
        // of the second putStringSet overwriting the first's within the same editor.
        val tagMediaWork = HashMap<String, MutableSet<MediaId>>()
        val autoMediaWork = HashMap<String, MutableSet<MediaId>>()
        autoTags.forEach { (id, tags) ->
            tags.forEach tag@{ raw ->
                val tag = raw.trim().replace(Regex("\\s+"), " ")
                if (tag.isEmpty()) return@tag
                val media = tagMediaWork.getOrPut(tag) { tagMedia(tag).toMutableSet() }
                if (id in media) return@tag
                if (id in rejectedAutoTagMedia(tag)) return@tag
                media += id
                autoMediaWork.getOrPut(tag) { autoTagMedia(tag).toMutableSet() } += id
                tagNamesUpdated = tagNamesUpdated + tag
                changed += id
            }
        }
        tagMediaWork.forEach { (tag, media) -> editor.putStringSet(tagMediaKey(tag), encodeIds(media)) }
        autoMediaWork.forEach { (tag, media) -> editor.putStringSet(autoTagMediaKey(tag), encodeIds(media)) }
        editor.putStringSet(KEY_TAG_NAMES, tagNamesUpdated)

        val captionIds = captionMediaIds().toMutableSet()
        val machineIds = machineCaptionIds().toMutableSet()
        val suppressed = suppressedMachineCaptionIds()
        captions.forEach { (id, caption) ->
            val clean = caption.trim()
            if (clean.isEmpty() || id in suppressed) return@forEach
            val current = preferences.getString(captionKey(id), null).orEmpty()
            if (current.isNotEmpty() && id !in machineIds) return@forEach
            if (current == clean) return@forEach
            editor.putString(captionKey(id), clean)
            captionIds += id
            machineIds += id
            changed += id
        }
        editor
            .putStringSet(KEY_CAPTION_MEDIA_IDS, encodeIds(captionIds))
            .putStringSet(KEY_MACHINE_CAPTION_IDS, encodeIds(machineIds))

        if (changed.isEmpty()) return 0
        editor.apply()
        refresh()
        return changed.size
    }

    @Synchronized
    fun removeMissingMedia(availableIds: Set<MediaId>) {
        val snapshot = state.value
        val editor = preferences.edit()
        snapshot.collections.forEach { collection ->
            editor.putStringSet(
                collectionMediaKey(collection.id),
                encodeIds(collection.mediaIds.intersect(availableIds)),
            )
        }
        snapshot.allTags.forEach { tag ->
            val remaining = tagMedia(tag).intersect(availableIds)
            if (remaining.isEmpty()) {
                editor.remove(tagMediaKey(tag))
            } else {
                editor.putStringSet(tagMediaKey(tag), encodeIds(remaining))
            }
            // Same cleanup, same reasoning, for the auto-provenance marker: a photo that no
            // longer exists cannot usefully stay flagged as "this tag was machine-applied".
            val remainingAuto = autoTagMedia(tag).intersect(availableIds)
            if (remainingAuto.isEmpty()) {
                editor.remove(autoTagMediaKey(tag))
            } else {
                editor.putStringSet(autoTagMediaKey(tag), encodeIds(remainingAuto))
            }
        }
        val remainingTags = snapshot.allTags.filterTo(linkedSetOf()) { tag ->
            tagMedia(tag).any { it in availableIds }
        }
        // Rejection memory is indexed by its own name list, because a rejected tag may no longer
        // be in KEY_TAG_NAMES at all (it was removed from the last photo that carried it).
        val remainingRejectedNames = linkedSetOf<String>()
        rejectedAutoTagNames().forEach { tag ->
            val remaining = rejectedAutoTagMedia(tag).intersect(availableIds)
            if (remaining.isEmpty()) {
                editor.remove(rejectedAutoTagMediaKey(tag))
            } else {
                remainingRejectedNames += tag
                editor.putStringSet(rejectedAutoTagMediaKey(tag), encodeIds(remaining))
            }
        }
        editor.putStringSet(KEY_REJECTED_AUTO_TAG_NAMES, remainingRejectedNames)
        // A caption for a photo that no longer exists is dead weight, same as everything above --
        // and unlike a tag's per-tag key, a caption's key is per-id, so it is removed directly
        // rather than rewritten with a shrunk set.
        (snapshot.captionByMediaId.keys - availableIds).forEach { editor.remove(captionKey(it)) }
        editor
            .putStringSet(KEY_TAG_NAMES, remainingTags)
            .putStringSet(KEY_ARCHIVED_IDS, encodeIds(snapshot.archivedIds.intersect(availableIds)))
            .putStringSet(KEY_CAPTION_MEDIA_IDS, encodeIds(snapshot.captionByMediaId.keys.intersect(availableIds)))
            .putStringSet(KEY_MACHINE_CAPTION_IDS, encodeIds(snapshot.machineCaptionIds.intersect(availableIds)))
            .putStringSet(KEY_EVER_UNARCHIVED_IDS, encodeIds(snapshot.everUnarchivedIds.intersect(availableIds)))
            .putStringSet(
                KEY_REJECTED_ARCHIVE_SUGGESTION_IDS,
                encodeIds(snapshot.rejectedArchiveSuggestionIds.intersect(availableIds)),
            )
            .putStringSet(
                KEY_SUPPRESSED_MACHINE_CAPTION_IDS,
                encodeIds(suppressedMachineCaptionIds().intersect(availableIds)),
            )
            .apply()
        refresh()
    }

    fun exportJson(): JSONObject = JSONObject().apply {
        put("schema", BACKUP_SCHEMA)
        put("collections", JSONArray().apply {
            state.value.collections.forEach { collection ->
                put(JSONObject().apply {
                    put("id", collection.id)
                    put("name", collection.name)
                    put("createdAtMillis", collection.createdAtMillis)
                    put("mediaIds", JSONArray(collection.mediaIds.map { it.value }))
                })
            }
        })
        put("tags", JSONObject().apply {
            state.value.allTags.forEach { tag ->
                put(tag, JSONArray(tagMedia(tag).map { it.value }))
            }
        })
        put("archivedIds", JSONArray(state.value.archivedIds.map { it.value }))
        // Everything below is new for this feature. None of it bumps BACKUP_SCHEMA: an OLDER
        // build importing THIS export already ignores keys it does not recognise (see the
        // existing "collections"/"tags" handling above, which never rejected an export for
        // having an EXTRA key), and importJson below defaults every one of these to empty when
        // reading an OLDER export that lacks them. Bumping the schema number would instead break
        // every backup file already sitting on a user's device from before this change, for
        // fields that degrade safely on their own.
        put("autoTags", JSONObject().apply {
            // Only a tag with at least one auto-provenance photo gets a key, so a library with no
            // auto-tagging at all exports identically to how it did before this field existed.
            state.value.allTags.forEach { tag ->
                val autoIds = autoTagMedia(tag)
                if (autoIds.isNotEmpty()) put(tag, JSONArray(autoIds.map { it.value }))
            }
        })
        put("captions", JSONObject().apply {
            state.value.captionByMediaId.forEach { (id, caption) -> put(id.value.toString(), caption) }
        })
        put("machineCaptionIds", JSONArray(state.value.machineCaptionIds.map { it.value }))
        // Both halves of curate.ArchiveAdvisor's "do not ask again" memory travel with the
        // backup deliberately -- restoring a backup and having every previously-rejected
        // suggestion come back the next time recognition runs would be a real regression of the
        // one promise that memory exists to keep.
        put("everUnarchivedIds", JSONArray(state.value.everUnarchivedIds.map { it.value }))
        put(
            "rejectedArchiveSuggestionIds",
            JSONArray(state.value.rejectedArchiveSuggestionIds.map { it.value }),
        )
        // The two "the person said no" memories for curation travel too, for the same reason the
        // archive ones do: a restore that forgets them would re-apply every tag and caption the
        // person had removed, the first time recognition ran again.
        put("rejectedAutoTags", JSONObject().apply {
            rejectedAutoTagNames().forEach { tag ->
                val ids = rejectedAutoTagMedia(tag)
                if (ids.isNotEmpty()) put(tag, JSONArray(ids.map { it.value }))
            }
        })
        put("suppressedMachineCaptionIds", JSONArray(state.value.suppressedMachineCaptionIds.map { it.value }))
    }

    @Synchronized
    fun importJson(root: JSONObject): Result<Unit> = runCatching {
        require(root.optInt("schema", 0) == BACKUP_SCHEMA) { "Unsupported metadata backup" }
        val editor = preferences.edit().clear()
        val collectionIds = linkedSetOf<String>()
        root.optJSONArray("collections")?.let { collections ->
            for (index in 0 until collections.length()) {
                val item = collections.getJSONObject(index)
                val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
                val name = item.getString("name").trim()
                if (name.isEmpty()) continue
                collectionIds += id
                editor
                    .putString(collectionNameKey(id), name)
                    .putLong(collectionCreatedKey(id), item.optLong("createdAtMillis", System.currentTimeMillis()))
                    .putStringSet(collectionMediaKey(id), encodeIds(item.optJSONArray("mediaIds").toMediaIds()))
            }
        }
        editor.putStringSet(KEY_COLLECTION_IDS, collectionIds)

        val tagNames = linkedSetOf<String>()
        root.optJSONObject("tags")?.let { tags ->
            tags.keys().forEach { tag ->
                val cleanTag = tag.trim()
                if (cleanTag.isNotEmpty()) {
                    tagNames += cleanTag
                    editor.putStringSet(tagMediaKey(cleanTag), encodeIds(tags.optJSONArray(tag).toMediaIds()))
                }
            }
        }
        editor
            .putStringSet(KEY_TAG_NAMES, tagNames)
            .putStringSet(KEY_ARCHIVED_IDS, encodeIds(root.optJSONArray("archivedIds").toMediaIds()))

        // Only a tag name that also came through in "tags" above can hold an auto marker here --
        // a marker for a name "tags" never mentioned would reference a tag with no photos under
        // it, which addAutoTags/addTag would never themselves produce.
        root.optJSONObject("autoTags")?.let { autoTags ->
            autoTags.keys().forEach { tag ->
                val cleanTag = tag.trim()
                if (cleanTag.isNotEmpty() && cleanTag in tagNames) {
                    editor.putStringSet(
                        autoTagMediaKey(cleanTag),
                        encodeIds(autoTags.optJSONArray(tag).toMediaIds()),
                    )
                }
            }
        }

        val captionIds = linkedSetOf<MediaId>()
        root.optJSONObject("captions")?.let { captions ->
            captions.keys().forEach { key ->
                val id = key.toLongOrNull()?.takeIf { it >= 0L }?.let(::MediaId)
                val text = captions.optString(key).trim()
                if (id != null && text.isNotEmpty()) {
                    captionIds += id
                    editor.putString(captionKey(id), text)
                }
            }
        }
        editor.putStringSet(KEY_CAPTION_MEDIA_IDS, encodeIds(captionIds))
        // Intersected against captionIds: a backup claiming a photo's caption is machine-written
        // when this same import has no caption text for that photo at all is internally
        // inconsistent, and the caption -- not the marker -- is the data that actually matters.
        editor.putStringSet(
            KEY_MACHINE_CAPTION_IDS,
            encodeIds(root.optJSONArray("machineCaptionIds").toMediaIds().intersect(captionIds)),
        )
        editor.putStringSet(
            KEY_EVER_UNARCHIVED_IDS,
            encodeIds(root.optJSONArray("everUnarchivedIds").toMediaIds()),
        )
        editor.putStringSet(
            KEY_REJECTED_ARCHIVE_SUGGESTION_IDS,
            encodeIds(root.optJSONArray("rejectedArchiveSuggestionIds").toMediaIds()),
        )
        val rejectedNames = linkedSetOf<String>()
        root.optJSONObject("rejectedAutoTags")?.let { rejected ->
            rejected.keys().forEach { tag ->
                val cleanTag = tag.trim()
                val ids = rejected.optJSONArray(tag).toMediaIds()
                if (cleanTag.isNotEmpty() && ids.isNotEmpty()) {
                    rejectedNames += cleanTag
                    editor.putStringSet(rejectedAutoTagMediaKey(cleanTag), encodeIds(ids))
                }
            }
        }
        editor.putStringSet(KEY_REJECTED_AUTO_TAG_NAMES, rejectedNames)
        editor.putStringSet(
            KEY_SUPPRESSED_MACHINE_CAPTION_IDS,
            encodeIds(root.optJSONArray("suppressedMachineCaptionIds").toMediaIds()),
        )
        editor.apply()
        refresh()
    }

    private fun refresh() {
        state.value = load()
    }

    private fun load(): LibraryState {
        val tagsById = linkedMapOf<MediaId, MutableSet<String>>()
        val autoTagsById = linkedMapOf<MediaId, MutableSet<String>>()
        tagNames().forEach { tag ->
            tagMedia(tag).forEach { id -> tagsById.getOrPut(id, ::linkedSetOf).add(tag) }
            autoTagMedia(tag).forEach { id -> autoTagsById.getOrPut(id, ::linkedSetOf).add(tag) }
        }
        val rejectedById = linkedMapOf<MediaId, MutableSet<String>>()
        rejectedAutoTagNames().forEach { tag ->
            rejectedAutoTagMedia(tag).forEach { id -> rejectedById.getOrPut(id, ::linkedSetOf).add(tag) }
        }
        val captionText = captionMediaIds().associateWith { id ->
            preferences.getString(captionKey(id), null).orEmpty()
        }.filterValues { it.isNotEmpty() } // defensive: an index entry with no text behind it is not a caption
        return LibraryState(
            collections = collectionIds().mapNotNull { id ->
                val name = preferences.getString(collectionNameKey(id), null) ?: return@mapNotNull null
                MediaCollection(
                    id = id,
                    name = name,
                    mediaIds = collectionMedia(id),
                    createdAtMillis = preferences.getLong(collectionCreatedKey(id), 0L),
                )
            }.sortedWith(compareBy<MediaCollection> { it.name.lowercase() }.thenBy { it.createdAtMillis }),
            tagsByMediaId = tagsById.mapValues { it.value.toSet() },
            archivedIds = decodeIds(preferences.getStringSet(KEY_ARCHIVED_IDS, emptySet())),
            autoTagsByMediaId = autoTagsById.mapValues { it.value.toSet() },
            captionByMediaId = captionText,
            machineCaptionIds = machineCaptionIds(),
            everUnarchivedIds = everUnarchivedIds(),
            rejectedArchiveSuggestionIds = rejectedArchiveSuggestionIds(),
            rejectedAutoTagsByMediaId = rejectedById.mapValues { it.value.toSet() },
            suppressedMachineCaptionIds = suppressedMachineCaptionIds(),
        )
    }

    private fun collectionIds(): Set<String> =
        preferences.getStringSet(KEY_COLLECTION_IDS, emptySet()).orEmpty().toSet()

    private fun collectionMedia(collectionId: String): Set<MediaId> =
        decodeIds(preferences.getStringSet(collectionMediaKey(collectionId), emptySet()))

    private fun tagNames(): Set<String> =
        preferences.getStringSet(KEY_TAG_NAMES, emptySet()).orEmpty().toSet()

    private fun tagMedia(tag: String): Set<MediaId> =
        decodeIds(preferences.getStringSet(tagMediaKey(tag), emptySet()))

    private fun tagMediaKey(tag: String): String {
        val encoded = Base64.encodeToString(
            tag.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )
        return "$TAG_MEDIA_PREFIX$encoded"
    }

    /** Which ids currently carry [tag] as machine-provenance -- always a subset of [tagMedia]. */
    private fun autoTagMedia(tag: String): Set<MediaId> =
        decodeIds(preferences.getStringSet(autoTagMediaKey(tag), emptySet()))

    /** Same Base64-over-UTF-8 scheme as [tagMediaKey], under its own prefix so the two never collide. */
    private fun autoTagMediaKey(tag: String): String {
        val encoded = Base64.encodeToString(
            tag.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )
        return "$AUTO_TAG_MEDIA_PREFIX$encoded"
    }

    /** Index of every id that currently has a caption of either provenance, for [load] to enumerate. */
    private fun captionMediaIds(): Set<MediaId> =
        decodeIds(preferences.getStringSet(KEY_CAPTION_MEDIA_IDS, emptySet()))

    /** One caption is one string preference, unlike a tag -- there is exactly one per photo, not a set. */
    private fun captionKey(mediaId: MediaId): String = "$CAPTION_PREFIX${mediaId.value}"

    private fun machineCaptionIds(): Set<MediaId> =
        decodeIds(preferences.getStringSet(KEY_MACHINE_CAPTION_IDS, emptySet()))

    private fun everUnarchivedIds(): Set<MediaId> =
        decodeIds(preferences.getStringSet(KEY_EVER_UNARCHIVED_IDS, emptySet()))

    private fun rejectedArchiveSuggestionIds(): Set<MediaId> =
        decodeIds(preferences.getStringSet(KEY_REJECTED_ARCHIVE_SUGGESTION_IDS, emptySet()))

    private fun suppressedMachineCaptionIds(): Set<MediaId> =
        decodeIds(preferences.getStringSet(KEY_SUPPRESSED_MACHINE_CAPTION_IDS, emptySet()))

    /** Names with at least one rejection recorded -- its own index, since such a tag may no longer be in [tagNames]. */
    private fun rejectedAutoTagNames(): Set<String> =
        preferences.getStringSet(KEY_REJECTED_AUTO_TAG_NAMES, emptySet()).orEmpty().toSet()

    private fun rejectedAutoTagMedia(tag: String): Set<MediaId> =
        decodeIds(preferences.getStringSet(rejectedAutoTagMediaKey(tag), emptySet()))

    /** Same Base64-over-UTF-8 scheme as [tagMediaKey], under a third prefix. */
    private fun rejectedAutoTagMediaKey(tag: String): String {
        val encoded = Base64.encodeToString(
            tag.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )
        return "$REJECTED_AUTO_TAG_MEDIA_PREFIX$encoded"
    }

    private fun rememberRejectedAutoTag(editor: SharedPreferences.Editor, tag: String, ids: Set<MediaId>) {
        editor.putStringSet(KEY_REJECTED_AUTO_TAG_NAMES, rejectedAutoTagNames() + tag)
        editor.putStringSet(rejectedAutoTagMediaKey(tag), encodeIds(rejectedAutoTagMedia(tag) + ids))
    }

    private fun forgetRejectedAutoTag(editor: SharedPreferences.Editor, tag: String, ids: Set<MediaId>) {
        val current = rejectedAutoTagMedia(tag)
        if (current.isEmpty()) return
        val remaining = current - ids
        if (remaining.isEmpty()) {
            editor.remove(rejectedAutoTagMediaKey(tag))
            editor.putStringSet(KEY_REJECTED_AUTO_TAG_NAMES, rejectedAutoTagNames() - tag)
        } else {
            editor.putStringSet(rejectedAutoTagMediaKey(tag), encodeIds(remaining))
        }
    }

    companion object {
        @Volatile
        private var instance: LibraryStore? = null

        /** The process's one [LibraryStore]. See the class KDoc for why there must be only one. */
        fun get(context: Context): LibraryStore =
            instance ?: synchronized(this) {
                instance ?: LibraryStore(context.applicationContext).also { instance = it }
            }

        private const val PREFERENCES_NAME = "foto_xplorr_library"
        private const val BACKUP_SCHEMA = 1
        private const val KEY_COLLECTION_IDS = "collection_ids"
        private const val KEY_TAG_NAMES = "tag_names"
        private const val KEY_ARCHIVED_IDS = "archived_ids"
        private const val TAG_MEDIA_PREFIX = "tag_media:"
        private const val AUTO_TAG_MEDIA_PREFIX = "auto_tag_media:"
        private const val REJECTED_AUTO_TAG_MEDIA_PREFIX = "rejected_auto_tag_media:"
        private const val KEY_REJECTED_AUTO_TAG_NAMES = "rejected_auto_tag_names"
        private const val KEY_CAPTION_MEDIA_IDS = "caption_media_ids"
        private const val CAPTION_PREFIX = "caption:"
        private const val KEY_MACHINE_CAPTION_IDS = "machine_caption_ids"
        private const val KEY_SUPPRESSED_MACHINE_CAPTION_IDS = "suppressed_machine_caption_ids"
        private const val KEY_EVER_UNARCHIVED_IDS = "ever_unarchived_ids"
        private const val KEY_REJECTED_ARCHIVE_SUGGESTION_IDS = "rejected_archive_suggestion_ids"
        private fun collectionNameKey(id: String) = "collection_name:$id"
        private fun collectionMediaKey(id: String) = "collection_media:$id"
        private fun collectionCreatedKey(id: String) = "collection_created:$id"
    }
}

internal fun encodeIds(ids: Set<MediaId>): Set<String> = ids
    .asSequence()
    .map { it.value }
    .filter { it >= 0L }
    .sorted()
    .mapTo(linkedSetOf(), Long::toString)

internal fun decodeIds(values: Set<String>?): Set<MediaId> = values
    .orEmpty()
    .mapNotNullTo(linkedSetOf()) { raw -> raw.toLongOrNull()?.takeIf { it >= 0L }?.let(::MediaId) }

private fun JSONArray?.toMediaIds(): Set<MediaId> {
    if (this == null) return emptySet()
    return buildSet {
        for (index in 0 until length()) {
            val value = optLong(index, -1L)
            if (value >= 0L) add(MediaId(value))
        }
    }
}
