package com.fotoxplorr.app.board

import com.fotoxplorr.app.board.BoardTestKit.identity
import com.fotoxplorr.app.board.BoardTestKit.layer
import com.fotoxplorr.app.board.BoardTestKit.photo
import com.fotoxplorr.app.editor.CropRect
import com.fotoxplorr.app.lift.BrushStroke
import com.fotoxplorr.app.lift.NormPoint
import com.fotoxplorr.app.lift.SelectionOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardJsonTest {

    private fun rich(): Board {
        val poly = MaskShape.Polygon(listOf(NormPoint(0.1f, 0.1f), NormPoint(0.9f, 0.2f), NormPoint(0.5f, 0.95f)))
        val ops = listOf(
            SelectionOp.Hint(true, BrushStroke(listOf(NormPoint(0.4f, 0.4f), NormPoint(0.5f, 0.5f)), 0.03f)),
            SelectionOp.Hint(false, BrushStroke(listOf(NormPoint(0.05f, 0.05f)), 0.02f)),
            SelectionOp.Paint(true, BrushStroke(listOf(NormPoint(0.6f, 0.6f)), 0.01f)),
        )
        return Board(
            canvas = CanvasPreset.PORTRAIT_4_5.size,
            background = BoardBackground.Colour(0xFF123456.toInt()),
            nextLayerId = 12,
            layers = listOf(
                layer(1, 540f, 600f, w = 600f, h = 450f, rot = 12.5f, scale = 1.25f, content = photo(42), crop = CropRect(0.1f, 0.2f, 0.9f, 0.8f), opacity = 0.75f, blend = LayerBlend.MULTIPLY, mask = LayerMask(MaskShape.RoundedRect(0.25f), 0.05f)),
                layer(2, 100f, 100f, content = LayerContent.Colour(0x80FF8800.toInt()), locked = true, mask = LayerMask(MaskShape.Ellipse)),
                layer(3, 200f, 300f, content = LayerContent.Text("Hello\n\"quoted\" \\ and ünï", argb = BoardTestKit.BLUE, sizeUnits = 48f, bold = true, align = TextAlign.CENTRE)),
                layer(5, 300f, 400f, content = LayerContent.Shape(ShapeKind.TRIANGLE, BoardTestKit.RED, BoardTestKit.GREEN, 6f), mask = LayerMask(poly)),
                layer(9, 500f, 500f, content = photo(77, identity = null), mask = LayerMask(MaskShape.Subject(ops))),
                layer(11, 540f, 700f, content = photo(5), mask = LayerMask(MaskShape.Full, 0.2f), blend = LayerBlend.OVERLAY),
            ),
        ).let { it.copy(layers = it.layers.map { l -> if (l.id == 5L) l.copy(blend = LayerBlend.SCREEN) else l }) }
    }

    @Test
    fun `a board with every kind of layer and mask reads back equal`() {
        val board = rich()
        val decoded = BoardJson.decode(BoardJson.encode(board))!!
        assertEquals(board, decoded.board)
        assertTrue(decoded.isComplete)
    }

    @Test
    fun `writing what was read gives the same text`() {
        val text = BoardJson.encode(rich())
        assertEquals(text, BoardJson.encode(BoardJson.decode(text)!!.board))
    }

    @Test
    fun `a transparent background and an empty board round trip`() {
        val board = Board(background = BoardBackground.Transparent)
        assertEquals(board, BoardJson.decode(BoardJson.encode(board))!!.board)
    }

    @Test
    fun `the identity of a photo survives, which is how it is found again`() {
        val board = Board(layers = listOf(layer(1, 0f, 0f, content = photo(3, identity = identity("a b.jpg", 123456789012L, 99L, "DCIM/x/")))))
        val ref = (BoardJson.decode(BoardJson.encode(board))!!.board.layers.single().content as LayerContent.Photo).ref
        assertEquals("a b.jpg", ref.identity!!.displayName)
        assertEquals(123456789012L, ref.identity!!.sizeBytes)
        assertEquals("DCIM/x/", ref.identity!!.relativePath)
    }

    @Test
    fun `a photo that is not in the library any more is not a read error`() {
        // The codec has no library to ask: a reference to a photo nobody has is just a reference.
        val text = BoardJson.encode(Board(layers = listOf(layer(1, 0f, 0f, content = photo(999_999)))))
        val decoded = BoardJson.decode(text)!!
        assertEquals(1, decoded.board.layers.size)
        assertTrue(decoded.isComplete)
    }

    @Test
    fun `keys it does not know are ignored at every level, and a newer schema is flagged`() {
        val text = """
            {"kind":"fotoz-board","schema":7,"future":{"a":[1,2]},"canvas":{"w":1080,"h":1080,"dpi":300},
             "background":{"type":"gradient","argb":-1,"stops":[1,2]},"next":3,"extra":true,
             "layers":[{"id":1,"type":"colour","argb":-65536,"w":100,"h":100,"x":50,"y":60,"tilt":4,"shadow":{"r":2},
               "mask":{"shape":"ellipse","feather":0.1,"invert":true}}]}
        """.trimIndent()
        val d = BoardJson.decode(text)!!
        assertTrue(d.fromNewerVersion)
        assertFalse(d.isComplete)
        val l = d.board.layers.single()
        assertEquals(LayerContent.Colour(BoardTestKit.RED), l.content)
        assertEquals(MaskShape.Ellipse, l.mask.shape)
        assertEquals(0.1f, l.mask.feather, 0f)
        assertEquals(Transform(50f, 60f), l.transform)
        // An unknown background kind is the plain white one.
        assertEquals(BoardBackground.Colour(BoardTestKit.WHITE), d.board.background)
    }

    @Test
    fun `text that is not a board gives nothing`() {
        for (bad in listOf("", "   ", "not json", "[]", "{}", "null", "42", "\"fotoz-board\"", """{"kind":"other","layers":[]}""", """{"kind":"fotoz-board","layers":""", "{")) {
            assertNull("[$bad]", BoardJson.decode(bad))
        }
    }

    @Test
    fun `a file cut short anywhere gives nothing or a board, never a crash`() {
        val text = BoardJson.encode(rich())
        for (n in 0 until text.length step 7) {
            val d = BoardJson.decode(text.substring(0, n))
            if (d != null) assertTrue(d.board.layers.size <= 6)
        }
        assertNotNull(BoardJson.decode(text))
    }

    @Test
    fun `very deep nesting and very long text are refused without a crash`() {
        assertNull(BoardJson.decode("[".repeat(200_000)))
        assertNull(BoardJson.decode(" ".repeat(BoardJson.MAX_CHARS + 1)))
    }

    @Test
    fun `layers it cannot read are skipped and counted, the rest load`() {
        val text = """
            {"kind":"fotoz-board","schema":1,"layers":[
              7, "x", null, [],
              {"type":"colour","w":10,"h":10},
              {"id":0,"type":"colour","w":10,"h":10},
              {"id":-4,"type":"colour","w":10,"h":10},
              {"id":2,"type":"hologram","w":10,"h":10},
              {"id":3,"type":"colour","h":10},
              {"id":4,"type":"photo","w":10,"h":10},
              {"id":5,"type":"photo","photo":{"id":"abc"},"w":10,"h":10},
              {"id":6,"type":"colour","argb":-16711936,"w":100,"h":100,"x":10,"y":10}
            ]}
        """.trimIndent()
        val d = BoardJson.decode(text)!!
        assertEquals(listOf(6L), d.board.layers.map { it.id })
        assertEquals(11, d.skippedLayers)
        assertFalse(d.isComplete)
    }

    @Test
    fun `numbers are held to the limits`() {
        val text = """
            {"kind":"fotoz-board","schema":1,"canvas":{"w":99999,"h":-5},"layers":[
              {"id":1,"type":"colour","w":-5,"h":1e9,"x":1e9,"y":-1e9,"scale":0,"rot":725,"opacity":7,"blend":"nope"},
              {"id":2,"type":"text","text":"hi","size":0.001,"w":100,"h":50},
              {"id":3,"type":"shape","shape":"star","strokeW":99999,"w":100,"h":50}
            ]}
        """.trimIndent()
        val d = BoardJson.decode(text)!!
        assertEquals(CanvasSize(BoardLimits.MAX_CANVAS_EDGE, BoardLimits.MIN_CANVAS_EDGE), d.board.canvas)
        val a = d.board.layers[0]
        assertEquals(BoardLimits.MIN_BOX, a.width, 0f)
        assertEquals(BoardLimits.MAX_BOX, a.height, 0f)
        assertEquals(BoardLimits.MIN_SCALE, a.transform.scale, 0f)
        assertEquals(5f, a.transform.rotationDeg, 0.001f)
        assertEquals(1f, a.opacity, 0f)
        assertEquals(LayerBlend.NORMAL, a.blend)
        assertTrue(a.transform.cx <= 4096f * 5 + 1 && a.transform.cy >= -64f * 5 - 1)
        assertEquals(4f, (d.board.layers[1].content as LayerContent.Text).sizeUnits, 0f)
        val shape = d.board.layers[2].content as LayerContent.Shape
        assertEquals(ShapeKind.RECTANGLE, shape.kind)
        assertEquals(500f, shape.strokeUnits, 0f)
    }

    @Test
    fun `layers with the same id are numbered apart, and the next id is above them all`() {
        val text = """
            {"kind":"fotoz-board","schema":1,"next":2,"layers":[
              {"id":4,"type":"colour","w":10,"h":10},{"id":4,"type":"colour","w":10,"h":10},{"id":4,"type":"colour","w":10,"h":10}
            ]}
        """.trimIndent()
        val b = BoardJson.decode(text)!!.board
        assertEquals(3, b.layers.map { it.id }.distinct().size)
        assertTrue(b.nextLayerId > b.layers.maxOf { it.id })
        assertEquals(4L, b.layers[0].id)
    }

    @Test
    fun `no more than the limit of layers are read`() {
        val layers = (1..(BoardLimits.MAX_LAYERS + 5)).joinToString(",") { """{"id":$it,"type":"colour","w":10,"h":10}""" }
        val d = BoardJson.decode("""{"kind":"fotoz-board","schema":1,"layers":[$layers]}""")!!
        assertEquals(BoardLimits.MAX_LAYERS, d.board.layers.size)
        assertEquals(5, d.skippedLayers)
    }

    @Test
    fun `masks it cannot read are dropped and counted, the layer stays`() {
        val text = """
            {"kind":"fotoz-board","schema":1,"layers":[
              {"id":1,"type":"colour","w":10,"h":10,"mask":{"shape":"swirl"}},
              {"id":2,"type":"colour","w":10,"h":10,"mask":{"shape":"polygon","points":[0,0,1,1]}},
              {"id":3,"type":"colour","w":10,"h":10,"mask":"ellipse"},
              {"id":4,"type":"colour","w":10,"h":10,"mask":{"shape":"polygon","points":[0,0,1,0,1,1,"x",3]}},
              {"id":5,"type":"colour","w":10,"h":10,"mask":{"shape":"rounded","radius":9,"feather":9}}
            ]}
        """.trimIndent()
        val d = BoardJson.decode(text)!!
        assertEquals(5, d.board.layers.size)
        assertTrue(d.board.layers.take(4).all { it.mask.isNone })
        assertEquals(3, d.droppedMasks)
        assertEquals(MaskShape.RoundedRect(0.5f), d.board.layers[4].mask.shape)
        assertEquals(0.5f, d.board.layers[4].mask.feather, 0f)
    }

    @Test
    fun `a polygon with too many points keeps the first ones and a stroke keeps its limit`() {
        val pts = (0 until 200).joinToString(",") { "0.5,0.5" }
        val text = """
            {"kind":"fotoz-board","schema":1,"layers":[
              {"id":1,"type":"photo","photo":{"id":1},"w":10,"h":10,"mask":{"shape":"polygon","points":[$pts]}},
              {"id":2,"type":"photo","photo":{"id":1},"w":10,"h":10,"mask":{"shape":"subject","ops":[{"k":"hint","on":true,"r":5,"p":[${(0 until 5000).joinToString(",") { "2,-1" }}]},{"k":"wave"},{"k":"paint","p":[]}]}}
            ]}
        """.trimIndent()
        val d = BoardJson.decode(text)!!
        assertEquals(BoardLimits.MAX_POLYGON_POINTS, (d.board.layers[0].mask.shape as MaskShape.Polygon).points.size)
        val subject = d.board.layers[1].mask.shape as MaskShape.Subject
        assertEquals(1, subject.ops.size)
        val hint = subject.ops.single() as SelectionOp.Hint
        assertEquals(BoardLimits.MAX_STROKE_POINTS, hint.stroke.points.size)
        assertEquals(0.5f, hint.stroke.radius, 0f)
        assertEquals(NormPoint(1f, 0f), hint.stroke.points[0])
        assertEquals(1, d.droppedMasks)
    }

    @Test
    fun `a crop that is upside down, empty or not four numbers is the whole photo`() {
        val text = """
            {"kind":"fotoz-board","schema":1,"layers":[
              {"id":1,"type":"photo","photo":{"id":1},"w":10,"h":10,"crop":[0.9,0,0.1,1]},
              {"id":2,"type":"photo","photo":{"id":1},"w":10,"h":10,"crop":[0,0,0.001,1]},
              {"id":3,"type":"photo","photo":{"id":1},"w":10,"h":10,"crop":[0,0,1]},
              {"id":4,"type":"photo","photo":{"id":1},"w":10,"h":10,"crop":[0.25,0.25,0.75,"x"]},
              {"id":5,"type":"photo","photo":{"id":1},"w":10,"h":10,"crop":[-1,0,2,1]}
            ]}
        """.trimIndent()
        assertTrue(BoardJson.decode(text)!!.board.layers.all { it.crop.isFull })
    }

    @Test
    fun `an overlong text is cut`() {
        val long = "a".repeat(2000)
        val text = """{"kind":"fotoz-board","schema":1,"layers":[{"id":1,"type":"text","text":"$long","w":10,"h":10}]}"""
        assertEquals(BoardLimits.MAX_TEXT_CHARS, ((BoardJson.decode(text)!!.board.layers.single().content) as LayerContent.Text).text.length)
    }

    @Test
    fun `a number too big to be a number is no number`() {
        val text = """{"kind":"fotoz-board","schema":1,"layers":[{"id":1,"type":"colour","w":1e999,"h":10},{"id":2,"type":"colour","w":10,"h":10,"x":-1e999}]}"""
        val d = BoardJson.decode(text)!!
        assertEquals(listOf(2L), d.board.layers.map { it.id })
        assertEquals(540f, d.board.layers.single().transform.cx, 0f)
        assertEquals(1, d.skippedLayers)
    }
}
