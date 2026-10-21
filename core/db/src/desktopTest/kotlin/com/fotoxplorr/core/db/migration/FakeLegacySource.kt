package com.fotoxplorr.core.db.migration

/** A small, hand-built [LegacySource] for [MigrationToV2]'s own unit tests -- not the 100k
 *  synthetic fixture ADR-011 asks for separately, just enough to exercise every step's logic
 *  directly and cheaply. */
class FakeLegacySource(
    private val media: MutableList<LegacyMediaRow> = mutableListOf(),
    private val volumesByMediaId: MutableMap<Long, String> = mutableMapOf(),
    private val volumes: MutableList<String> = mutableListOf("external_primary"),
    var geo: List<LegacyGeoRow> = emptyList(),
    var recognition: List<LegacyRecognitionRow> = emptyList(),
    var faces: List<LegacyFaceRow> = emptyList(),
    var textBlocks: List<LegacyTextBlockRow> = emptyList(),
    var recognitionFailures: List<LegacyFailureRow> = emptyList(),
    var traits: List<LegacyTraitRow> = emptyList(),
    var embeddings: List<LegacyEmbeddingRow> = emptyList(),
    var embeddingFailures: List<LegacyFailureRow> = emptyList(),
    var videoMoments: List<LegacyVideoMomentRow> = emptyList(),
    var videoScanned: Set<Long> = emptySet(),
    var videoMomentFeedback: List<LegacyMomentFeedbackRow> = emptyList(),
    var favorites: Set<Long> = emptySet(),
    var sensitive: Set<Long> = emptySet(),
    var library: LegacyLibraryData = LegacyLibraryData(
        collections = emptyList(), tagMembers = emptyMap(), autoTagMembers = emptyMap(),
        rejectedAutoTagMembers = emptyMap(), archivedIds = emptySet(), everUnarchivedIds = emptySet(),
        rejectedArchiveSuggestionIds = emptySet(), captions = emptyMap(), machineCaptionIds = emptySet(),
        suppressedMachineCaptionIds = emptySet(),
    ),
    var locks: List<LegacyFolderLock> = emptyList(),
    private var clockMs: Long = 1_700_000_000_000L,
) : LegacySource {

    fun addMedia(row: LegacyMediaRow, volume: String? = "external_primary") {
        media += row
        if (volume != null) volumesByMediaId[row.mediaId] = volume
    }

    fun advanceClock(deltaMs: Long) {
        clockMs += deltaMs
    }

    override suspend fun mediaRowsAfter(afterId: Long, limit: Int): List<LegacyMediaRow> =
        media.filter { it.mediaId > afterId }.sortedBy { it.mediaId }.take(limit)

    override suspend fun volumeForMediaId(mediaId: Long): String? = volumesByMediaId[mediaId]
    override suspend fun externalVolumeNames(): List<String> = volumes
    override suspend fun geoRows(): List<LegacyGeoRow> = geo
    override suspend fun recognitionRows(): List<LegacyRecognitionRow> = recognition
    override suspend fun faceRows(): List<LegacyFaceRow> = faces
    override suspend fun textBlockRows(): List<LegacyTextBlockRow> = textBlocks
    override suspend fun recognitionFailures(): List<LegacyFailureRow> = recognitionFailures
    override suspend fun traitRows(): List<LegacyTraitRow> = traits
    override suspend fun embeddingRows(): List<LegacyEmbeddingRow> = embeddings
    override suspend fun embeddingFailures(): List<LegacyFailureRow> = embeddingFailures
    override suspend fun videoMomentRows(): List<LegacyVideoMomentRow> = videoMoments
    override suspend fun videoScannedIds(): Set<Long> = videoScanned
    override suspend fun videoMomentFeedbackRows(): List<LegacyMomentFeedbackRow> = videoMomentFeedback
    override suspend fun favoriteIds(): Set<Long> = favorites
    override suspend fun sensitiveIds(): Set<Long> = sensitive
    override suspend fun libraryData(): LegacyLibraryData = library
    override suspend fun folderLocks(): List<LegacyFolderLock> = locks
    override fun nowMs(): Long = clockMs
}

class FakeMigrationEnvironment : MigrationEnvironment {
    val exportedJson = mutableListOf<String>()
    var copiedOldStoreFiles = false
    var deletedOldStoresAtLiveLocation = false
    private var cutoverComplete = false
    private var startsSinceCutover = 0

    override suspend fun writeExportJson(json: String, timestampMs: Long) {
        exportedJson += json
    }

    override suspend fun copyOldStoreFiles() {
        copiedOldStoreFiles = true
    }

    override suspend fun deleteOldStoresAtLiveLocation() {
        deletedOldStoresAtLiveLocation = true
    }

    override suspend fun markCutoverComplete() {
        cutoverComplete = true
    }

    override suspend fun isCutoverComplete(): Boolean = cutoverComplete

    override suspend fun recordSuccessfulStartSinceCutover(): Int {
        startsSinceCutover++
        return startsSinceCutover
    }
}

/** Always reports parity -- [MigrationToV2]'s own tests aren't exercising the Android/Compose
 *  projection code [ProjectionParityCheck] exists to bridge to; that comparison is :app's job. */
class AlwaysParityCheck : ProjectionParityCheck {
    override suspend fun oldFingerprints(): Map<String, String> = emptyMap()
    override suspend fun newFingerprints(): Map<String, String> = emptyMap()
}
