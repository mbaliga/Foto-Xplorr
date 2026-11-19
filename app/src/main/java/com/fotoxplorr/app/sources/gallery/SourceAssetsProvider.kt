package com.fotoxplorr.app.sources.gallery

import com.fotoxplorr.core.index.sources.SourceAssetRow
import com.fotoxplorr.core.index.sources.SourceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One media file of a source together with the source it belongs to (what the mapper needs). */
data class SourceAssetEntry(val row: SourceAssetRow, val source: SourceInfo)

/**
 * Where the gallery gets the files of added folders and drives (ADR-020). An interface so the
 * activity does not know about `fotoz-sources.db`: U4's `SourcesRuntime` supplies the real one;
 * until then [EmptySourceAssetsProvider] is wired and nothing changes for anyone.
 *
 * Implementations emit only rows the last completed walk still saw (`present`); the merge drops
 * the rest again, so a slow provider cannot show a vanished file.
 */
interface SourceAssetsProvider {
    fun assets(): Flow<List<SourceAssetEntry>>
}

/** No sources. Opens no database and no file, so wiring it is free (U3 must not create fotoz-sources.db). */
object EmptySourceAssetsProvider : SourceAssetsProvider {
    private val none: StateFlow<List<SourceAssetEntry>> = MutableStateFlow(emptyList())
    override fun assets(): Flow<List<SourceAssetEntry>> = none
}
