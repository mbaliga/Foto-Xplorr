package com.fotoxplorr.app.sources.gallery

import android.content.Context
import android.graphics.Bitmap
import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryActionsRoom
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.GallerySelection
import com.fotoxplorr.app.gallery.GalleryScreen
import com.fotoxplorr.app.gallery.GalleryUiState
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.app.render.ViewerFixtures
import com.fotoxplorr.app.render.emptyState
import com.fotoxplorr.app.render.noOpActions
import com.fotoxplorr.app.sources.saf.FakeDocumentsProvider
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.viewer.ViewerScreen
import com.fotoxplorr.app.viewer.ViewerQuickAction
import com.fotoxplorr.app.viewer.viewerQuickActions
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A source photo through the real seams: a document uri served by a fake provider (the way U2's
 * lister tests do) decodes through `decodeUpright` and Coil with no special casing, a mixed list
 * renders in the gallery, the permission empty state gives way to sources, the room disables the
 * rows that write, and the viewer opens the file. Layout and real provider speed are device checks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// SDK 28: Coil decodes through BitmapFactory below API 29. On 29+ it uses ImageDecoder from the
// provider's file descriptor, which Robolectric's native ImageDecoder does not implement ("unimplemented");
// the provider fetch itself works on both (device check: decode on API 29+).
@Config(sdk = [28], qualifiers = "w440dp-h956dp-xhdpi")
class SourceGalleryComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        FakeDocumentsProvider.reset()
        FakeDocumentsProvider.install()
        ViewerFixtures.installSynchronousImageLoader(context)
    }

    @After
    fun tearDown() = FakeDocumentsProvider.reset()

    /** A real PNG served by the provider as document [id]; returns the source asset showing it. */
    private fun sourceAsset(id: Long, width: Int = 120, height: Int = 80): MediaAsset {
        val file = File(context.cacheDir, "src-doc-$id.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        FakeDocumentsProvider.files["img$id"] = file
        val uri = DocumentsContract.buildDocumentUriUsingTree(FakeDocumentsProvider.treeUri, "img$id").toString()
        return SourceAssetMapper.toMediaAsset(
            entry(sourceRow(id, name = "Trip_$id.png", uri = uri, width = width, height = height)),
        )!!.copy(mimeType = "image/png")
    }

    // Distinct days, so the grid does not fold the fixtures into one burst stack.
    private fun library(n: Int): List<MediaAsset> = (1L..n.toLong()).map {
        ViewerFixtures.portrait(context, it, seed = it.toInt()).copy(dateTakenMillis = 1_600_000_000_000L - it * 86_400_000L)
    }

    @Test
    fun `a document uri decodes through decodeUpright like any other uri`() {
        val asset = sourceAsset(1, width = 120, height = 80)
        val decoded = runBlocking { decodeUpright(context, asset.contentUri, DecodeLimits(maxLongEdge = 4096, maxPixels = 16_000_000L)) }
        assertNotNull("the provider's stream did not decode", decoded)
        assertEquals(120, decoded!!.sourceWidth)
        assertEquals(80, decoded.sourceHeight)
    }

    @Test
    fun `a mixed list renders its library tiles and its source tile`() {
        val source = sourceAsset(1)
        val items = library(3) + source
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                GalleryScreen(state = emptyState().copy(assets = items), actions = noOpActions())
            }
        }
        compose.waitForIdle()
        assertTrue("the source tile is missing", compose.onAllNodesWithContentDescription(source.displayName).fetchSemanticsNodes().isNotEmpty())
        items.take(3).forEach {
            assertTrue("${it.displayName} missing", compose.onAllNodesWithContentDescription(it.displayName).fetchSemanticsNodes().isNotEmpty())
        }
    }

    private fun galleryWith(permission: Boolean, assets: List<MediaAsset>): GalleryUiState =
        emptyState().copy(assets = assets, permissionGranted = permission)

    @Test
    fun `denied media access with sources shows the sources not the permission screen`() {
        val source = sourceAsset(1)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                GalleryScreen(state = galleryWith(permission = false, assets = listOf(source)), actions = noOpActions())
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Your gallery stays on this device").assertCountEquals(0)
        assertTrue(compose.onAllNodesWithContentDescription(source.displayName).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `denied media access with no sources still shows the permission screen`() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                GalleryScreen(state = galleryWith(permission = false, assets = emptyList()), actions = noOpActions())
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Your gallery stays on this device").assertExists()
    }

    private fun showRoom(selected: List<MediaAsset>) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                GalleryActionsRoom(
                    state = emptyState(),
                    actions = noOpActions(),
                    selection = GallerySelection(selectedIds = selected.map { it.id }.toSet(), selecting = true),
                    selectedAssets = selected,
                    currentIds = emptySet(),
                    inTrash = false,
                    inArchive = false,
                    tagRoute = null,
                    collectionRoute = null,
                    onSelectionChange = {},
                    onRenameAsset = {},
                    onAddToCollection = {},
                    onAddTag = {},
                    onStartSelection = {},
                    onNewCollection = {},
                    onCloseRoom = {},
                    hasBottomPeek = false,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a source selection disables every writing row and says why while share stays live`() {
        showRoom(listOf(sourceAsset(1)))
        listOf("Add to favourites", "Mark sensitive", "Archive", "Add to collection", "Add tag", "Rename", "Move to trash").forEach {
            compose.onNodeWithText(it).performScrollTo().assertHasNoClickAction()
        }
        assertTrue(
            "the reason is written beside the disabled rows",
            compose.onAllNodesWithText(SourceGateCopy.WRITES_UNAVAILABLE).fetchSemanticsNodes().size >= 5,
        )
        compose.onNodeWithText("Share").performScrollTo().assertHasClickAction()
        compose.onNodeWithText("Select all").performScrollTo().assertHasClickAction()
    }

    @Test
    fun `a library selection keeps every row live and shows no reason`() {
        showRoom(listOf(libraryAsset(1)))
        listOf("Add to favourites", "Mark sensitive", "Archive", "Add to collection", "Add tag", "Rename").forEach {
            compose.onNodeWithText(it).performScrollTo().assertHasClickAction()
        }
        compose.onAllNodesWithText(SourceGateCopy.WRITES_UNAVAILABLE).assertCountEquals(0)
    }

    @Test
    fun `a source photo opens in the viewer and its neighbours decode from the provider`() {
        val a = sourceAsset(1)
        val b = sourceAsset(2)
        val lib = library(2)
        val roll = lib + listOf(a, b)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    ViewerScreen(
                        asset = a,
                        position = roll.indexOf(a) + 1,
                        total = roll.size,
                        isFavorite = false,
                        isSensitive = false,
                        hasPrevious = true,
                        hasNext = true,
                        canMoveToTrash = true,
                        slideshowActive = false,
                        slideshowIntervalSeconds = 2,
                        onToggleSlideshow = {},
                        onToggleFavorite = {},
                        onToggleSensitive = {},
                        onShare = {},
                        onEdit = {},
                        onOpenWith = {},
                        onMoveToTrash = {},
                        onPrevious = {},
                        onNext = {},
                        onClose = {},
                        showFilmstrip = true,
                        relatedAssets = roll,
                        onSelectAsset = {},
                        plateMapBackdrop = ViewerFixtures.mapStandIn,
                        // What the activity passes for a source file: no catalogue rows, not isExternal.
                        catalogueActions = false,
                    )
                }
            }
        }
        compose.waitForIdle()
        // The photo itself is on screen, decoded from the provider's stream (an Image, not the
        // "can't be shown here" placeholder) ...
        compose.onNode(hasContentDescription(a.displayName) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
            .assertExists()
        // ... and one tap raises the chrome (it starts down over a photo).
        compose.onRoot().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close").assertExists()
        assertTrue(compose.onAllNodesWithContentDescription(b.displayName).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `the viewer strip for a source file offers share and no favourite or trash`() {
        // catalogueActions = false and isExternal = false is how the activity opens a source file.
        val actions = viewerQuickActions(catalogueActions = false, isExternal = false, canMoveToTrash = true)
        assertEquals(listOf(ViewerQuickAction.SHARE), actions)
    }
}
