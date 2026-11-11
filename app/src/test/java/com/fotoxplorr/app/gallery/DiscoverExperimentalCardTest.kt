package com.fotoxplorr.app.gallery

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.app.spatial.GeoMetadata
import com.fotoxplorr.app.spatial.LocalSpatialExperience
import com.fotoxplorr.app.spatial.SpatialExperience
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Experimental card on Discover: one button for a screen reader, with no stray number on it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class DiscoverExperimentalCardTest {

    @get:Rule
    val compose = createComposeRule()

    private var opened = 0

    private fun show(located: Int) {
        val geo = GeoIndexState(
            metadataById = (1..located).associate { n -> MediaId(n.toLong()) to GeoMetadata(MediaId(n.toLong()), 1.0, 2.0, null, null) },
        )
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                CompositionLocalProvider(
                    LocalSpatialExperience provides SpatialExperience(
                        assets = emptyList(),
                        indexInput = emptyList(),
                        geoState = geo,
                        onIndexLocations = {},
                        onOpenAsset = { _, _ -> },
                    ),
                ) {
                    DiscoverScreen(summaries = emptyList(), onOpen = {}, onOpenExperimental = { opened += 1 })
                }
            }
        }
    }

    @Test
    fun `the card is one button with a click and no second long-press action`() {
        show(located = 12)
        compose.onNodeWithText("Experimental")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            .performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `a located count is not read out on the card`() {
        show(located = 12)
        compose.onNodeWithText("12").assertDoesNotExist()
    }
}
