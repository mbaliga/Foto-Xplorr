package com.fotoxplorr.app.backup

import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.asArray
import com.fotoxplorr.app.editor.asLong
import com.fotoxplorr.app.editor.asObject
import com.fotoxplorr.app.editor.asString
import com.fotoxplorr.app.editor.get
import com.fotoxplorr.app.editor.parseJson
import com.fotoxplorr.app.editor.stringify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the tags-and-collections backup: what a file must look like to be read, which
 * photo each id means on the phone that restores it, and how a restore merges. No Android here.
 *
 * The wrong-photo cases are the point. Ids are MediaStore row numbers, so a file restored on a
 * phone that numbers its photos differently must put each tag on the photo it was on, or nowhere.
 */
class MetadataBackupTest {
    private fun json(text: String): JsonValue = checkNotNull(parseJson(text)) { "fixture is not JSON" }

    private fun ids(value: JsonValue): Set<Long> = value.asArray().map { it.asLong(-1) }.toSet()

    private fun identity(name: String, size: Long = 1000, taken: Long = 1_700_000_000_000, path: String? = "DCIM/Camera/") =
        MediaIdentity(name, size, taken, path)

    private val library = """
        {
          "schema": 1,
          "collections": [{"id": "c1", "name": "Holiday", "createdAtMillis": 5, "mediaIds": [1, 2]}],
          "tags": {"beach": [1, 2], "dog": [3]},
          "archivedIds": [4],
          "autoTags": {"dog": [3]},
          "captions": {"1": "sunny", "3": "good dog"},
          "machineCaptionIds": [3],
          "everUnarchivedIds": [],
          "rejectedArchiveSuggestionIds": [],
          "rejectedAutoTags": {},
          "suppressedMachineCaptionIds": []
        }
    """.trimIndent()

    /** Phone A: ids 1..4 are these four photos. */
    private val phoneA = mapOf(
        1L to identity("IMG_0001.jpg", 1001, 1_700_000_001_000),
        2L to identity("IMG_0002.jpg", 1002, 1_700_000_002_000),
        3L to identity("IMG_0003.jpg", 1003, 1_700_000_003_000),
        4L to identity("IMG_0004.jpg", 1004, 1_700_000_004_000),
    )

    private fun fileFor(catalogue: Map<Long, MediaIdentity>, fav: Set<Long> = setOf(1L), sensitive: Set<Long> = setOf(4L)) =
        MetadataBackup.buildFile(json(library), fav, sensitive, catalogue, 1_800_000_000_000)

    private fun usable(text: String): MetadataBackup.MetadataFile {
        val read = MetadataBackup.read(text)
        assertTrue("expected a usable file but got ${(read as? MetadataBackup.FileRead.Unusable)?.reason}", read is MetadataBackup.FileRead.Usable)
        return (read as MetadataBackup.FileRead.Usable).file
    }

    private fun unusable(text: String): String {
        val read = MetadataBackup.read(text)
        assertTrue("expected the file to be refused", read is MetadataBackup.FileRead.Unusable)
        return (read as MetadataBackup.FileRead.Unusable).reason
    }

    // ---- writing ----

    @Test
    fun `the file names every photo it mentions`() {
        val built = fileFor(phoneA)
        val identityBlock = built.json["identity"].asObject()
        assertEquals(setOf("1", "2", "3", "4"), identityBlock.keys)
        val first = built.json["identity"]["1"]
        assertEquals("IMG_0001.jpg", first["name"].asString())
        assertEquals(1001L, first["size"].asLong())
        assertEquals(1_700_000_001_000L, first["dateTaken"].asLong())
        assertEquals("DCIM/Camera/", first["path"].asString())
        assertEquals(2L, built.json["schema"].asLong())
        assertEquals(MetadataBackup.KIND, built.json["kind"].asString())
        assertEquals(4, built.coverage.photos)
        assertEquals(4, built.coverage.withIdentity)
        assertEquals(0, built.coverage.withoutIdentity)
        assertFalse(built.coverage.catalogueMissing)
    }

    @Test
    fun `a photo that is no longer on the phone gets no identity and is counted`() {
        val built = fileFor(phoneA - 4L)
        assertEquals(setOf("1", "2", "3"), built.json["identity"].asObject().keys)
        assertEquals(1, built.coverage.withoutIdentity)
        // favourites and sensitive ids count as mentioned too
        val withExtra = fileFor(phoneA, fav = setOf(1L, 99L))
        assertEquals(5, withExtra.coverage.photos)
        assertEquals(1, withExtra.coverage.withoutIdentity)
    }

    @Test
    fun `an empty catalogue is recognised so a save can be refused`() {
        val built = fileFor(emptyMap())
        assertTrue(built.coverage.catalogueMissing)
        // ...but a library that mentions no photo at all is simply an empty backup
        val empty = MetadataBackup.buildFile(MetadataBackup.emptyLibrary(), emptySet(), emptySet(), emptyMap(), 1)
        assertFalse(empty.coverage.catalogueMissing)
    }

    @Test
    fun `a missing folder is left out of the identity rather than written as null`() {
        val built = MetadataBackup.buildFile(json(library), emptySet(), emptySet(), mapOf(1L to identity("a.jpg", path = null)), 1)
        assertFalse(built.json["identity"]["1"].asObject().containsKey("path"))
    }

    // ---- reading ----

    @Test
    fun `a file we wrote reads back and restores onto the same photos`() {
        val text = fileFor(phoneA).json.stringify()
        val file = usable(text)
        assertTrue(file.hasIdentity)
        assertEquals(1_800_000_000_000, file.exportedAtMillis)
        val prepared = MetadataBackup.prepare(file, phoneA)
        assertEquals(setOf(1L), prepared.favoriteIds)
        assertEquals(setOf(4L), prepared.sensitiveIds)
        assertEquals(setOf(1L, 2L), ids(prepared.library["tags"]["beach"]))
        assertEquals(setOf(1L, 2L), ids(prepared.library["collections"].asArray()[0]["mediaIds"]))
        assertEquals(4, prepared.summary.photosFound)
        assertEquals(0, prepared.summary.photosRenumbered)
        assertEquals(0, prepared.summary.photosMissing)
        assertTrue(prepared.summary.hasIdentity)
    }

    @Test
    fun `an old file with an envelope and no identity is still read, and says so`() {
        val text = """{"schema":1,"exportedAtMillis":5,"library":$library,"favoriteIds":[1],"sensitiveIds":[]}"""
        val file = usable(text)
        assertFalse(file.hasIdentity)
        val prepared = MetadataBackup.prepare(file, emptyMap())
        // No identity: the ids are used as they stand, which is only right on the original phone.
        assertEquals(setOf(1L, 2L), ids(prepared.library["tags"]["beach"]))
        assertEquals(setOf(1L), prepared.favoriteIds)
        assertFalse(prepared.summary.hasIdentity)
        assertEquals(0, prepared.summary.photosMissing)
    }

    @Test
    fun `the oldest shape, the library with no envelope, is still read`() {
        val file = usable(library)
        assertFalse(file.hasIdentity)
        val prepared = MetadataBackup.prepare(file, emptyMap())
        assertEquals(setOf(3L), ids(prepared.library["tags"]["dog"]))
        assertTrue(prepared.favoriteIds.isEmpty())
    }

    @Test
    fun `things that are not a backup are refused as a whole`() {
        unusable("")
        unusable("not json at all")
        unusable("[1,2,3]")
        unusable("\"a string\"")
        unusable("{}")
        unusable("""{"hello":"world"}""")
        unusable("""{"schema":1,"unrelated":true}""")
        // truncated mid-file
        unusable(fileFor(phoneA).json.stringify().dropLast(40))
    }

    @Test
    fun `a file from a newer Fotoz is refused with a way forward`() {
        val reason = unusable("""{"schema":3,"kind":"${MetadataBackup.KIND}","library":$library,"identity":{}}""")
        assertTrue(reason, reason.contains("newer version"))
        val inner = unusable("""{"schema":2,"kind":"${MetadataBackup.KIND}","identity":{},"library":${library.replace("\"schema\": 1", "\"schema\": 7")}}""")
        assertTrue(inner, inner.contains("newer version"))
    }

    @Test
    fun `a schema 2 file without its identity block or its kind is refused`() {
        val noIdentity = unusable("""{"schema":2,"kind":"${MetadataBackup.KIND}","library":$library}""")
        assertTrue(noIdentity, noIdentity.contains("which photos"))
        unusable("""{"schema":2,"library":$library,"identity":{}}""")
        unusable("""{"schema":2,"kind":"something-else","library":$library,"identity":{}}""")
        unusable("""{"schema":2,"kind":"${MetadataBackup.KIND}","library":$library,"identity":[]}""")
    }

    @Test
    fun `every wrong container type is refused before anything could be written`() {
        fun withLibrary(change: (String) -> String) = unusable("""{"schema":1,"library":${change(library)}}""")
        withLibrary { it.replace("\"tags\": {\"beach\": [1, 2], \"dog\": [3]}", "\"tags\": [1]") }
        withLibrary { it.replace("\"beach\": [1, 2]", "\"beach\": 7") }
        withLibrary { it.replace("\"archivedIds\": [4]", "\"archivedIds\": {\"a\": 1}") }
        withLibrary { it.replace("\"captions\": {\"1\": \"sunny\", \"3\": \"good dog\"}", "\"captions\": {\"1\": 5}") }
        withLibrary { it.replace("\"captions\": {\"1\": \"sunny\", \"3\": \"good dog\"}", "\"captions\": []") }
        withLibrary { it.replace("\"collections\": [", "\"collections\": [5, ") }
        withLibrary { it.replace("\"name\": \"Holiday\"", "\"name\": 12") }
        withLibrary { it.replace("\"mediaIds\": [1, 2]", "\"mediaIds\": \"1,2\"") }
        withLibrary { it.replace("\"autoTags\": {\"dog\": [3]}", "\"autoTags\": {\"dog\": \"3\"}") }
        withLibrary { it.replace("\"schema\": 1", "\"schema\": \"1\"") }
        withLibrary { it.replace("\"schema\": 1,", "") }
        unusable("""{"schema":1,"library":$library,"favoriteIds":"1"}""")
        unusable("""{"schema":1,"library":[]}""")
        unusable("""{"schema":"x","library":$library}""")
        unusable("""{"schema":1.5,"library":$library}""")
    }

    @Test
    fun `a file larger than the limit is refused without being parsed`() {
        val reason = unusable(" ".repeat(MetadataBackup.MAX_FILE_BYTES + 1))
        assertTrue(reason, reason.contains("too large"))
    }

    @Test
    fun `bad single ids are skipped and counted, not fatal`() {
        val text = """{"schema":1,"library":{"schema":1,"tags":{"x":[1,-5,2.5,"7",3]},"captions":{"abc":"nope","2":"fine"}},"favoriteIds":[1,-1]}"""
        val prepared = MetadataBackup.prepare(usable(text), emptyMap())
        assertEquals(setOf(1L, 3L), ids(prepared.library["tags"]["x"]))
        assertEquals(setOf("2"), prepared.library["captions"].asObject().keys)
        assertEquals(setOf(1L), prepared.favoriteIds)
        assertTrue(prepared.summary.unusableEntries >= 4)
    }

    @Test
    fun `identity entries that are not usable make the photo not found rather than guessed`() {
        val text = """
            {"schema":2,"kind":"${MetadataBackup.KIND}","library":$library,
             "identity":{"1":{"name":""},"2":"nope","3":{"name":"IMG_0003.jpg","size":1003,"dateTaken":1700000003000},"x":{"name":"a"},"-1":{"name":"b"}}}
        """.trimIndent()
        val file = usable(text)
        assertEquals(setOf(3L), file.identity!!.keys)
        val prepared = MetadataBackup.prepare(file, phoneA)
        assertEquals(setOf(3L), ids(prepared.library["tags"]["dog"]))
        assertEquals(null, prepared.library["tags"]["beach"].takeIf { it is JsonValue.JsonArray })
    }

    // ---- which photo is which ----

    /** Phone B: a restore after a reinstall. Same photos, different row numbers, plus a new one. */
    private val phoneB = mapOf(
        501L to phoneA.getValue(1L),
        502L to phoneA.getValue(2L),
        503L to phoneA.getValue(3L),
        // photo 4 was not copied across
        900L to identity("IMG_9000.jpg", 9000, 1_600_000_000_000),
    )

    @Test
    fun `on another phone every tag follows its photo to the new id`() {
        val prepared = MetadataBackup.prepare(usable(fileFor(phoneA).json.stringify()), phoneB)
        assertEquals(setOf(501L, 502L), ids(prepared.library["tags"]["beach"]))
        assertEquals(setOf(503L), ids(prepared.library["tags"]["dog"]))
        assertEquals(setOf(503L), ids(prepared.library["autoTags"]["dog"]))
        assertEquals(setOf("501", "503"), prepared.library["captions"].asObject().keys)
        assertEquals(setOf(503L), ids(prepared.library["machineCaptionIds"]))
        assertEquals(setOf(501L, 502L), ids(prepared.library["collections"].asArray()[0]["mediaIds"]))
        assertEquals(setOf(501L), prepared.favoriteIds)
        // photo 4 (archived, sensitive) is not on this phone: dropped and counted, never moved onto another photo
        assertTrue(prepared.sensitiveIds.isEmpty())
        assertTrue(ids(prepared.library["archivedIds"]).isEmpty())
        assertEquals(4, prepared.summary.photosInFile)
        assertEquals(3, prepared.summary.photosFound)
        assertEquals(3, prepared.summary.photosRenumbered)
        assertEquals(1, prepared.summary.photosMissing)
    }

    @Test
    fun `an id that is now a different photo on this phone gets nothing`() {
        // The hazard: phone C has an id 1 and an id 3, but they are other photos.
        val phoneC = mapOf(
            1L to identity("DSC_7777.jpg", 7, 1),
            2L to phoneA.getValue(2L),
            3L to identity("Screenshot.png", 3, 3),
        )
        val prepared = MetadataBackup.prepare(usable(fileFor(phoneA).json.stringify()), phoneC)
        assertEquals("beach goes only to photo 2", setOf(2L), ids(prepared.library["tags"]["beach"]))
        assertFalse(prepared.library["tags"].asObject().containsKey("dog"))
        assertEquals(setOf("1", "3").intersect(prepared.library["captions"].asObject().keys), emptySet<String>())
        assertTrue(prepared.favoriteIds.isEmpty())
        assertEquals(3, prepared.summary.photosMissing)
    }

    @Test
    fun `two photos that cannot be told apart are skipped, not guessed`() {
        val twins = mapOf(10L to phoneA.getValue(3L), 11L to phoneA.getValue(3L))
        val prepared = MetadataBackup.prepare(usable(fileFor(phoneA).json.stringify()), twins)
        // 3 is not an id here; two catalogue photos fit it equally, and neither is picked
        assertFalse(prepared.library["tags"].asObject().containsKey("dog"))
    }

    @Test
    fun `nothing matching at all is reported so the screen can refuse to restore`() {
        val prepared = MetadataBackup.prepare(usable(fileFor(phoneA).json.stringify()), emptyMap())
        assertEquals(0, prepared.summary.photosFound)
        assertEquals(4, prepared.summary.photosMissing)
        assertTrue(prepared.summary.nothingToRestore)
        assertTrue(prepared.library["tags"].asObject().isEmpty())
    }

    @Test
    fun `an id the file mentions but does not describe is dropped`() {
        val text = """{"schema":2,"kind":"${MetadataBackup.KIND}","library":$library,"identity":{"1":{"name":"IMG_0001.jpg","size":1001,"dateTaken":1700000001000}}}"""
        val prepared = MetadataBackup.prepare(usable(text), phoneA)
        assertEquals(setOf(1L), ids(prepared.library["tags"]["beach"]))
        assertEquals(3, prepared.summary.photosMissing)
    }

    // ---- merging ----

    private fun lib(
        collections: String = "[]",
        tags: String = "{}",
        archived: String = "[]",
        auto: String = "{}",
        captions: String = "{}",
        machine: String = "[]",
        ever: String = "[]",
        rejectedArchive: String = "[]",
        rejectedAuto: String = "{}",
        suppressed: String = "[]",
    ) = json(
        """{"schema":1,"collections":$collections,"tags":$tags,"archivedIds":$archived,"autoTags":$auto,"captions":$captions,
            "machineCaptionIds":$machine,"everUnarchivedIds":$ever,"rejectedArchiveSuggestionIds":$rejectedArchive,
            "rejectedAutoTags":$rejectedAuto,"suppressedMachineCaptionIds":$suppressed}""",
    )

    private fun merge(current: JsonValue, incoming: JsonValue) = MetadataBackup.mergeLibraries(current, incoming)

    @Test
    fun `merging into nothing, or nothing into something, changes nothing`() {
        val x = json(library)
        val normalised = merge(MetadataBackup.emptyLibrary(), x)
        assertEquals(normalised, merge(normalised, MetadataBackup.emptyLibrary()))
        assertEquals(normalised, merge(normalised, normalised))
        assertEquals(setOf(1L, 2L), ids(normalised["tags"]["beach"]))
    }

    @Test
    fun `tags union and a collection joins by id or by name`() {
        val current = lib(
            collections = """[{"id":"a","name":"Holiday","createdAtMillis":1,"mediaIds":[1]},{"id":"b","name":"Work","createdAtMillis":2,"mediaIds":[]}]""",
            tags = """{"beach":[1]}""",
        )
        val incoming = lib(
            collections = """[{"id":"a","name":"Renamed elsewhere","createdAtMillis":9,"mediaIds":[2]},
                {"id":"zz","name":"work","createdAtMillis":3,"mediaIds":[5]},{"id":"c","name":"New","createdAtMillis":4,"mediaIds":[6]}]""",
            tags = """{"beach":[2],"fresh":[3]}""",
        )
        val out = merge(current, incoming)
        val byId = out["collections"].asArray().associateBy { it["id"].asString() }
        assertEquals(setOf("a", "b", "c"), byId.keys)
        assertEquals("the name already here stays", "Holiday", byId.getValue("a")["name"].asString())
        assertEquals(1L, byId.getValue("a")["createdAtMillis"].asLong())
        assertEquals(setOf(1L, 2L), ids(byId.getValue("a")["mediaIds"]))
        assertEquals("joined by name, ignoring case", setOf(5L), ids(byId.getValue("b")["mediaIds"]))
        assertEquals(setOf(6L), ids(byId.getValue("c")["mediaIds"]))
        assertEquals(setOf(1L, 2L), ids(out["tags"]["beach"]))
        assertEquals(setOf(3L), ids(out["tags"]["fresh"]))
    }

    @Test
    fun `a caption written here is never overwritten`() {
        val current = lib(captions = """{"1":"mine","2":"machine one"}""", machine = "[2]")
        val incoming = lib(captions = """{"1":"theirs","2":"theirs two","3":"only theirs"}""", machine = "[3]")
        val out = merge(current, incoming)
        assertEquals("mine", out["captions"]["1"].asString())
        assertEquals("machine one", out["captions"]["2"].asString())
        assertEquals("only theirs", out["captions"]["3"].asString())
        assertEquals("the machine marker follows whichever side supplied the caption", setOf(2L, 3L), ids(out["machineCaptionIds"]))
    }

    @Test
    fun `a machine caption the person cleared here does not come back`() {
        val current = lib(suppressed = "[7]")
        val incoming = lib(captions = """{"7":"a machine sentence","8":"a typed sentence"}""", machine = "[7]")
        val out = merge(current, incoming)
        assertEquals(setOf("8"), out["captions"].asObject().keys)
        assertEquals(setOf(7L), ids(out["suppressedMachineCaptionIds"]))
    }

    @Test
    fun `a tag the person typed never becomes machine applied through a merge`() {
        // Here the person typed "dog" on 1; the file says "dog" on 1 was auto-applied.
        val current = lib(tags = """{"dog":[1]}""")
        val incoming = lib(tags = """{"dog":[1,2]}""", auto = """{"dog":[1,2]}""")
        val out = merge(current, incoming)
        assertEquals(setOf(1L, 2L), ids(out["tags"]["dog"]))
        assertEquals("only the photo no one typed it on stays auto", setOf(2L), ids(out["autoTags"]["dog"]))

        // ...and the other way round: auto here, typed in the file.
        val out2 = merge(lib(tags = """{"dog":[1]}""", auto = """{"dog":[1]}"""), lib(tags = """{"dog":[1]}"""))
        assertTrue(ids(out2["autoTags"]["dog"]).isEmpty())
    }

    @Test
    fun `an auto marker needs the tag, and a rejection yields to a tag now present`() {
        val out = merge(
            lib(rejectedAuto = """{"cat":[1],"dog":[1]}"""),
            lib(tags = """{"dog":[1]}""", auto = """{"dog":[1],"ghost":[9]}""", rejectedAuto = """{"bird":[2]}"""),
        )
        assertFalse(out["autoTags"].asObject().containsKey("ghost"))
        assertEquals("dog is on photo 1 now, so the old rejection of it is moot", null, out["rejectedAutoTags"].asObject()["dog"])
        assertEquals(setOf(1L), ids(out["rejectedAutoTags"]["cat"]))
        assertEquals(setOf(2L), ids(out["rejectedAutoTags"]["bird"]))
    }

    @Test
    fun `a photo the person un-archived here is not archived again`() {
        val current = lib(archived = "[5]", ever = "[5,6]")
        val incoming = lib(archived = "[5,6,7]", ever = "[8]", rejectedArchive = "[9]")
        val out = merge(current, incoming)
        // 6 was archived once and un-archived here on purpose; 5 is archived here; 7 is new
        assertEquals(setOf(5L, 7L), ids(out["archivedIds"]))
        assertEquals(setOf(5L, 6L, 8L), ids(out["everUnarchivedIds"]))
        assertEquals(setOf(9L), ids(out["rejectedArchiveSuggestionIds"]))
    }

    @Test
    fun `whitespace in names and empty tags are tidied the way the store always did`() {
        val out = merge(
            lib(tags = """{"beach":[1]}"""),
            lib(tags = """{" beach ":[2],"":[3],"empty":[]}""", collections = """[{"id":"","name":"   ","mediaIds":[1]}]"""),
        )
        assertEquals(setOf("beach"), out["tags"].asObject().keys)
        assertEquals(setOf(1L, 2L), ids(out["tags"]["beach"]))
        assertTrue(out["collections"].asArray().isEmpty())
    }

    @Test
    fun `the library written out has exactly the keys the export has`() {
        val keys = (merge(MetadataBackup.emptyLibrary(), json(library)) as JsonValue.JsonObject).entries.keys
        assertEquals(IdRemap.LIBRARY_KEYS + "schema", keys)
        assertNotNull(keys)
    }

    @Test
    fun `a replace is the file's content and nothing of what was there`() {
        // REPLACE merges into an empty library, so none of the current side can leak in.
        val replaced = merge(MetadataBackup.emptyLibrary(), lib(tags = """{"only":[1]}"""))
        assertEquals(setOf("only"), replaced["tags"].asObject().keys)
    }
}
