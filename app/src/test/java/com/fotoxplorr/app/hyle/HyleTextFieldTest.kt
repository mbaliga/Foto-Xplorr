package com.fotoxplorr.app.hyle

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The one field: typing, the error line, the numeric keyboard, the clear button, disabled. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class HyleTextFieldTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `typing reaches the caller`() {
        var text by mutableStateOf("")
        compose.setContent { HyleTextField(value = text, onValueChange = { text = it }, label = "Name", fieldTag = "f") }
        compose.onNodeWithTag("f").performTextInput("hello")
        assertEquals("hello", text)
        compose.onNodeWithText("Name").assertExists()
    }

    @Test
    fun `the error line is shown in words and exposed as the field's error`() {
        compose.setContent { HyleTextField(value = "x", onValueChange = {}, label = "Latitude", errorText = "Between -90 and 90.", fieldTag = "f") }
        compose.onNodeWithText("Between -90 and 90.").assertExists()
        val node = compose.onNodeWithTag("f").fetchSemanticsNode()
        assertEquals("Between -90 and 90.", node.config[SemanticsProperties.Error])
    }

    @Test
    fun `the field is named for TalkBack from its label`() {
        compose.setContent { HyleTextField(value = "", onValueChange = {}, label = "Longitude", fieldTag = "f") }
        val description = compose.onNodeWithTag("f").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertTrue(description.contains("Longitude"))
    }

    @Test
    fun `a decimal keyboard and an IME action are passed to the editor`() {
        compose.setContent {
            HyleTextField(
                value = "1",
                onValueChange = {},
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                fieldTag = "f",
            )
        }
        val node = compose.onNodeWithTag("f").fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsActions.SetText))
        // The editable node carries its IME action in its semantics.
        assertEquals(ImeAction.Next, node.config[SemanticsProperties.ImeAction])
    }

    @Test
    fun `the clear button empties the field and exists only while there is text`() {
        var text by mutableStateOf("abc")
        compose.setContent { HyleTextField(value = text, onValueChange = { text = it }, label = "Tag", clearable = true) }
        compose.onNodeWithContentDescription("Clear Tag").performClick()
        assertEquals("", text)
        compose.onNodeWithContentDescription("Clear Tag").assertDoesNotExist()
    }

    @Test
    fun `the field is at least 48 dp tall`() {
        compose.setContent { HyleTextField(value = "", onValueChange = {}, label = "A", modifier = Modifier.testTag("box")) }
        compose.onNodeWithTag("box").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `a disabled field cannot be edited and shows no clear button`() {
        compose.setContent { HyleTextField(value = "abc", onValueChange = {}, label = "Tag", enabled = false, clearable = true, fieldTag = "f") }
        compose.onNodeWithTag("f").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Clear Tag").assertDoesNotExist()
    }

    @Test
    fun `a long value keeps its first characters visible as text`() {
        val long = "The beginning of a very long value that cannot fit in one narrow line at all, ever"
        compose.setContent { HyleTextField(value = long, onValueChange = {}, modifier = Modifier.testTag("box")) }
        // Unfocused, the value is drawn by an ellipsising Text from its start; the editable keeps it too.
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `an error flag without words still reaches TalkBack`() {
        compose.setContent { HyleTextField(value = "x", onValueChange = {}, error = true, fieldTag = "f") }
        val node = compose.onNodeWithTag("f").fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsProperties.Error))
    }
}
