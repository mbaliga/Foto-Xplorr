package com.fotoxplorr.app.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.hyle.HyleTextField
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.recognition.FaceCropLoader
import com.fotoxplorr.app.recognition.PeopleCopy
import com.fotoxplorr.app.recognition.PersonCard
import com.fotoxplorr.app.recognition.RecognitionProgress
import com.fotoxplorr.app.ui.HyleGrotesk
import com.fotoxplorr.core.model.MediaId

// The People destination's own surface. Fixed neutrals on purpose (reference design, like the rest
// of the destination chrome): a dark card ground, no accent role.
private val CARD_GROUND = Color(0xFF1A1A1A)
private val INK = Color.White
private val INK_DIM = Color.White.copy(alpha = 0.6f)
private val FACE_SIZE = 88.dp
private const val TARGET_DP = 48

/** Test tags, so a Compose test can find the pieces without reading the copy. */
object PeopleTags {
    const val GRID = "people-grid"
    const val STATUS = "people-status"
    const val EMPTY = "people-empty"
    const val CHIP = "people-filter-chip"
    const val BACK = "people-back"
    fun card(id: Long) = "person-card-$id"
    fun manage(id: Long) = "person-manage-$id"
}

/** The one line of honest progress at the top of the People destination. */
internal fun peopleStatusLine(progress: RecognitionProgress): String = when {
    progress.message != null -> PeopleCopy.stopped(progress.message)
    progress.running -> PeopleCopy.working(progress.completed, progress.total)
    progress.failed > 0 -> PeopleCopy.couldNotCheck(progress.failed)
    progress.passFinished -> PeopleCopy.UP_TO_DATE
    // A fresh install, or before the first pass has begun: nothing has been checked, and saying
    // otherwise would be the one false thing this line can say.
    else -> PeopleCopy.NOT_CHECKED
}

/**
 * The People destination: a grid of person cards, a status line, and the empty state when there is
 * nobody yet.
 *
 * [topInset] clears the status bar and the activity shade, which is drawn OVER this content (it
 * never displaces it): the strip this replaces put its round pictures at the very top, behind the
 * shade, so only the text under them showed -- the "bare row of Person 1 ... Person 7" in the
 * owner's recording.
 */
@Composable
internal fun PeopleOverview(
    cards: List<PersonCard>,
    coverAssets: Map<MediaId, MediaAsset>,
    progress: RecognitionProgress,
    hiddenCount: Int,
    showHidden: Boolean,
    onToggleHidden: () -> Unit,
    onOpen: (Long) -> Unit,
    onManage: (PersonCard) -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = peopleTopInset(),
) {
    // Pictures are held in memory only; leaving the destination lets them go.
    DisposableEffect(Unit) { onDispose { FaceCropLoader.clear() } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        modifier = modifier.fillMaxSize().background(Color.Black).testTag(PeopleTags.GRID),
        contentPadding = PaddingValues(start = 16.dp, end = 28.dp, top = topInset, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = peopleStatusLine(progress),
                color = INK_DIM,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag(PeopleTags.STATUS),
            )
        }
        if (cards.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PeopleEmptyState(allHidden = hiddenCount > 0 && !showHidden)
            }
        } else {
            items(cards, key = { it.id }) { card ->
                PersonCardView(
                    card = card,
                    cover = card.cover?.let { coverAssets[it.mediaId] },
                    onOpen = { onOpen(card.id) },
                    onManage = { onManage(card) },
                )
            }
        }
        if (hiddenCount > 0) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = if (showHidden) PeopleCopy.HIDE_HIDDEN else PeopleCopy.showHidden(hiddenCount),
                    color = INK,
                    style = TextStyle(fontFamily = HyleGrotesk, fontSize = 14.sp),
                    modifier = Modifier
                        .heightIn(min = TARGET_DP.dp)
                        .clickable(role = Role.Button, onClick = onToggleHidden)
                        .padding(vertical = 14.dp),
                )
            }
        }
    }
}

/** What to say when there is nobody to show: what is happening, what to do, and where the faces stay. */
@Composable
internal fun PeopleEmptyState(allHidden: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag(PeopleTags.EMPTY),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (allHidden) PeopleCopy.EMPTY_ALL_HIDDEN else PeopleCopy.EMPTY_NOTHING_YET,
            color = INK,
            style = TextStyle(fontFamily = HyleGrotesk, fontSize = 17.sp, fontWeight = FontWeight.Medium),
        )
        Text(PeopleCopy.EMPTY_WHAT_TO_DO, color = INK_DIM, style = TextStyle(fontFamily = HyleGrotesk, fontSize = 14.sp))
        Text(PeopleCopy.EMPTY_PRIVATE, color = INK_DIM, style = TextStyle(fontFamily = HyleGrotesk, fontSize = 14.sp))
    }
}

/**
 * Status bar plus the activity shade's band, in dp: [shadeReserve] is the shade's current height
 * (0 when no job is running, so a pane with no shade does not leave a blank strip), capped at the
 * collapsed band. Capped, not followed: an opened shade is a transient overlay that covers more
 * for a moment, and a pane that slid down with it would be the jiggle the shade was built to avoid.
 */
@Composable
internal fun peopleTopInset(shadeReserve: Dp = 0.dp): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        minOf(shadeReserve, COLLAPSED_SHADE_DP.dp) + AIR_DP.dp

// hyle/ActivityShade.kt COLLAPSED_HEIGHT.
private const val COLLAPSED_SHADE_DP = 28
private const val AIR_DP = 8

@Composable
internal fun PersonCardView(
    card: PersonCard,
    cover: MediaAsset?,
    onOpen: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PeopleTags.card(card.id))
                .semantics(mergeDescendants = true) {
                    contentDescription = "${card.displayName}, ${PeopleCopy.photos(card.count)}"
                }
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(vertical = 4.dp),
        ) {
            FaceThumb(card = card, cover = cover, size = FACE_SIZE)
            Text(
                text = card.displayName,
                color = if (card.hidden) INK_DIM else INK,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = PeopleCopy.photos(card.count),
                color = INK_DIM,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 12.sp),
            )
        }
        // A button, not a hold: the way to manage a person is something you can see and tap.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(TARGET_DP.dp)
                .testTag(PeopleTags.manage(card.id))
                .semantics { contentDescription = "${PeopleCopy.MANAGE}: ${card.displayName}" }
                .clickable(role = Role.Button, onClick = onManage),
            contentAlignment = Alignment.TopEnd,
        ) {
            Icon(Icons.Outlined.MoreVert, contentDescription = null, tint = INK_DIM, modifier = Modifier.padding(top = 4.dp, end = 4.dp))
        }
    }
}

/** The face picture: the crop once it has loaded, a neutral person mark until then (and when it cannot). */
@Composable
internal fun FaceThumb(card: PersonCard, cover: MediaAsset?, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val face = card.cover
    val bitmap by produceState(
        initialValue = if (cover != null && face != null) FaceCropLoader.cached(cover, face, sizePx) else null,
        cover, face, sizePx,
    ) {
        if (cover != null && face != null && value == null) {
            value = FaceCropLoader.load(context, cover, face, sizePx)
        }
    }
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(CARD_GROUND),
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Outlined.Person,
                contentDescription = PeopleCopy.FACE_PENDING,
                tint = INK_DIM,
                modifier = Modifier.size(size / 2),
            )
        }
    }
}

/**
 * Above the grid when one person's photos are showing: a way back, and the filter as a chip with a
 * clear cross -- "Person 3 ×". Both are 48 dp targets and neither needs a drag.
 *
 * While photos are selected it also offers "Not this person" for them, which takes them out of
 * this person (and only this person); the photos themselves are untouched.
 */
@Composable
internal fun PersonFilterBar(
    name: String,
    selectedCount: Int,
    onBack: () -> Unit,
    onManage: () -> Unit,
    onNotThisPerson: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = peopleTopInset(),
) {
    Row(
        modifier = modifier.fillMaxWidth().background(Color.Black).padding(top = topInset, start = 8.dp, end = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = TARGET_DP.dp)
                .background(CARD_GROUND, RoundedCornerShape(TARGET_DP.dp / 2))
                .testTag(PeopleTags.CHIP)
                .semantics { contentDescription = PeopleCopy.clearFilter(name) }
                .clickable(role = Role.Button, onClick = onBack)
                .padding(start = 18.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = PeopleCopy.filterChip(name),
                color = INK,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectedCount > 0) {
            TextButton(onClick = onNotThisPerson, modifier = Modifier.heightIn(min = TARGET_DP.dp)) {
                Text("${PeopleCopy.ACTION_NOT_THIS_PERSON} ($selectedCount)", color = INK)
            }
        }
        Box(Modifier.weight(1f))
        Box(
            Modifier
                .size(TARGET_DP.dp)
                .semantics { contentDescription = PeopleCopy.MANAGE }
                .clickable(role = Role.Button, onClick = onManage),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.MoreVert, contentDescription = null, tint = INK_DIM)
        }
    }
}

/**
 * Rename, hide / show again, and merge, for one person. Merge is a second step inside the same
 * dialog -- pick who this person really is -- because a merge is the one choice here that cannot be
 * undone from the screen.
 */
@Composable
internal fun PersonManageDialog(
    card: PersonCard,
    others: List<PersonCard>,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onToggleHidden: () -> Unit,
    onMerge: (targetId: Long) -> Unit,
) {
    var name by remember(card.id) { mutableStateOf(card.name.orEmpty()) }
    var merging by remember(card.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (merging) PeopleCopy.MERGE_TITLE else card.displayName) },
        text = {
            if (merging) {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(others, key = { it.id }) { other ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = TARGET_DP.dp)
                                .clickable(role = Role.Button) { onMerge(other.id) }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(other.displayName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(PeopleCopy.photos(other.count), color = INK_DIM)
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HyleTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = PeopleCopy.NAME_LABEL,
                        placeholder = PeopleCopy.NAME_PLACEHOLDER,
                        clearable = true,
                    )
                    TextButton(onClick = onToggleHidden, modifier = Modifier.heightIn(min = TARGET_DP.dp)) {
                        Text(if (card.hidden) PeopleCopy.ACTION_UNHIDE else PeopleCopy.ACTION_HIDE)
                    }
                    if (others.isNotEmpty()) {
                        TextButton(onClick = { merging = true }, modifier = Modifier.heightIn(min = TARGET_DP.dp)) {
                            Text(PeopleCopy.ACTION_MERGE)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!merging) TextButton(onClick = { onRename(name) }) { Text(PeopleCopy.SAVE) }
        },
        dismissButton = {
            TextButton(onClick = { if (merging) merging = false else onDismiss() }) { Text(PeopleCopy.CANCEL) }
        },
    )
}

/**
 * The People destination's overview as the browser hosts it: derives the cards from the stored
 * roster and the destination's own (already visibility-filtered) [assets], and owns the little
 * state the pane has (hidden people shown or not, which person is being managed).
 */
@Composable
internal fun PeopleOverviewHost(
    state: GalleryUiState,
    assets: List<MediaAsset>,
    actions: PeopleActions,
    onOpenPerson: (Long) -> Unit,
    shadeReserve: Dp = 0.dp,
) {
    var showHidden by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var managing by remember { mutableStateOf<Long?>(null) }
    val roster = state.recognition.roster
    val everyone = remember(roster, assets) {
        com.fotoxplorr.app.recognition.PeopleRoster.cards(roster, assets.mapTo(HashSet()) { it.id }, showHidden = true)
    }
    val cards = remember(everyone, showHidden) { if (showHidden) everyone else everyone.filterNot { it.hidden } }
    val hiddenCount = remember(everyone) { everyone.count { it.hidden } }
    val coversById = remember(assets, cards) {
        val wanted = cards.mapNotNullTo(HashSet()) { it.cover?.mediaId }
        assets.filter { it.id in wanted }.associateBy { it.id }
    }
    PeopleOverview(
        cards = cards,
        coverAssets = coversById,
        progress = state.recognitionProgress,
        hiddenCount = hiddenCount,
        showHidden = showHidden,
        onToggleHidden = { showHidden = !showHidden },
        onOpen = onOpenPerson,
        onManage = { managing = it.id },
        topInset = peopleTopInset(shadeReserve),
    )
    everyone.firstOrNull { it.id == managing }?.let { card ->
        PersonManageDialog(
            card = card,
            others = everyone.filter { it.id != card.id },
            onDismiss = { managing = null },
            onRename = { actions.onRename(card.id, it); managing = null },
            onToggleHidden = { actions.onHide(card.id, !card.hidden); managing = null },
            onMerge = { actions.onMerge(card.id, it); managing = null },
        )
    }
}

/** [PersonFilterBar] as the browser hosts it, for the person [personId] the grid is narrowed to. */
@Composable
internal fun PersonFilterHeader(
    personId: Long,
    state: GalleryUiState,
    selection: GallerySelection,
    onSelectionChange: (GallerySelection) -> Unit,
    actions: PeopleActions,
    onClear: () -> Unit,
    shadeReserve: Dp = 0.dp,
) {
    // Leaving the person also leaves the selection: it was of THEIR photos, and a selection left
    // active on the overview would keep the selection bar up and the shade's activities hidden.
    val leave = {
        onSelectionChange(selection.clear())
        onClear()
    }
    var managing by remember { mutableStateOf(false) }
    val roster = state.recognition.roster
    val person = roster.firstOrNull { it.id == personId }
    // The dialog's merge list needs everyone, not just this person's photos; worked out only while
    // it is open, because it walks the whole library's visibility rule.
    val everyone = remember(managing, roster) {
        if (!managing) {
            emptyList()
        } else {
            val visible = destinationAssets(HyleDestination.PEOPLE, state).mapTo(HashSet()) { it.id }
            com.fotoxplorr.app.recognition.PeopleRoster.cards(roster, visible, showHidden = true)
        }
    }
    PersonFilterBar(
        name = person?.displayName ?: PeopleCopy.unnamed(personId),
        selectedCount = selection.selectedIds.size,
        onBack = leave,
        onManage = { managing = true },
        topInset = peopleTopInset(shadeReserve),
        onNotThisPerson = {
            actions.onNotThisPerson(personId, selection.selectedIds)
            onSelectionChange(selection.clear())
        },
    )
    val card = everyone.firstOrNull { it.id == personId }
    if (managing && card != null) {
        PersonManageDialog(
            card = card,
            others = everyone.filter { it.id != personId },
            onDismiss = { managing = false },
            onRename = { actions.onRename(personId, it); managing = false },
            onToggleHidden = { actions.onHide(personId, !card.hidden); managing = false; leave() },
            onMerge = { actions.onMerge(personId, it); managing = false; leave() },
        )
    }
}
