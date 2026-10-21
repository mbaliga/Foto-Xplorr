@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fotoxplorr.ut.bridge

import com.fotoxplorr.core.db.baseAssetUser
import com.fotoxplorr.core.db.entity.AssetEntity
import com.fotoxplorr.core.db.entity.Availability
import com.fotoxplorr.core.db.entity.SourceEntity
import com.fotoxplorr.core.db.entity.SourceKind
import com.fotoxplorr.core.db.entity.SourceState
import com.fotoxplorr.core.model.AssetId
import com.fotoxplorr.core.model.FormatId
import com.fotoxplorr.core.model.SourceId
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.remove
import platform.posix.rmdir
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP8.1's own gate: REAL execution of the `@CName`-exported functions, not just "it compiles" --
 * called directly, as this module's own KDoc on each of them says Kotlin/Native allows within the
 * same compilation (the `linuxX64Test` compilation is associated with `linuxX64Main`, so it can
 * also see `internal` declarations like [LibraryHandle] and [asLibraryHandleOrNull] -- used below
 * only to seed real rows through the handle's own real, open [com.fotoxplorr.core.db.FotozDatabase],
 * never to bypass the C API surface under test itself).
 */
class BridgeApiTest {

    private lateinit var dbDir: String
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        dbDir = "/tmp/fotoz-bridge-test-${getpid()}-${Random.nextInt(0, Int.MAX_VALUE)}"
        assertEquals(0, mkdir(dbDir, 493u /* 0755 */), "could not create temp dir $dbDir")
        dbPath = "$dbDir/fotoz.db"
    }

    @AfterTest
    fun tearDown() {
        remove(dbPath)
        remove("$dbPath-wal")
        remove("$dbPath-shm")
        remove("$dbPath-journal")
        rmdir(dbDir)
    }

    private fun openHandle(): COpaquePointer = memScoped {
        assertNotNull(fotoz_library_open(dbPath.cstr.ptr))
    }

    private fun seedAsset(
        library: LibraryHandle,
        displayName: String = "IMG_0001.jpg",
        mime: String = "image/jpeg",
        dateTakenMs: Long = 1_000_000L,
        favorite: Boolean = false,
    ): Long = runBlocking {
        val sourceId = library.db.sourceDao().findByLocator(SourceKind.MEDIASTORE_VOLUME, "external_primary")?.sourceId
            ?: SourceId(
                library.db.sourceDao().insert(
                    SourceEntity(
                        sourceId = SourceId(0),
                        kind = SourceKind.MEDIASTORE_VOLUME,
                        rootLocator = "external_primary",
                        volumeUuid = null,
                        displayName = "Internal storage",
                        state = SourceState.ONLINE,
                        syncVersion = null,
                        syncGeneration = null,
                        lastFullScanMs = null,
                    ),
                ),
            )
        val assetId = library.db.assetDao().insert(
            AssetEntity(
                assetId = AssetId(0),
                sourceId = sourceId,
                locator = Random.nextLong(1, Long.MAX_VALUE).toString(),
                contentUri = null,
                displayName = displayName,
                mime = mime,
                formatId = FormatId("jpeg"),
                sizeBytes = 1024,
                width = 100,
                height = 100,
                dateTakenMs = dateTakenMs,
                dateModifiedMs = dateTakenMs,
                dateAddedMs = null,
                relativePath = "DCIM/Camera/",
                folderKey = "1:path:dcim/camera",
                bucketId = null,
                bucketName = null,
                fingerprint = null,
                contentHash = null,
                xmpDocumentId = null,
                availability = Availability.ONLINE,
                trashedAtMs = null,
                msGenerationModified = null,
                revision = 0,
            ),
        )
        // Every real asset gets a matching `asset_user` row alongside it -- see
        // `MigrationToV2.runAssets` (core/db/.../migration/MigrationToV2.kt:272-273), which upserts
        // exactly this `baseAssetUser` the moment an asset is inserted, before anything else ever
        // touches it. `GalleryProjectionDao`'s smart-album queries rely on this invariant: they
        // find an asset via `asset_id IN (SELECT ... FROM asset_user WHERE archived = 0)`, a
        // POSITIVE match against that row, not a `NOT IN archived = 1` negative one -- an asset
        // with no `asset_user` row at all is invisible to them, correctly, because that state
        // never occurs for a real asset. Reusing the real `baseAssetUser` function (not a
        // hand-rolled duplicate) means this fixture can never silently drift from what production
        // code actually inserts.
        library.db.assetUserDao().upsert(baseAssetUser(AssetId(assetId), favorite = favorite))
        assetId
    }

    // ---- capabilities() ---------------------------------------------------------------------

    @Test
    fun `capabilities reports a real -- stable feature matrix`() {
        val cPtr = fotoz_capabilities()
        val text = cPtr.toKString()
        fotoz_free_string(cPtr)

        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("room-kmp-bundled-sqlite", obj["database"]!!.jsonObject["engine"]!!.jsonPrimitive.content)
        assertTrue(obj["queryPage"]!!.jsonObject["available"]!!.jsonPrimitive.boolean)
        assertTrue(obj["queryPage"]!!.jsonObject["albums"]!!.jsonArray.isNotEmpty())
        assertTrue(obj["assetDetail"]!!.jsonObject["available"]!!.jsonPrimitive.boolean)
        assertTrue(obj["setMetadata"]!!.jsonObject["sidecarName"]!!.jsonPrimitive.boolean)
        assertTrue(obj["setMetadata"]!!.jsonObject["strip"]!!.jsonPrimitive.boolean)
        assertFalse(obj["setMetadata"]!!.jsonObject["xmp"]!!.jsonPrimitive.boolean)
        assertFalse(obj["sync"]!!.jsonObject["available"]!!.jsonPrimitive.boolean)
        assertFalse(obj["ops"]!!.jsonObject["available"]!!.jsonPrimitive.boolean)
    }

    // ---- library_open / library_close --------------------------------------------------------

    @Test
    fun `library_open on a fresh path opens a real database without error`() = memScoped {
        val handle = fotoz_library_open(dbPath.cstr.ptr)
        assertNotNull(handle, "expected a real handle for a writable fresh path")
        val library = handle.asLibraryHandleOrNull()
        assertNotNull(library)
        // A real, open FotozDatabase: proves this isn't a fake non-null sentinel.
        runBlocking { assertEquals(0, library.db.assetDao().count()) }
        fotoz_library_close(handle)
    }

    @Test
    fun `library_open returns null for a null path`() {
        assertNull(fotoz_library_open(null))
    }

    @Test
    fun `library_open returns null when the parent directory does not exist`() = memScoped {
        val badPath = "$dbDir/does-not-exist/fotoz.db"
        assertNull(fotoz_library_open(badPath.cstr.ptr))
    }

    // ---- query_page() -------------------------------------------------------------------------

    @Test
    fun `query_page against real seeded data returns real rows for EVERYDAY`() = memScoped {
        val handle = openHandle()
        val library = handle.asLibraryHandleOrNull()!!
        val plain = seedAsset(library, displayName = "sunset-beach.jpg")
        seedAsset(library, displayName = "receipt-scan.jpg", dateTakenMs = 500_000L)

        val ast = """{"album":"EVERYDAY"}""".cstr.ptr
        val resultPtr = fotoz_query_page(handle, ast, null)
        assertNotNull(resultPtr)
        val text = resultPtr.toKString()
        fotoz_free_string(resultPtr)

        val obj = Json.parseToJsonElement(text).jsonObject
        assertNull(obj["error"])
        assertEquals(2, obj["count"]!!.jsonPrimitive.int)
        val ids = obj["rows"]!!.jsonArray.map { it.jsonObject["assetId"]!!.jsonPrimitive.long }
        assertTrue(plain in ids)

        fotoz_library_close(handle)
    }

    @Test
    fun `query_page free-text word filter matches display_name via the real search parser`() = memScoped {
        val handle = openHandle()
        val library = handle.asLibraryHandleOrNull()!!
        val sunset = seedAsset(library, displayName = "sunset-beach.jpg")
        seedAsset(library, displayName = "receipt-scan.jpg")

        val ast = """{"album":"EVERYDAY","query":"sunset"}""".cstr.ptr
        val resultPtr = fotoz_query_page(handle, ast, null)!!
        val text = resultPtr.toKString()
        fotoz_free_string(resultPtr)

        val obj = Json.parseToJsonElement(text).jsonObject
        assertTrue(obj["textFilterApplied"]!!.jsonPrimitive.boolean)
        val ids = obj["rows"]!!.jsonArray.map { it.jsonObject["assetId"]!!.jsonPrimitive.long }
        assertEquals(listOf(sunset), ids)

        fotoz_library_close(handle)
    }

    @Test
    fun `query_page reports an error envelope -- not null -- for an unknown album`() = memScoped {
        val handle = openHandle()
        val ast = """{"album":"NOT_A_REAL_ALBUM"}""".cstr.ptr
        val resultPtr = fotoz_query_page(handle, ast, null)
        assertNotNull(resultPtr)
        val obj = Json.parseToJsonElement(resultPtr.toKString()).jsonObject
        fotoz_free_string(resultPtr)
        assertNotNull(obj["error"])
        assertEquals(0, obj["rows"]!!.jsonArray.size)
        fotoz_library_close(handle)
    }

    @Test
    fun `query_page returns null only for an invalid handle`() = memScoped {
        val ast = """{"album":"EVERYDAY"}""".cstr.ptr
        assertNull(fotoz_query_page(null, ast, null))
    }

    // ---- asset_detail() ------------------------------------------------------------------------

    @Test
    fun `asset_detail returns real merged asset and user rows`() = memScoped {
        val handle = openHandle()
        val library = handle.asLibraryHandleOrNull()!!
        val assetId = seedAsset(library, displayName = "loved-one.jpg", favorite = true)

        val resultPtr = fotoz_asset_detail(handle, assetId)!!
        val obj = Json.parseToJsonElement(resultPtr.toKString()).jsonObject
        fotoz_free_string(resultPtr)

        assertEquals("loved-one.jpg", obj["asset"]!!.jsonObject["displayName"]!!.jsonPrimitive.content)
        assertTrue(obj["user"]!!.jsonObject["favorite"]!!.jsonPrimitive.boolean)

        fotoz_library_close(handle)
    }

    @Test
    fun `asset_detail reports not_found for a missing asset -- not null`() = memScoped {
        val handle = openHandle()
        val resultPtr = fotoz_asset_detail(handle, 999_999L)!!
        val obj = Json.parseToJsonElement(resultPtr.toKString()).jsonObject
        fotoz_free_string(resultPtr)
        assertEquals("not_found", obj["error"]!!.jsonPrimitive.content)
        fotoz_library_close(handle)
    }

    @Test
    fun `asset_detail returns null only for an invalid handle`() {
        assertNull(fotoz_asset_detail(null, 1L))
    }

    // ---- set_metadata() ------------------------------------------------------------------------

    @Test
    fun `set_metadata sidecar_name computes the real darktable and lightroom names`() = memScoped {
        val handle = openHandle()
        val darktable = """{"kind":"sidecar_name","originalFileName":"IMG_1234.CR3","style":"DARKTABLE"}""".cstr.ptr
        assertEquals(BridgeStatus.OK, fotoz_set_metadata(handle, darktable))
        fotoz_library_close(handle)
    }

    @Test
    fun `set_metadata strip really strips a synthetic JPEG's Exif GPS segment on disk`() = memScoped {
        val handle = openHandle()
        val input = "$dbDir/in.jpg"
        val output = "$dbDir/out.jpg"
        assertTrue(writeWholeFile(input, syntheticJpegWithExifGps()))

        val request = """{"kind":"strip","inputPath":"$input","outputPath":"$output"}""".cstr.ptr
        assertEquals(BridgeStatus.OK, fotoz_set_metadata(handle, request))

        val stripped = readWholeFile(output)
        assertNotNull(stripped)
        val strippedText = stripped.decodeToString(throwOnInvalidSequence = false)
        assertFalse(strippedText.contains("FAKE-EXIF-WITH-GPS"))

        remove(input)
        remove(output)
        fotoz_library_close(handle)
    }

    @Test
    fun `set_metadata returns NOT_IMPLEMENTED for real XMP field writes -- not a fake success`() = memScoped {
        val handle = openHandle()
        val request = """{"kind":"xmp","originalFileName":"a.jpg"}""".cstr.ptr
        assertEquals(fotoz_status_not_implemented(), fotoz_set_metadata(handle, request))
        fotoz_library_close(handle)
    }

    @Test
    fun `set_metadata reports INVALID_HANDLE and BAD_REQUEST as documented sentinels`() = memScoped {
        val request = """{"kind":"sidecar_name","originalFileName":"a.jpg","style":"DARKTABLE"}""".cstr.ptr
        assertEquals(BridgeStatus.INVALID_HANDLE, fotoz_set_metadata(null, request))

        val handle = openHandle()
        assertEquals(BridgeStatus.BAD_REQUEST, fotoz_set_metadata(handle, "not json".cstr.ptr))
        fotoz_library_close(handle)
    }

    // ---- sync_start() --------------------------------------------------------------------------

    @Test
    fun `sync_start invokes the real C callback exactly once with the documented sentinel`() = memScoped {
        val handle = openHandle()
        lastSyncStatus = Int.MIN_VALUE
        lastSyncMessage = null
        lastSyncInvocations = 0

        val source = """{"kind":"LINUX_DIR"}""".cstr.ptr
        val status = fotoz_sync_start(handle, source, staticCFunction(::recordSyncCallback), null)

        assertEquals(BridgeStatus.NOT_IMPLEMENTED, status)
        assertEquals(1, lastSyncInvocations)
        assertEquals(BridgeStatus.NOT_IMPLEMENTED, lastSyncStatus)
        assertNotNull(lastSyncMessage)
        assertNotNull(Json.parseToJsonElement(lastSyncMessage!!).jsonObject["error"])

        fotoz_library_close(handle)
    }

    @Test
    fun `sync_start reports INVALID_HANDLE without ever invoking the callback`() = memScoped {
        lastSyncInvocations = 0
        val source = """{"kind":"LINUX_DIR"}""".cstr.ptr
        val status = fotoz_sync_start(null, source, staticCFunction(::recordSyncCallback), null)
        assertEquals(BridgeStatus.INVALID_HANDLE, status)
        assertEquals(0, lastSyncInvocations)
    }

    // ---- ops_execute() -------------------------------------------------------------------------

    @Test
    fun `ops_execute is a documented stub -- no operation engine exists yet`() = memScoped {
        val handle = openHandle()
        val op = """{"op":"move"}""".cstr.ptr
        assertEquals(fotoz_status_not_implemented(), fotoz_ops_execute(handle, op))
        fotoz_library_close(handle)
    }

    @Test
    fun `ops_execute reports INVALID_HANDLE and BAD_REQUEST as documented sentinels`() = memScoped {
        val op = """{"op":"move"}""".cstr.ptr
        assertEquals(BridgeStatus.INVALID_HANDLE, fotoz_ops_execute(null, op))

        val handle = openHandle()
        assertEquals(BridgeStatus.BAD_REQUEST, fotoz_ops_execute(handle, "not json".cstr.ptr))
        fotoz_library_close(handle)
    }

    // ---- status accessors match the constants this test asserts against directly -------------

    @Test
    fun `status accessor functions match BridgeStatus's own constants`() {
        assertEquals(BridgeStatus.OK, fotoz_status_ok())
        assertEquals(BridgeStatus.INVALID_HANDLE, fotoz_status_invalid_handle())
        assertEquals(BridgeStatus.BAD_REQUEST, fotoz_status_bad_request())
        assertEquals(BridgeStatus.NOT_FOUND, fotoz_status_not_found())
        assertEquals(BridgeStatus.NOT_IMPLEMENTED, fotoz_status_not_implemented())
        assertEquals(BridgeStatus.IO_ERROR, fotoz_status_io_error())
        assertEquals(BridgeStatus.INTERNAL_ERROR, fotoz_status_internal_error())
    }
}

/** A minimal, real JPEG (SOI + APP0 + an Exif segment carrying a GPS-shaped marker string + DQT +
 *  DHT + SOF0 + SOS + entropy data + EOI) -- the same construction
 *  `core/metadata/.../MetadataStripperTest.kt` uses, built fresh here so this module's own test
 *  does not need a cross-module test dependency for one small fixture. */
private fun syntheticJpegWithExifGps(): ByteArray {
    fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), ((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte()) + payload
    }
    val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    val app0 = segment(0xE0, "JFIF\u0000".encodeToByteArray() + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0))
    val exifGps = segment(0xE1, "Exif\u0000\u0000FAKE-EXIF-WITH-GPS".encodeToByteArray())
    val dqt = segment(0xDB, ByteArray(9))
    val dht = segment(0xC4, byteArrayOf(0, 1, 2, 3))
    val sof0 = segment(0xC0, byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0))
    val sos = segment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0))
    val entropyData = byteArrayOf(0x12, 0x34, 0x56)
    val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    return soi + app0 + exifGps + dqt + dht + sof0 + sos + entropyData + eoi
}

// `staticCFunction` requires a top-level function with no captured state -- these three vars are
// this test file's own recording side-channel for [recordSyncCallback], not shared/production
// state.
private var lastSyncStatus: Int = Int.MIN_VALUE
private var lastSyncMessage: String? = null
private var lastSyncInvocations: Int = 0

private fun recordSyncCallback(status: Int, message: CPointer<ByteVar>?, userData: COpaquePointer?) {
    lastSyncInvocations += 1
    lastSyncStatus = status
    lastSyncMessage = message?.toKString()
}
