package com.fotoxplorr.app.editor

/**
 * What the editor's tool hub shows, as a pure function of its state.
 *
 * The shape (owner's choice): the CENTRE holds the few tools this person uses most, a RING of
 * [ToolGroup]s surrounds it, and a group opens onto its everyday tools with its rarely used ones
 * one tap deeper ([GroupPage.deeper]). Nothing scrolls: the centre is [CENTRE_SIZE] tools, a group
 * page is at most [FRONT_MAX] tools plus the deeper ones, and a test holds every group to a size
 * that fits.
 *
 * THE CENTRE IS CALM. A centre that reshuffled every time a slider moved would be worse than the
 * long strip it replaces, so:
 *  - [view] never recomputes the centre. It shows [HubMemory.centre] (filled from the cold-start
 *    defaults when that is empty or short) and nothing else.
 *  - The centre changes only in [settle] (the state one), which the caller invokes when the hub comes back into
 *    view: on entering the editor and when a tool is closed ([closeTool] and [back] do it).
 *    While a tool is open no code path touches the centre.
 *  - [settleCentre] has hysteresis. A tool outside the centre replaces the weakest one inside only when
 *    it leads it by 25 percent plus half a use ([MARGIN_PERCENT], [MARGIN_MILLI]). A newcomer takes
 *    the SLOT of the tool it replaces, so everything else stays where the person's thumb expects it.
 *    The cost: slot 0 is the most used tool on the first run and not necessarily afterwards.
 *
 * Process death: [HubMemory] (use counts and the centre) is the only state worth keeping across
 * launches; [HubMemory.encode] is one SharedPreferences string. The navigation part of [HubState]
 * (open tool, open group, deeper shown) is small enough for `rememberSaveable` as strings; see
 * [HubState.restore], which tolerates an id or group that no longer exists.
 *
 * Pure Kotlin, no Android imports. Not yet wired into [EditorScreen]; see [ToolCatalog].
 */
object HubModel {

    /** Tools in the centre. The owner asked for 4 to 5; 5 fills the middle of a phone-width ring. */
    const val CENTRE_SIZE = 5

    /** Most tools a group page shows before "More". */
    const val FRONT_MAX = 5

    /** A rarely-used tool is promoted onto its group's front page once its learned score reaches this. */
    const val PROMOTE_MILLI = 1_500

    /** Hysteresis: a challenger must beat an incumbent by this percent... */
    const val MARGIN_PERCENT = 25

    /** ...plus this many thousandths of a use. */
    const val MARGIN_MILLI = 500

    /** Learned score plus the rarity prior: what every ranking here compares. */
    fun effectiveScore(usage: ToolUsage, spec: ToolSpec): Int = usage.score(spec.id) + spec.rarity.priorMilli

    /** Centre-eligible tools, best first; ties fall to [ToolCatalog.defaultOrder], so never arbitrary. */
    fun ranked(usage: ToolUsage): List<ToolSpec> =
        ToolCatalog.defaultOrder.filter { it.centreEligible }
            .sortedByDescending { effectiveScore(usage, it) } // stable: ties keep default order

    /**
     * [previous] with the changes [usage] justifies, in the same slot order.
     *
     * Unknown, repeated and ineligible ids in [previous] are dropped first; a short list is filled
     * from the best-ranked tools; then challengers replace incumbents under the hysteresis above.
     * With an empty [previous] the result is simply the top [size] by rank.
     */
    fun settleCentre(previous: List<String>, usage: ToolUsage, size: Int = CENTRE_SIZE): List<String> {
        val ranked = ranked(usage)
        val centre = sanitised(previous, size).toMutableList()
        for (spec in ranked) {
            if (centre.size >= size) break
            if (centre.none { it.id == spec.id }) centre += spec
        }
        // Each swap strictly raises the centre's total score, so this ends; the bound is belt and braces.
        repeat(size) {
            val challenger = ranked.firstOrNull { c -> centre.none { it.id == c.id } } ?: return@repeat
            val weakestIndex = centre.indices.minWithOrNull(
                compareBy<Int>({ effectiveScore(usage, centre[it]) }, { -ToolCatalog.defaultRank(centre[it]) }),
            ) ?: return@repeat
            val weakest = effectiveScore(usage, centre[weakestIndex])
            if (effectiveScore(usage, challenger) < weakest * (100 + MARGIN_PERCENT) / 100 + MARGIN_MILLI) {
                return centre.map { it.id }
            }
            centre[weakestIndex] = challenger
        }
        return centre.map { it.id }
    }

    /** Known, eligible, distinct tools of [ids], in order, at most [size]. */
    private fun sanitised(ids: List<String>, size: Int): List<ToolSpec> =
        ids.mapNotNull { ToolCatalog.get(it) }.filter { it.centreEligible }.distinctBy { it.id }.take(size)

    /** The centre to draw for [memory]: what is remembered, topped up from the cold-start order. */
    private fun shownCentre(memory: HubMemory, size: Int): List<ToolSpec> {
        val kept = sanitised(memory.centre, size).toMutableList()
        for (spec in ToolCatalog.defaultOrder) {
            if (kept.size >= size) break
            if (spec.centreEligible && kept.none { it.id == spec.id }) kept += spec
        }
        return kept
    }

    /**
     * A group's page: [GroupPage.front] are the tools shown at once, [GroupPage.deeper] the ones
     * behind "More". A tool is on the front when it is not [Rarity.RARELY] or has been used enough
     * to reach [PROMOTE_MILLI]; if that is more than [FRONT_MAX], the lowest scores go deeper.
     * Both lists keep catalogue order, so a tool moves between them rarely and never sideways.
     */
    fun groupPage(group: ToolGroup, usage: ToolUsage, deeperShown: Boolean): GroupPage {
        val specs = ToolCatalog.inGroup(group)
        val front = specs
            .filter { it.rarity != Rarity.RARELY || usage.score(it.id) >= PROMOTE_MILLI }
            .toMutableList()
        while (front.size > FRONT_MAX) {
            // Lowest effective score out; on a tie the later catalogue entry goes first.
            val out = front.withIndex().minWith(
                compareBy<IndexedValue<ToolSpec>>({ effectiveScore(usage, it.value) }, { -it.index }),
            ).value
            front.remove(out)
        }
        val deeper = specs.filter { it !in front }
        return GroupPage(group, front, if (deeperShown) deeper else emptyList(), deeper.size)
    }

    /** What to draw for [state]. A pure function of it: nothing here reads a clock or changes anything. */
    fun view(state: HubState, size: Int = CENTRE_SIZE): HubView {
        val open = ToolCatalog.get(state.openTool)
        val group = state.openGroup
        return HubView(
            centre = shownCentre(state.memory, size),
            ring = ToolGroup.entries.toList(),
            activeGroup = group ?: open?.group,
            groupPage = group?.let { groupPage(it, state.memory.usage, state.deeperShown) },
            openTool = open,
        )
    }

    /** The person opened [id], from the centre or from a group page. The centre is untouched. */
    fun openTool(state: HubState, id: String): HubState =
        if (ToolCatalog.has(id)) state.copy(openTool = id) else state

    /** One real use of [id] (see [ToolUsage]'s note on what counts). Changes the counts, never the centre. */
    fun record(state: HubState, id: String): HubState =
        state.copy(memory = state.memory.copy(usage = state.memory.usage.record(id)))

    /** Let the centre catch up with use. The caller's job whenever the hub comes back into view. */
    fun settle(state: HubState, size: Int = CENTRE_SIZE): HubState =
        state.copy(memory = state.memory.copy(centre = settleCentre(state.memory.centre, state.memory.usage, size)))

    /** The tool is done with: no tool open any more, and the centre catches up. */
    fun closeTool(state: HubState): HubState = settle(state.copy(openTool = null))

    /** A ring button: shows [group]'s page (closing any open tool first, which settles the centre). */
    fun showGroup(state: HubState, group: ToolGroup): HubState =
        closeTool(state).copy(openGroup = group, deeperShown = false)

    /** The "More" button on a group page. */
    fun toggleDeeper(state: HubState): HubState =
        if (state.openGroup == null) state else state.copy(deeperShown = !state.deeperShown)

    /**
     * One step back, or null when there is nowhere left to go (the caller then leaves the editor).
     * A tool closes first, then the deeper list, then the group page.
     */
    fun back(state: HubState): HubState? = when {
        state.openTool != null -> closeTool(state)
        state.deeperShown -> state.copy(deeperShown = false)
        state.openGroup != null -> state.copy(openGroup = null)
        else -> null
    }
}

/** What [HubModel.view] says to draw. */
data class HubView(
    val centre: List<ToolSpec>,
    val ring: List<ToolGroup>,
    /** The ring button to mark as current (by more than colour): the open group, else the open tool's. */
    val activeGroup: ToolGroup?,
    val groupPage: GroupPage?,
    val openTool: ToolSpec?,
)

/** A group's page. [deeperCount] is how many tools "More" holds, shown or not. */
data class GroupPage(
    val group: ToolGroup,
    val front: List<ToolSpec>,
    val deeper: List<ToolSpec>,
    val deeperCount: Int,
)

/** The part of the hub worth keeping across launches: use counts and the centre last shown. */
data class HubMemory(val usage: ToolUsage = ToolUsage.EMPTY, val centre: List<String> = emptyList()) {

    /** One string, one SharedPreferences write: `hm1`, the centre as comma-separated ids, the usage text. */
    fun encode(): String = listOf(HEADER, centre.joinToString(","), usage.encode()).joinToString("\n")

    companion object {
        val EMPTY = HubMemory()
        private const val HEADER = "hm1"

        /** Never throws. Text it cannot read gives [EMPTY]; a bad centre or usage part is dropped alone. */
        fun decode(text: String?): HubMemory {
            if (text.isNullOrEmpty() || text.length > 2 * ToolUsage.MAX_ENCODED_CHARS) return EMPTY
            val lines = text.split('\n')
            if (lines.size != 3 || lines[0] != HEADER) return EMPTY
            val centre = lines[1].split(',').filter { ToolCatalog.has(it) }.distinct().take(HubModel.CENTRE_SIZE)
            return HubMemory(ToolUsage.decode(lines[2]), centre)
        }
    }
}

/**
 * The hub's whole state. [openTool] and [openGroup] are ids and enum values, not specs, so they
 * survive being saved as strings; [HubModel.view] ignores an [openTool] that is not in the catalogue.
 */
data class HubState(
    val memory: HubMemory = HubMemory.EMPTY,
    val openTool: String? = null,
    val openGroup: ToolGroup? = null,
    val deeperShown: Boolean = false,
) {
    companion object {
        /** Rebuilds the state from saved strings; anything unknown is dropped rather than trusted. */
        fun restore(memory: HubMemory, toolId: String?, groupName: String?, deeperShown: Boolean): HubState {
            val group = ToolGroup.entries.firstOrNull { it.name == groupName }
            return HubState(
                memory = memory,
                openTool = toolId?.takeIf { ToolCatalog.has(it) },
                openGroup = group,
                deeperShown = deeperShown && group != null,
            )
        }
    }
}
