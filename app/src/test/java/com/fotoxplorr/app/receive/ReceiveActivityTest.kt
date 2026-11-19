package com.fotoxplorr.app.receive

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import com.fotoxplorr.app.board.BoardMediaProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * [ReceiveActivity] started the way another app starts it: a SEND_MULTIPLE intent with a stream list.
 * The sender and MediaStore are stand-ins (see [ReceiveTestKit]); the drawing is not asserted, only
 * what the view model decided, which is what the sheet shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceiveActivityTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun attach() {
        BoardMediaProvider.reset(context.cacheDir)
        ReceiveSenderProvider.reset(context.cacheDir)
        Robolectric.buildContentProvider(BoardMediaProvider::class.java).create(MediaStore.AUTHORITY)
        Robolectric.buildContentProvider(ReceiveSenderProvider::class.java).create(ReceiveSenderProvider.AUTHORITY)
    }

    private fun share(vararg names: String, type: String = "image/*"): Intent = shareUris(names.map(ReceiveSenderProvider::uri), type)

    private fun shareUris(uris: List<Uri>, type: String = "image/*") = Intent(context, ReceiveActivity::class.java).apply {
        action = Intent.ACTION_SEND_MULTIPLE
        this.type = type
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        clipData = ClipData.newRawUri("", uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
    }

    private fun start(intent: Intent): Pair<org.robolectric.android.controller.ActivityController<ReceiveActivity>, ReceiveViewModel> {
        val controller = Robolectric.buildActivity(ReceiveActivity::class.java, intent).setup()
        return controller to ViewModelProvider(controller.get())[ReceiveViewModel::class.java]
    }

    private fun awaitStage(model: ReceiveViewModel, done: (ReceiveStage) -> Boolean): ReceiveStage {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done(model.stage.value)) return model.stage.value
            Thread.sleep(25)
        }
        error("stage never reached: ${model.stage.value}")
    }

    private fun awaitIdleSaving(model: ReceiveViewModel) {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!model.saving.value && model.stage.value is ReceiveStage.Saving) return
            Thread.sleep(25)
        }
        error("saving never ended")
    }

    @Test
    fun `two photos and one unreadable one make a choice of two with the unreadable one counted`() {
        val (_, model) = start(share("a.jpg", "b.jpg", "missing.jpg"))
        val stage = awaitStage(model) { it is ReceiveStage.Choice } as ReceiveStage.Choice
        assertEquals(listOf("a.jpg", "b.jpg"), stage.plan.items.map { it.displayName })
        assertEquals(1, stage.plan.unreadable)
        assertEquals("2 photos were shared with you.", ReceiveCopy.title(stage.plan))
        assertEquals("1 shared file could not be used.", ReceiveCopy.leftOut(stage.plan))
    }

    @Test
    fun `six hundred items are cut to five hundred with the rest counted`() {
        val (_, model) = start(shareUris((1..600).map { ReceiveSenderProvider.uri("p$it.jpg") }))
        val stage = awaitStage(model) { it is ReceiveStage.Choice } as ReceiveStage.Choice
        assertEquals(500, stage.plan.items.size)
        assertEquals(100, stage.plan.overCap)
        assertTrue(ReceiveCopy.leftOut(stage.plan)!!.startsWith("Only the first 500"))
    }

    @Test
    fun `a share of nothing usable says so and offers nothing to save`() {
        val (_, model) = start(share("missing1.jpg", "doc.pdf", type = "*/*"))
        val stage = awaitStage(model) { it !is ReceiveStage.Preparing }
        assertTrue(stage is ReceiveStage.NothingUsable)
    }

    @Test
    fun `a file scheme uri is refused even when the sender names a real file`() {
        val file = File(context.cacheDir, "evil.jpg").apply { writeText("x") }
        val (_, model) = start(shareUris(listOf(ReceiveSenderProvider.uri("ok.jpg"), Uri.fromFile(file))))
        val stage = awaitStage(model) { it is ReceiveStage.Choice } as ReceiveStage.Choice
        assertEquals(1, stage.plan.items.size)
        assertEquals(1, stage.plan.unreadable)
    }

    @Test
    fun `an intent with another action or no stream finishes without a sheet`() {
        val wrong = Intent(context, ReceiveActivity::class.java).apply { action = Intent.ACTION_SEND_MULTIPLE; type = "image/*" }
        val c = Robolectric.buildActivity(ReceiveActivity::class.java, wrong).setup()
        assertTrue(c.get().isFinishing)
        val send = Intent(context, ReceiveActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(ReceiveSenderProvider.uri("a.jpg")))
        }
        assertTrue(Robolectric.buildActivity(ReceiveActivity::class.java, send).setup().get().isFinishing)
    }

    @Test
    fun `view is the default and keeps nothing`() {
        val (_, model) = start(share("a.jpg", "b.jpg"))
        awaitStage(model) { it is ReceiveStage.Choice }
        model.view()
        assertTrue(model.stage.value is ReceiveStage.Viewing)
        assertTrue("viewing writes no MediaStore row", BoardMediaProvider.rows.isEmpty())
    }

    @Test
    fun `save writes new unique files and touches no store but MediaStore`() {
        val (controller, model) = start(share("a.jpg", "a.jpg2.jpg", "b.jpg"))
        BoardMediaProvider.seed("a.jpg", "Pictures/Fotoz/")
        awaitStage(model) { it is ReceiveStage.Choice }
        val before = appStores()
        model.save()
        awaitIdleSaving(model)
        val names = BoardMediaProvider.rows.values.map { it.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) }
        assertEquals(listOf("a.jpg", "a-2.jpg", "a.jpg2.jpg", "b.jpg"), names)
        assertFalse(model.cancelled.value)
        val progress = (model.stage.value as ReceiveStage.Saving).session.progress.value
        assertEquals(3, progress.saved)
        assertEquals("Saved 3 photos and videos.", ReceiveCopy.summary(progress, cancelled = false))
        assertEquals("no preference file, database or other store changed", before, appStores())
        controller.destroy()
    }

    /** Every file the app keeps outside MediaStore, by name and content: preference files and databases. */
    private fun appStores(): Map<String, String> {
        val root = context.applicationInfo.dataDir
        return listOf("shared_prefs", "databases", "files").flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.isFile && !it.name.endsWith("-journal") }.map { f ->
                "$dir/${f.name}" to f.readBytes().contentHashCode().toString()
            }.toList()
        }.toMap()
    }
}
