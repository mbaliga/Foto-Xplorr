package com.fotoxplorr.app.animate

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException

/**
 * The JDK's own image codecs (`javax.imageio`), reached by reflection. This module's Kotlin
 * compilation does not expose `java.desktop` (the same reason `MetadataStripperJpegTest` builds its
 * JPEG with Robolectric), but the JVM the tests RUN on has it, so the cross-checks against the JDK's
 * GIF and PNG reader and writer call it through `Class.forName`. If the test JVM ever lacks
 * java.desktop the tests that use this fail loudly rather than skip.
 *
 * Methods are looked up on the public API class (never the plug-in's hidden implementation class),
 * so no `--add-opens` is needed.
 */
object Jdk {
    private fun cls(name: String): Class<*> = Class.forName(name)

    private fun call(className: String, target: Any?, name: String, vararg args: Any?): Any? {
        val c = cls(className)
        val m = c.methods.firstOrNull { it.name == name && it.parameterCount == args.size && fits(it.parameterTypes, args) }
            ?: error("No $className.$name/${args.size}")
        try {
            return m.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun fits(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        for (i in types.indices) {
            val a = args[i] ?: continue
            val t = wrap(types[i])
            if (!t.isInstance(a)) return false
        }
        return true
    }

    private fun wrap(c: Class<*>): Class<*> = when (c) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        else -> c
    }

    private fun construct(className: String, vararg args: Any?): Any {
        val c = cls(className)
        val k = c.constructors.firstOrNull { it.parameterCount == args.size && fits(it.parameterTypes, args) } ?: error("No constructor $className/${args.size}")
        try {
            return k.newInstance(*args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private const val BI = "java.awt.image.BufferedImage"
    private const val IMAGEIO = "javax.imageio.ImageIO"
    private const val READER = "javax.imageio.ImageReader"
    private const val WRITER = "javax.imageio.ImageWriter"
    private const val META = "javax.imageio.metadata.IIOMetadata"

    /** Pixels as the JDK hands them over. */
    class Raster(val width: Int, val height: Int, val argb: IntArray)

    private fun rasterOf(img: Any): Raster {
        val w = call(BI, img, "getWidth") as Int
        val h = call(BI, img, "getHeight") as Int
        val out = IntArray(w * h)
        call(BI, img, "getRGB", 0, 0, w, h, out, 0, w)
        return Raster(w, h, out)
    }

    /** Decode a PNG (or the first image of any ImageIO format) with the JDK. */
    fun decode(bytes: ByteArray): Raster {
        val img = call(IMAGEIO, null, "read", ByteArrayInputStream(bytes)) ?: error("ImageIO could not read the bytes")
        return rasterOf(img)
    }

    class IoFrame(val image: Raster, val left: Int, val top: Int, val delayCs: Int, val disposal: String, val hasTransparency: Boolean)

    class IoGif(val width: Int, val height: Int, val frames: List<IoFrame>, val loopCount: Int?)

    private fun reader(bytes: ByteArray, format: String): Any {
        val it = call(IMAGEIO, null, "getImageReadersByFormatName", format) as Iterator<*>
        val reader = it.next()!!
        val iis = call(IMAGEIO, null, "createImageInputStream", ByteArrayInputStream(bytes))
        call(READER, reader, "setInput", iis, false, false)
        return reader
    }

    private fun elem(tree: Any?, tag: String): Element =
        (tree as Element).getElementsByTagName(tag).item(0) as Element

    /** The JDK's reading of a GIF: logical screen, every frame as the reader gives it (not composited), delay, disposal, loop count. */
    fun readGif(bytes: ByteArray): IoGif {
        val reader = reader(bytes, "gif")
        val sroot = call(META, call(READER, reader, "getStreamMetadata"), "getAsTree", "javax_imageio_gif_stream_1.0")
        val screen = elem(sroot, "LogicalScreenDescriptor")
        val width = screen.getAttribute("logicalScreenWidth").toInt()
        val height = screen.getAttribute("logicalScreenHeight").toInt()
        val n = call(READER, reader, "getNumImages", true) as Int
        val frames = ArrayList<IoFrame>()
        var loop: Int? = null
        for (i in 0 until n) {
            val img = call(READER, reader, "read", i)!!
            val root = call(META, call(READER, reader, "getImageMetadata", i), "getAsTree", "javax_imageio_gif_image_1.0")
            val desc = elem(root, "ImageDescriptor")
            val gce = elem(root, "GraphicControlExtension")
            frames.add(
                IoFrame(
                    rasterOf(img),
                    desc.getAttribute("imageLeftPosition").toInt(),
                    desc.getAttribute("imageTopPosition").toInt(),
                    gce.getAttribute("delayTime").toInt(),
                    gce.getAttribute("disposalMethod"),
                    gce.getAttribute("transparentColorFlag") == "TRUE",
                ),
            )
            val apps = (root as Element).getElementsByTagName("ApplicationExtension")
            for (k in 0 until apps.length) {
                val app = apps.item(k) as Element
                if (app.getAttribute("applicationID") == "NETSCAPE") {
                    val b = call("javax.imageio.metadata.IIOMetadataNode", app, "getUserObject") as ByteArray
                    loop = (b[1].toInt() and 255) or ((b[2].toInt() and 255) shl 8)
                }
            }
        }
        call(READER, reader, "dispose")
        return IoGif(width, height, frames, loop)
    }

    /** Composite the JDK's frames the way a viewer would (disposal "doNotDispose" and "restoreToBackgroundColor" only). */
    fun composite(gif: IoGif): List<IntArray> {
        val canvas = IntArray(gif.width * gif.height)
        val out = ArrayList<IntArray>()
        var prev: IoFrame? = null
        for (f in gif.frames) {
            prev?.let { p ->
                if (p.disposal == "restoreToBackgroundColor") {
                    for (y in 0 until p.image.height) for (x in 0 until p.image.width) canvas[(p.top + y) * gif.width + p.left + x] = 0
                }
            }
            for (y in 0 until f.image.height) for (x in 0 until f.image.width) {
                val p = f.image.argb[y * f.image.width + x]
                if ((p ushr 24) >= 128) canvas[(f.top + y) * gif.width + f.left + x] = p
            }
            out.add(canvas.copyOf())
            prev = f
        }
        return out
    }

    private fun image(argb: IntArray, w: Int, h: Int, type: Int): Any {
        val img = construct(BI, w, h, type)
        call(BI, img, "setRGB", 0, 0, w, h, argb, 0, w)
        return img
    }

    /** An animated GIF written by the JDK's own writer: full-canvas frames, [delayCs] hundredths each, NETSCAPE loop [loop] (0 = for ever). */
    fun writeGif(frames: List<IntArray>, w: Int, h: Int, delayCs: Int, loop: Int): ByteArray {
        val writer = (call(IMAGEIO, null, "getImageWritersByFormatName", "gif") as Iterator<*>).next()!!
        val bos = ByteArrayOutputStream()
        val ios = call(IMAGEIO, null, "createImageOutputStream", bos)
        call(WRITER, writer, "setOutput", ios)
        val param = call(WRITER, writer, "getDefaultWriteParam")
        call(WRITER, writer, "prepareWriteSequence", null)
        for ((i, px) in frames.withIndex()) {
            val img = image(px, w, h, 2) // TYPE_INT_ARGB
            val spec = call("javax.imageio.ImageTypeSpecifier", null, "createFromRenderedImage", img)
            val meta = call(WRITER, writer, "getDefaultImageMetadata", spec, param)!!
            val format = call(META, meta, "getNativeMetadataFormatName") as String
            val root = call(META, meta, "getAsTree", format) as Element
            val gce = root.getElementsByTagName("GraphicControlExtension").item(0) as Element
            gce.setAttribute("disposalMethod", "doNotDispose")
            gce.setAttribute("userInputFlag", "FALSE")
            gce.setAttribute("delayTime", delayCs.toString())
            if (i == 0) {
                val apps = construct("javax.imageio.metadata.IIOMetadataNode", "ApplicationExtensions") as Element
                val app = construct("javax.imageio.metadata.IIOMetadataNode", "ApplicationExtension") as Element
                app.setAttribute("applicationID", "NETSCAPE")
                app.setAttribute("authenticationCode", "2.0")
                call("javax.imageio.metadata.IIOMetadataNode", app, "setUserObject", byteArrayOf(1, (loop and 255).toByte(), ((loop shr 8) and 255).toByte()))
                apps.appendChild(app)
                root.appendChild(apps)
            }
            call(META, meta, "setFromTree", format, root)
            val iio = construct("javax.imageio.IIOImage", img, null, meta)
            call(WRITER, writer, "writeToSequence", iio, param)
        }
        call(WRITER, writer, "endWriteSequence")
        call("javax.imageio.stream.ImageOutputStream", ios, "close")
        call(WRITER, writer, "dispose")
        return bos.toByteArray()
    }

    /**
     * A PNG written by the JDK from [argb] as a BufferedImage of [type] (a `BufferedImage.TYPE_*`
     * constant; 1 RGB, 2 ARGB, 10 byte gray, 11 ushort gray, 13 byte indexed), optionally Adam7 interlaced.
     */
    fun writePng(argb: IntArray, w: Int, h: Int, type: Int, interlaced: Boolean = false): ByteArray = writePng(image(argb, w, h, type), interlaced)

    /** A PNG of an indexed image with [bits] (1, 2 or 4) bits per pixel and the given palette; [indices] are palette indices. */
    fun writeIndexedPng(indices: IntArray, w: Int, h: Int, bits: Int, palette: IntArray, interlaced: Boolean = false, transparentIndex: Int = -1): ByteArray {
        val size = 1 shl bits
        val cmap = IntArray(size) { if (it < palette.size) palette[it] else 0 }
        val model = construct("java.awt.image.IndexColorModel", bits, size, cmap, 0, transparentIndex >= 0, transparentIndex, 0)
        val img = construct(BI, w, h, 12, model) // TYPE_BYTE_BINARY
        val raster = call(BI, img, "getRaster")
        for (y in 0 until h) for (x in 0 until w) {
            call("java.awt.image.WritableRaster", raster, "setSample", x, y, 0, indices[y * w + x])
        }
        return writePng(img, interlaced)
    }

    private fun writePng(img: Any, interlaced: Boolean): ByteArray {
        val writer = (call(IMAGEIO, null, "getImageWritersByFormatName", "png") as Iterator<*>).next()!!
        val bos = ByteArrayOutputStream()
        val ios = call(IMAGEIO, null, "createImageOutputStream", bos)
        call(WRITER, writer, "setOutput", ios)
        val param = call(WRITER, writer, "getDefaultWriteParam")!!
        if (interlaced) call("javax.imageio.ImageWriteParam", param, "setProgressiveMode", 1) // MODE_DEFAULT
        val iio = construct("javax.imageio.IIOImage", img, null, null)
        call(WRITER, writer, "write", null, iio, param)
        call("javax.imageio.stream.ImageOutputStream", ios, "close")
        call(WRITER, writer, "dispose")
        return bos.toByteArray()
    }
}
