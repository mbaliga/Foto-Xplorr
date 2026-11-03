package com.fotoxplorr.app.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.recognition.ClusterFace
import com.fotoxplorr.app.recognition.PeopleCopy
import com.fotoxplorr.app.recognition.PersonCard
import com.fotoxplorr.app.recognition.RecognitionProgress
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The cards, the empty state and the filter chip. Layout and the face picture itself are device checks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class PeoplePaneComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun card(id: Long, name: String?, count: Int) =
        PersonCard(id, name, count, ClusterFace(MediaId(id), 0, 0.1f), hidden = false)

    @Test
    fun `each card shows its name or Person N and its photo count, and opens on tap`() {
        var opened = -1L
        compose.setContent {
            PeopleOverview(
                cards = listOf(card(3, "Asha", 12), card(1, null, 1)),
                coverAssets = emptyMap(),
                progress = RecognitionProgress(),
                hiddenCount = 0,
                showHidden = false,
                onToggleHidden = {},
                onOpen = { opened = it },
                onManage = {},
                topInset = 0.dp,
            )
        }
        compose.onNodeWithText("Asha").assertIsDisplayed()
        compose.onNodeWithText("12 photos").assertIsDisplayed()
        compose.onNodeWithText("Person 1").assertIsDisplayed()
        compose.onNodeWithText("1 photo").assertIsDisplayed()
        compose.onNodeWithTag(PeopleTags.card(3)).assertHasClickAction().performClick()
        assertEquals(3L, opened)
    }

    @Test
    fun `the manage button is a visible 48 dp target that names the person`() {
        var managed = -1L
        compose.setContent {
            PeopleOverview(
                cards = listOf(card(5, null, 4)),
                coverAssets = emptyMap(),
                progress = RecognitionProgress(),
                hiddenCount = 0,
                showHidden = false,
                onToggleHidden = {},
                onOpen = {},
                onManage = { managed = it.id },
                topInset = 0.dp,
            )
        }
        compose.onNodeWithTag(PeopleTags.manage(5)).assertTouchHeightIsEqualTo(48.dp).performClick()
        assertEquals(5L, managed)
    }

    @Test
    fun `the status line shows honest progress above the cards`() {
        compose.setContent {
            PeopleOverview(
                cards = listOf(card(1, null, 2)),
                coverAssets = emptyMap(),
                progress = RecognitionProgress(running = true, completed = 749, total = 20_743),
                hiddenCount = 0,
                showHidden = false,
                onToggleHidden = {},
                onOpen = {},
                onManage = {},
                topInset = 0.dp,
            )
        }
        compose.onNodeWithTag(PeopleTags.STATUS).assertIsDisplayed()
        compose.onNodeWithText(PeopleCopy.working(749, 20_743)).assertIsDisplayed()
    }

    @Test
    fun `with nobody found the empty state says what is happening, what to do, and that faces stay on the device`() {
        compose.setContent {
            PeopleOverview(
                cards = emptyList(),
                coverAssets = emptyMap(),
                progress = RecognitionProgress(running = true, completed = 10, total = 100),
                hiddenCount = 0,
                showHidden = false,
                onToggleHidden = {},
                onOpen = {},
                onManage = {},
                topInset = 0.dp,
            )
        }
        compose.onNodeWithTag(PeopleTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText(PeopleCopy.EMPTY_NOTHING_YET).assertIsDisplayed()
        compose.onNodeWithText(PeopleCopy.EMPTY_WHAT_TO_DO).assertIsDisplayed()
        compose.onNodeWithText(PeopleCopy.EMPTY_PRIVATE).assertIsDisplayed()
        compose.onNodeWithText(PeopleCopy.working(10, 100)).assertIsDisplayed()
    }

    @Test
    fun `when everyone found is hidden the empty state says so and offers them back`() {
        var toggled = 0
        compose.setContent {
            PeopleOverview(
                cards = emptyList(),
                coverAssets = emptyMap(),
                progress = RecognitionProgress(),
                hiddenCount = 2,
                showHidden = false,
                onToggleHidden = { toggled++ },
                onOpen = {},
                onManage = {},
                topInset = 0.dp,
            )
        }
        compose.onNodeWithText(PeopleCopy.EMPTY_ALL_HIDDEN).assertIsDisplayed()
        compose.onNodeWithText(PeopleCopy.showHidden(2)).performClick()
        assertEquals(1, toggled)
    }

    @Test
    fun `the filter chip reads Person N cross and clears the filter`() {
        var cleared = 0
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                PersonFilterBar(
                    name = "Person 3",
                    selectedCount = 0,
                    onBack = { cleared++ },
                    onManage = {},
                    onNotThisPerson = {},
                    topInset = 0.dp,
                )
            }
        }
        compose.onNodeWithText("Person 3 ×").assertIsDisplayed()
        compose.onNodeWithTag(PeopleTags.CHIP).assertTouchHeightIsEqualTo(48.dp).performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun `not this person shows only while photos are selected`() {
        var removed = 0
        compose.setContent {
            PersonFilterBar(
                name = "Asha",
                selectedCount = 2,
                onBack = {},
                onManage = {},
                onNotThisPerson = { removed++ },
                topInset = 0.dp,
            )
        }
        compose.onNodeWithText("${PeopleCopy.ACTION_NOT_THIS_PERSON} (2)").performClick()
        assertEquals(1, removed)
    }
}
