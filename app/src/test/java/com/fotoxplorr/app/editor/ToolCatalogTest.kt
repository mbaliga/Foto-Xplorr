package com.fotoxplorr.app.editor

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds [ToolCatalog] to the editor that really exists. The editor's tab list is a private enum
 * inside EditorScreen.kt, so it is read the only way a test can: from that file's text (the same
 * technique HelpCopyTest uses; Gradle runs unit tests with the app module as the working
 * directory). Adjustments and EditRecipe are read by reflection.
 */
class ToolCatalogTest {

    private val screenSource: String by lazy {
        File("src/main/java/com/fotoxplorr/app/editor/EditorScreen.kt").also {
            assertTrue("EditorScreen.kt not found from ${File(".").absolutePath}", it.isFile)
        }.readText()
    }

    /** The names declared in `private enum class EditTool(...) { LIGHT("Light"), ... }`. */
    private fun editorTabs(): List<String> {
        val start = screenSource.indexOf("enum class EditTool(")
        assertTrue("EditTool enum not found", start >= 0)
        val body = screenSource.substring(screenSource.indexOf('{', start), screenSource.indexOf("\n}", start))
        return Regex("""(?m)^\s+([A-Z_]+)\("""").findAll(body).map { it.groupValues[1] }.toList()
    }

    private fun propertyNames(type: Class<*>): Set<String> =
        type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name }.toSet()

    @Test
    fun `the scan really finds the editor's tabs`() {
        // Guards the guards: an empty list would make every tab test below pass for nothing.
        val tabs = editorTabs()
        assertTrue("tabs: $tabs", tabs.size >= 9)
        assertTrue("LIGHT" in tabs && "HEAL" in tabs && "CURVES" in tabs)
    }

    @Test
    fun `ids are unique, well formed and stable text`() {
        val ids = ToolCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertTrue(it, Regex("[a-z][a-z0-9_]{0,31}").matches(it)) }
        assertEquals(24, ToolCatalog.all.size)
    }

    @Test
    fun `titles and icon keys are present and not repeated`() {
        val titles = ToolCatalog.all.map { it.title }
        assertEquals(titles.size, titles.toSet().size)
        titles.forEach { assertTrue(it, it.isNotBlank() && it == it.trim() && it.length <= 24) }
        val icons = ToolCatalog.all.map { it.iconKey } + ToolGroup.entries.map { it.iconKey }
        assertEquals(icons.size, icons.toSet().size)
        icons.forEach { assertTrue(it, it.isNotBlank()) }
    }

    @Test
    fun `every tool is in exactly one group and every group has tools`() {
        val perGroup = ToolGroup.entries.map { ToolCatalog.inGroup(it) }
        assertEquals(ToolCatalog.all.size, perGroup.sumOf { it.size })
        assertEquals(ToolCatalog.all.toSet(), perGroup.flatten().toSet())
        perGroup.forEach { assertTrue(it.isNotEmpty()) }
    }

    @Test
    fun `every group has something that is not buried on a first run`() {
        for (group in ToolGroup.entries) {
            assertTrue(group.name, ToolCatalog.inGroup(group).any { it.rarity != Rarity.RARELY })
        }
    }

    @Test
    fun `every editor tab is the host of at least one tool and every host is a real tab`() {
        val tabs = editorTabs().toSet()
        assertEquals(tabs, ToolCatalog.all.map { it.hostTab }.toSet())
    }

    @Test
    fun `every property the editor can change belongs to exactly one tool`() {
        val real = propertyNames(Adjustments::class.java).map { "adjustments.$it" } +
            (propertyNames(EditRecipe::class.java) - "adjustments").map { "recipe.$it" }
        assertTrue("reflection found ${real.size}", real.size >= 20)
        val claimed = ToolCatalog.all.flatMap { it.fields }
        assertEquals("a property claimed twice", claimed.size, claimed.toSet().size)
        assertEquals(
            "missing from the catalogue: ${real.toSet() - claimed.toSet()}, unknown to the editor: ${claimed.toSet() - real.toSet()}",
            real.toSet(),
            claimed.toSet(),
        )
    }

    @Test
    fun `every title is a label the editor really shows`() {
        for (spec in ToolCatalog.all) {
            assertTrue("${spec.title} is not a string in EditorScreen.kt", screenSource.contains("\"${spec.title}\""))
        }
    }

    @Test
    fun `every slider the editor draws is a tool, or a named sub-control of one`() {
        val labels = Regex("""LabelledSlider\(\s*"([^"]+)"""").findAll(screenSource).map { it.groupValues[1] }.toSet()
        assertTrue("found $labels", labels.size >= 15)
        // Spot size is the Heal tool's own control, not a tool.
        val subControls = setOf("Spot size")
        val titles = ToolCatalog.all.map { it.title }.toSet()
        assertEquals(emptySet<String>(), labels - titles - subControls)
        // And the other way: a slider-kind tool is a slider that is really there.
        ToolCatalog.all.filter { it.panel == PanelKind.SLIDER }.forEach {
            assertTrue("${it.title} has no LabelledSlider", it.title in labels)
        }
    }

    @Test
    fun `a destructive action is never offered in the centre`() {
        val actions = ToolCatalog.all.filter { it.panel == PanelKind.ACTION }
        assertEquals(setOf(ToolIds.RESET, ToolIds.REVERT), actions.map { it.id }.toSet())
        actions.forEach { assertFalse(it.id, it.centreEligible) }
        ToolCatalog.all.filter { it.panel != PanelKind.ACTION }.forEach { assertTrue(it.id, it.centreEligible) }
    }

    @Test
    fun `the cold start order puts everyday tools first and is rarity then catalogue order`() {
        val order = ToolCatalog.defaultOrder
        assertEquals(ToolCatalog.all.toSet(), order.toSet())
        assertEquals(order.map { it.rarity }, order.map { it.rarity }.sortedBy { it.ordinal })
        assertEquals(
            listOf(ToolIds.EXPOSURE, ToolIds.CONTRAST, ToolIds.SATURATION, ToolIds.CROP, ToolIds.STRAIGHTEN),
            ToolCatalog.defaultCentre().map { it.id },
        )
        order.forEachIndexed { i, spec -> assertEquals(i, ToolCatalog.defaultRank(spec)) }
    }

    @Test
    fun `lookup by id`() {
        assertEquals(ToolIds.HEAL, ToolCatalog.get(ToolIds.HEAL)?.id)
        assertEquals(null, ToolCatalog.get("nope"))
        assertEquals(null, ToolCatalog.get(null))
    }

    @Test
    fun `the copy is short and complete`() {
        val strings = HubCopy::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.also { f -> f.isAccessible = true }.get(null) as String }
        assertTrue(strings.size >= 30)
        strings.forEach { assertTrue(it, it.isNotBlank() && it.length <= 30) }
        ToolGroup.entries.forEach {
            assertTrue(HubCopy.groupTitle(it).isNotBlank())
            assertTrue(HubCopy.groupDescription(it, 3).contains("3"))
        }
        assertEquals("Light", HubCopy.groupDescription(ToolGroup.LIGHT, 0))
    }
}
