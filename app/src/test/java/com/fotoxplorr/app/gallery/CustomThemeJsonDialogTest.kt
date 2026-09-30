package com.fotoxplorr.app.gallery

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The owner's mandatory preview, as an actual interaction: paste JSON, continue, and the dialog
 * has to show back EXACTLY what was pasted -- not a reformatted, trimmed or re-serialised copy --
 * before Apply does anything, and never proceed to that preview at all on malformed input.
 *
 * `NATIVE` graphics mode and `sdk = 34`, same as [com.fotoxplorr.app.render.ScreenRenderTest]:
 * legacy Robolectric graphics draws nothing, and 36 has no Robolectric runtime yet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CustomThemeJsonDialogTest {

    @get:Rule
    val compose = createComposeRule()

    // Deliberately unusual formatting (mixed indentation, no space after ":") -- if the preview
    // ever normalised or re-serialised the JSON instead of echoing the raw text, a byte-identical
    // round-trip like this is exactly what would catch it.
    private val pastedJson = "{\n \"version\":1,\n  \"name\": \"Mine\",\n\"colors\":{" +
        "\"light\":{\"primary\":\"#112233\",\"secondary\":\"#112233\",\"tertiary\":\"#112233\"," +
        "\"background\":\"#FFFFFF\",\"surface\":\"#FFFFFF\",\"surfaceVariant\":\"#FFFFFF\"," +
        "\"onBackground\":\"#000000\",\"onSurface\":\"#000000\"}," +
        "\"dark\":{\"primary\":\"#AABBCC\",\"secondary\":\"#AABBCC\",\"tertiary\":\"#AABBCC\"," +
        "\"background\":\"#000000\",\"surface\":\"#000000\",\"surfaceVariant\":\"#000000\"," +
        "\"onBackground\":\"#FFFFFF\",\"onSurface\":\"#FFFFFF\"}}}"

    @Test
    fun `the preview shows exactly the pasted text, unmodified, and only Apply persists it`() {
        var applied: String? = null
        compose.setContent {
            CustomThemeJsonDialog(initialJson = "", onDismiss = {}, onApply = { applied = it })
        }

        compose.onNodeWithTag(CUSTOM_THEME_JSON_INPUT_TAG).performTextInput(pastedJson)
        compose.onNodeWithText("Continue").performClick()

        compose.onNodeWithTag(CUSTOM_THEME_JSON_PREVIEW_TAG).assertTextEquals(pastedJson)
        // Not applied yet -- the preview is not the apply.
        assertNull(applied)

        compose.onNodeWithText("Apply").performClick()
        assertEquals(pastedJson, applied)
    }

    @Test
    fun `Cancel on the preview applies nothing`() {
        var applied: String? = null
        compose.setContent {
            CustomThemeJsonDialog(initialJson = "", onDismiss = {}, onApply = { applied = it })
        }

        compose.onNodeWithTag(CUSTOM_THEME_JSON_INPUT_TAG).performTextInput(pastedJson)
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertNull(applied)
    }

    @Test
    fun `malformed JSON shows a specific inline error and never reaches the preview`() {
        compose.setContent {
            CustomThemeJsonDialog(initialJson = "", onDismiss = {}, onApply = {})
        }

        compose.onNodeWithTag(CUSTOM_THEME_JSON_INPUT_TAG).performTextInput("{not json at all")
        compose.onNodeWithText("Continue").performClick()

        compose.onNodeWithText("Malformed JSON", substring = true).assertExists()
        compose.onNodeWithTag(CUSTOM_THEME_JSON_PREVIEW_TAG).assertDoesNotExist()
    }

    @Test
    fun `a specific validation error -- a bad hex colour -- is shown inline too`() {
        val badColor = pastedJson.replace("\"primary\":\"#112233\"", "\"primary\":\"notacolor\"")
        compose.setContent {
            CustomThemeJsonDialog(initialJson = "", onDismiss = {}, onApply = {})
        }

        compose.onNodeWithTag(CUSTOM_THEME_JSON_INPUT_TAG).performTextInput(badColor)
        compose.onNodeWithText("Continue").performClick()

        compose.onNodeWithText("colors.light.primary", substring = true).assertExists()
        compose.onNodeWithTag(CUSTOM_THEME_JSON_PREVIEW_TAG).assertDoesNotExist()
    }
}
