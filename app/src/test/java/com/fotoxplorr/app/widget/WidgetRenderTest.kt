package com.fotoxplorr.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import com.fotoxplorr.app.FotoXplorrActivity
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.widget.WidgetFixtures.asset
import com.fotoxplorr.app.widget.WidgetFixtures.collection
import com.fotoxplorr.app.widget.WidgetFixtures.folderKey
import com.fotoxplorr.app.widget.WidgetFixtures.library
import com.fotoxplorr.app.widget.WidgetFixtures.millis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Every widget layout, inflated the way a launcher inflates it (`RemoteViews.apply`, which refuses
 * any view class a RemoteViews may not hold), at each of its sizes, in day and night, drawn to PNG
 * under `build/renders/widgets/` (TRAPS #30: render a layout before believing it). Pictures are
 * flat coloured stand-ins; the launcher, the phone and resizing are owner-verify items.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class WidgetRenderTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = WidgetFixtures.ZONE
    private val now = millis(2026, 11, 4, hour = 10)

    private val camera = (1L..9L).map { asset(it, millis(2026, 10, 20 + it.toInt(), hour = 9 + it.toInt())) }
    private val lastYear = asset(20, millis(2025, 11, 4, hour = 8))
    private val earlier = listOf(
        asset(21, millis(2023, 11, 4)),
        asset(22, millis(2021, 11, 4), video = true),
        asset(23, millis(2019, 11, 4)),
        asset(24, millis(2018, 11, 4)),
    )
    private val sensitiveOnThisDay = asset(30, millis(2024, 11, 4))
    private val protectedPhoto = asset(31, millis(2022, 11, 4), folder = "Private")
    private val screenshot = asset(32, millis(2026, 9, 1), folder = "Screenshots")
    private val passport = asset(33, millis(2026, 10, 30, hour = 20))

    /** Last read from the phone yesterday afternoon: the widgets must say so, with the date. */
    private val readAt = millis(2026, 11, 3, hour = 14)

    private val fullLibrary = library(
        assets = camera + lastYear + earlier + sensitiveOnThisDay + protectedPhoto + screenshot + passport,
        favoriteIds = setOf(3, 5, 20, 30, 31, 33),
        sensitiveIds = setOf(30),
        lockedFolders = setOf(folderKey(protectedPhoto)),
        identityIds = setOf(33),
        collections = listOf(
            collection("a", "Autumn walks", setOf(1, 2, 3, 4)),
            collection("b", "Divorce evidence", setOf(20, 21)),
            collection("c", "Only private things", setOf(30, 31)),
            collection("d", "Desk setup", setOf(32)),
            collection("e", "Visa papers", setOf(33)),
        ),
        libraryReadAtMillis = readAt,
    )

    /** 21,486 items: the widest counts the stat and progress layouts are likely to print. */
    private val bigLibrary by lazy {
        library(
            assets = (1L..21_486L).map { asset(it, millis(2024, 6, 1), video = it % 13L == 0L) },
            favoriteIds = (1L..214L).toSet(),
            libraryReadAtMillis = readAt,
        )
    }

    private fun renderer(withImages: Boolean = true) = WidgetRenderer(
        context = context,
        pictures = { asset, edge -> stubPicture(asset, edge) },
        budgetBytes = bitmapBudgetBytes(1080, 2400),
        nowMillis = now,
        zone = zone,
        withImages = withImages,
    )

    @Test
    fun `every widget renders at every size, day and night`() {
        for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
            val mode = if (night) "night" else "day"
            for (kind in WidgetKind.entries) {
                val views = render(kind, widgetId = 7 + kind.ordinal) as WidgetViews.Sized
                assertEquals(WidgetSize.entries.toSet(), views.bySize.keys)
                for ((size, remote) in views.bySize) {
                    val root = applyAndDraw(remote, kind, size, "${kind.name.lowercase()}-${size.name.lowercase()}-$mode")
                    assertTrue("$kind $size drew no text", texts(root).isNotEmpty())
                }
            }
        }
    }

    @Test
    fun `every widget still inflates and draws at a 1_3x font scale`() {
        RuntimeEnvironment.setFontScale(1.3f)
        try {
            for (kind in WidgetKind.entries) {
                val views = render(kind, widgetId = 20 + kind.ordinal) as WidgetViews.Sized
                for ((size, remote) in views.bySize) {
                    val root = applyAndDraw(remote, kind, size, "${kind.name.lowercase()}-${size.name.lowercase()}-font130")
                    assertTrue("$kind $size drew no text", texts(root).isNotEmpty())
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun `widgets say what they show in words`() {
        val favourite = textsOf(WidgetKind.FAVOURITE, WidgetSize.MEDIUM)
        assertTrue(favourite.contains("★ A favourite"))
        assertTrue(favourite.any { it.endsWith("favourites") })

        val onThisDay = textsOf(WidgetKind.ON_THIS_DAY, WidgetSize.MEDIUM)
        assertTrue(onThisDay.contains("On this day"))
        // The sensitive 2024 and protected 2022 photos taken on this day never appear.
        assertTrue(onThisDay.containsAll(listOf("2025", "2023", "2021", "2019")))
        assertTrue(onThisDay.none { it == "2024" || it == "2022" })
        assertTrue(onThisDay.any { it.endsWith("5 items") })

        val library = textsOf(WidgetKind.LIBRARY, WidgetSize.LARGE)
        // 9 camera + 5 on this day + 1 screenshot + 1 passport; the sensitive and protected photos
        // are not counted. Only "Autumn walks" and "Divorce evidence" have a picture the
        // Collections widget may draw, so Library counts 2 collections, as that widget shows.
        assertTrue(library.contains("16 items"))
        assertTrue(library.containsAll(listOf("15", "1", "4", "2")))
        assertTrue(library.containsAll(listOf("Photos", "Videos", "Favourites", "Screenshots", "Albums", "Collections")))
        assertTrue(library.any { it.startsWith("As of ") && it.contains("Nov") && it.endsWith("aren't counted.") })

        val collections = textsOf(WidgetKind.COLLECTIONS, WidgetSize.LARGE)
        // Typed names never reach the home screen; rows say the months their pictures span.
        val names = listOf("Autumn walks", "Divorce evidence", "Only private things", "Desk setup", "Visa papers")
        assertTrue(collections.none { text -> names.any { it in text } })
        assertTrue(collections.contains("2 collections"))
        assertTrue(collections.any { it.contains("Oct") && it.contains("2026") })
        assertTrue(collections.any { it.contains("2023") && it.contains("2025") })

        val indexing = textsOf(WidgetKind.INDEXING, WidgetSize.SMALL)
        assertTrue(indexing.contains("73%"))
    }

    @Test
    fun `stale data says when the library was last read, with the date when it was not today`() {
        val recent = textsOf(WidgetKind.RECENT, WidgetSize.MEDIUM)
        assertTrue(recent.any { it.startsWith("As of ") && it.contains("Nov") })
        val small = textsOf(WidgetKind.RECENT, WidgetSize.SMALL).single()
        assertTrue(small.startsWith("Latest · ") && small.contains("\nAs of "))
        assertTrue(textsOf(WidgetKind.LIBRARY, WidgetSize.MEDIUM).any { it.startsWith("As of ") && it.contains("Nov") })
        assertTrue(textsOf(WidgetKind.INDEXING, WidgetSize.MEDIUM).any { it.startsWith("As of ") && it.contains("Nov") })
        // Never read since this was installed: says so rather than printing the drawing time.
        val unknown = render(WidgetKind.RECENT, 6, fullLibrary.copy(libraryReadAtMillis = null)) as WidgetViews.Sized
        assertTrue(texts(applyAndDraw(unknown.bySize.getValue(WidgetSize.MEDIUM), WidgetKind.RECENT, WidgetSize.MEDIUM, null)).contains("As of: unknown"))
        // This year's date leaves the year out, so the line stays short.
        assertTrue(recent.none { it.startsWith("As of ") && it.contains("2026") })
        // Read earlier today: the time alone.
        val today = render(WidgetKind.RECENT, 6, fullLibrary.copy(libraryReadAtMillis = now - 60 * 60 * 1000L)) as WidgetViews.Sized
        val todayMeta = texts(applyAndDraw(today.bySize.getValue(WidgetSize.MEDIUM), WidgetKind.RECENT, WidgetSize.MEDIUM, null))
        assertTrue(todayMeta.any { it.startsWith("As of ") && !it.contains("Nov") })
    }

    @Test
    fun `no collection name reaches TalkBack either`() {
        val views = render(WidgetKind.COLLECTIONS, 8) as WidgetViews.Sized
        val root = applyAndDraw(views.bySize.getValue(WidgetSize.LARGE), WidgetKind.COLLECTIONS, WidgetSize.LARGE, null)
        val descriptions = descriptions(root)
        assertTrue(descriptions.any { it.startsWith("Collection of photos from ") })
        val names = listOf("Autumn walks", "Divorce evidence", "Only private things", "Desk setup", "Visa papers")
        assertTrue(descriptions.none { text -> names.any { it in text } })
    }

    /**
     * Every layout at exactly the smallest size it is used at, at each of Android's font sizes from
     * 1x to 2x: nothing is clipped, no line of text is cut, no picture is squeezed below a usable
     * size. Run on API 31, which scales text linearly with the font size (API 34 grows large text
     * less), so it is the stricter case.
     */
    @Test
    @Config(sdk = [31])
    fun `every layout fits at its own minimum size at every font size from 1x to 2x`() {
        val failures = mutableListOf<String>()
        try {
            for (fs in LayoutMinimum.FONT_STEPS) {
                RuntimeEnvironment.setFontScale(fs)
                // The fixture library, and one with five-digit counts for the widgets that print them.
                val cases = WidgetKind.entries.map { it to fullLibrary } +
                    listOf(WidgetKind.LIBRARY, WidgetKind.INDEXING).map { it to bigLibrary }
                for ((kind, lib) in cases) {
                    val views = render(kind, 30 + kind.ordinal, lib) as WidgetViews.Sized
                    for ((size, remote) in views.bySize) {
                        val (w, h) = kind.thresholds.minimumFor(size, fs)
                        val problems = WidgetLayoutFit.problems(WidgetLayoutFit.layOut(context, remote, w, h))
                        if (problems.isNotEmpty()) failures += "$kind $size at ${fs}x (${w}x$h dp, ${lib.assets.size} items): $problems"
                    }
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `every non-content state renders in words and opens the app`() {
        val states = mapOf(
            "no-access" to render(WidgetKind.FAVOURITE, 3, library(camera, hasMediaAccess = false)),
            "empty" to render(WidgetKind.FAVOURITE, 3, library(camera)),
            "withheld" to render(WidgetKind.FAVOURITE, 3, library(camera, favoriteIds = setOf(1), sensitiveIds = setOf(1))),
            "error" to renderer().error(3),
        )
        val texts = states.mapValues { (name, views) ->
            texts(applyAndDraw((views as WidgetViews.Single).views, WidgetKind.FAVOURITE, WidgetSize.MEDIUM, "state-$name"))
        }
        assertTrue(texts.getValue("no-access").contains("Photos not available"))
        assertTrue(texts.getValue("error").contains("Couldn't load"))
        // Withheld reads exactly like empty: nothing tells a passer-by that hidden favourites exist.
        assertEquals(texts.getValue("empty"), texts.getValue("withheld"))
        assertNotNull(PendingIntent.getActivity(context, widgetRequestCode(3, 0), launcherIntent(), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
    }

    @Test
    fun `a tile tap is an explicit, immutable intent carrying only visible ids`() {
        val widgetId = 41
        render(WidgetKind.ON_THIS_DAY, widgetId)
        val pending = PendingIntent.getActivity(
            context,
            widgetRequestCode(widgetId, 1),
            Intent(context, FotoXplorrActivity::class.java).setAction(WidgetLaunch.ACTION_OPEN_FROM_WIDGET),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        assertNotNull(pending)
        val shadow = shadowOf(pending)
        assertTrue(shadow.isActivityIntent)
        assertTrue(shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val intent = shadow.savedIntent
        assertEquals(FotoXplorrActivity::class.java.name, intent.component?.className)
        assertEquals(lastYear.id.value, intent.getLongExtra(WidgetLaunch.EXTRA_MEDIA_ID, -1))
        val run = intent.getLongArrayExtra(WidgetLaunch.EXTRA_RUN_IDS)!!.toList()
        assertEquals(listOf(20L, 21L, 22L, 23L, 24L), run)
        assertTrue(sensitiveOnThisDay.id.value !in run && protectedPhoto.id.value !in run)
        // No uri, no file name: only ids, which the Activity re-checks before opening anything.
        assertNull(intent.data)
        assertEquals(setOf(WidgetLaunch.EXTRA_MEDIA_ID, WidgetLaunch.EXTRA_RUN_IDS), intent.extras!!.keySet())
    }

    @Test
    fun `on API 31 and up one RemoteViews carries all three layouts and inflates`() {
        val delivered = (render(WidgetKind.LIBRARY, 12) as WidgetViews.Sized).toRemoteViews(WidgetKind.LIBRARY, android.os.Bundle())
        assertTrue(texts(delivered.apply(context, FrameLayout(context))).isNotEmpty())
    }

    @Test
    @Config(sdk = [26])
    fun `below API 31 the layout follows the reported size in each orientation`() {
        // A 4x3 widget as an older launcher reports it: 276 x 337 in portrait, 557 x 160 in landscape.
        val options = android.os.Bundle().apply {
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 276)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 557)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 337)
        }
        val delivered = (render(WidgetKind.LIBRARY, 13) as WidgetViews.Sized).toRemoteViews(WidgetKind.LIBRARY, options)
        RuntimeEnvironment.setQualifiers("+port")
        val portrait = texts(delivered.apply(context, FrameLayout(context)))
        assertTrue("portrait gets the large layout", portrait.any { it.endsWith("aren't counted.") })
        RuntimeEnvironment.setQualifiers("+land")
        val landscape = texts(delivered.apply(context, FrameLayout(context)))
        assertTrue("landscape gets the medium layout", landscape.none { it.endsWith("aren't counted.") })
        assertTrue(landscape.contains("Favourites"))
    }

    @Test
    fun `a refused update can be redrawn with placeholders and the same words`() {
        val withPictures = textsOf(WidgetKind.RECENT, WidgetSize.LARGE)
        val plain = (runBlocking { renderer(withImages = false).render(WidgetKind.RECENT, 9, fullLibrary, null) } as WidgetViews.Sized)
        val root = applyAndDraw(plain.bySize.getValue(WidgetSize.LARGE), WidgetKind.RECENT, WidgetSize.LARGE, "recent-large-placeholders")
        assertEquals(withPictures, texts(root))
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun render(kind: WidgetKind, widgetId: Int, lib: WidgetLibrary = fullLibrary): WidgetViews = runBlocking {
        val indexing = if (kind == WidgetKind.INDEXING) {
            // 10 of the 14 visible photos indexed, the one visible video not yet scanned (stand-in numbers).
            indexingState(lib, photosPending = 4, videosScanned = 0, activity = IndexingActivity.Waiting("Not charging (rule: only while charging, and only at night)", readAt))
        } else {
            null
        }
        renderer().render(kind, widgetId, lib, indexing)
    }

    private fun textsOf(kind: WidgetKind, size: WidgetSize): List<String> {
        val views = render(kind, 5) as WidgetViews.Sized
        return texts(applyAndDraw(views.bySize.getValue(size), kind, size, null))
    }

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClass(context, FotoXplorrActivity::class.java)

    /**
     * The size each layout is drawn at for the PNGs and the word checks: a typical portrait cell
     * size for that layout, or its own minimum at the current font size if that is larger.
     */
    private fun sizeDp(kind: WidgetKind, size: WidgetSize): Pair<Float, Float> {
        val typical = when (size) {
            WidgetSize.SMALL -> 150f to 160f
            WidgetSize.MEDIUM -> 300f to 220f
            WidgetSize.LARGE -> 300f to 340f
        }
        val (minW, minH) = kind.thresholds.minimumFor(size, context.resources.configuration.fontScale)
        return maxOf(typical.first, minW) to maxOf(typical.second, minH)
    }

    private fun applyAndDraw(views: RemoteViews, kind: WidgetKind, size: WidgetSize, name: String?): View {
        val (wDp, hDp) = sizeDp(kind, size)
        val root = WidgetLayoutFit.layOut(context, views, wDp, hDp)
        val parent = root.parent as View
        val w = parent.width
        val h = parent.height
        if (name != null) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(0x60, 0x60, 0x66)) // a stand-in wallpaper
            parent.draw(canvas)
            val out = File("build/renders/widgets/$name.png").apply { parentFile?.mkdirs() }
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return root
    }

    private fun descriptions(view: View): List<String> = when {
        view.visibility != View.VISIBLE -> emptyList()
        else -> listOfNotNull(view.contentDescription?.toString()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descriptions(view.getChildAt(it)) } else emptyList()
    }

    private fun texts(view: View): List<String> = when {
        view.visibility != View.VISIBLE -> emptyList()
        view is TextView -> listOfNotNull(view.text?.toString()?.takeIf { it.isNotBlank() })
        view is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    /** A flat colour per item, so tiles are told apart in the renders; no real photo is involved. */
    private fun stubPicture(asset: MediaAsset, edge: Int): Bitmap {
        val w = edge
        val h = (edge * 0.75f).roundToInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val hue = (asset.id.value * 47 % 360).toFloat()
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.75f)))
        canvas.drawCircle(w * 0.7f, h * 0.35f, h * 0.15f, Paint().apply { color = Color.argb(150, 255, 255, 255) })
        return bitmap
    }
}
