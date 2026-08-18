package com.fotoxplorr.app.spatial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import com.fotoxplorr.app.experience.PhotoSceneCard
import com.fotoxplorr.app.experience.SceneOrientationController
import com.fotoxplorr.app.experience.SceneOrientationMode
import com.fotoxplorr.app.media.MediaAsset
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal class SpatialSceneSurfaceView(
    context: Context,
    cards: List<PhotoSceneCard>,
    onAssetSelected: (MediaAsset) -> Unit,
    onAccuracyChanged: (Int) -> Unit,
    /**
     * A tap that hit no photo. The scene is immersive -- it has no visible chrome until asked --
     * so this is how the screen's controls are summoned, and the reason [SpatialSceneRenderer.pick]
     * reports whether it hit anything rather than swallowing the miss.
     */
    onEmptyTap: () -> Unit = {},
    /** Compass bearing in degrees from north, so the overlay can show where the phone is pointing. */
    onHeadingChanged: (Float) -> Unit = {},
) : GLSurfaceView(context) {
    private val renderer = SpatialSceneRenderer(
        context.applicationContext,
        cards,
        ::requestRender,
        onAssetSelected,
    )
    private val orientation = SceneOrientationController(
        context = context,
        mode = SceneOrientationMode.ABSOLUTE_NORTH,
        onOrientation = { yaw, pitch ->
            renderer.setOrientation(yaw, pitch)
            onHeadingChanged(yaw)
        },
        onAccuracy = onAccuracyChanged,
    )
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!renderer.pick(e.x, e.y)) onEmptyTap()
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            renderer.adjustTouch(distanceX, distanceY)
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gestures.onTouchEvent(event)) return true
        return super.onTouchEvent(event)
    }

    fun resumeScene() {
        onResume()
        orientation.start()
    }

    fun pauseScene() {
        orientation.stop()
        onPause()
    }

    fun recalibrate() {
        renderer.resetTouch()
        orientation.calibrate()
    }

    fun releaseScene() {
        orientation.stop()
        queueEvent(renderer::release)
    }
}

private class SpatialSceneRenderer(
    private val context: Context,
    private val cards: List<PhotoSceneCard>,
    private val requestFrame: () -> Unit,
    private val onAssetSelected: (MediaAsset) -> Unit,
) : GLSurfaceView.Renderer {
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD); position(0) }
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)
    private val centre = floatArrayOf(0f, 0f, 0f, 1f)
    private val clip = FloatArray(4)
    private val textures = object : LinkedHashMap<Long, Int>(MAX_TEXTURES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Int>): Boolean {
            if (size <= MAX_TEXTURES) return false
            GLES20.glDeleteTextures(1, intArrayOf(eldest.value), 0)
            return true
        }
    }
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val decoded = ConcurrentLinkedQueue<Decoded>()
    private val executor = Executors.newFixedThreadPool(TEXTURE_WORKERS)
    private val handler = Handler(Looper.getMainLooper())
    private val pickTargets = ArrayList<PickTarget>(MAX_VISIBLE_CARDS)

    private var program = 0
    private var placeholder = 0
    private var width = 1
    private var height = 1
    @Volatile private var heading = 0f
    @Volatile private var pitch = 0f
    @Volatile private var touchYaw = 0f
    @Volatile private var touchPitch = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.006f, 0.008f, 0.014f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        placeholder = createPlaceholder()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = max(1, width)
        this.height = max(1, height)
        GLES20.glViewport(0, 0, this.width, this.height)
        Matrix.perspectiveM(projection, 0, FIELD_OF_VIEW, this.width.toFloat() / this.height, 0.2f, 90f)
    }

    override fun onDrawFrame(gl: GL10?) {
        uploadDecoded()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val yaw = heading + touchYaw
        val finalPitch = (pitch + touchPitch).coerceIn(-72f, 72f)
        val yawRadians = Math.toRadians(yaw.toDouble())
        val pitchRadians = Math.toRadians(finalPitch.toDouble())
        val directionX = kotlin.math.sin(yawRadians).toFloat() * kotlin.math.cos(pitchRadians).toFloat()
        val directionY = kotlin.math.sin(pitchRadians).toFloat()
        val directionZ = -kotlin.math.cos(yawRadians).toFloat() * kotlin.math.cos(pitchRadians).toFloat()
        Matrix.setLookAtM(view, 0, 0f, 0f, 0f, directionX, directionY, directionZ, 0f, 1f, 0f)

        pickTargets.clear()
        var visibleCount = 0
        for (card in cards) {
            if (visibleCount >= MAX_VISIBLE_CARDS) break
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, card.position.x, card.position.y, card.position.z)
            Matrix.rotateM(model, 0, card.yawDegrees, 0f, 1f, 0f)
            Matrix.scaleM(model, 0, card.width, card.height, 1f)
            Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)
            Matrix.multiplyMV(clip, 0, mvp, 0, centre, 0)
            if (clip[3] <= 0f) continue
            val ndcX = clip[0] / clip[3]
            val ndcY = clip[1] / clip[3]
            if (abs(ndcX) > VIEWPORT_MARGIN || abs(ndcY) > VIEWPORT_MARGIN) continue
            val screenX = (ndcX * 0.5f + 0.5f) * width
            val screenY = (0.5f - ndcY * 0.5f) * height
            val radius = (min(card.width, card.height) / clip[3] * height * PICK_SCALE)
                .coerceIn(MIN_PICK_RADIUS, MAX_PICK_RADIUS)
            pickTargets += PickTarget(card.asset, screenX, screenY, radius)
            val texture = textures[card.asset.id.value] ?: placeholder.also { requestTexture(card.asset) }
            draw(texture)
            visibleCount += 1
        }
    }

    fun setOrientation(yaw: Float, pitch: Float) {
        heading = yaw
        this.pitch = pitch
    }

    fun adjustTouch(distanceX: Float, distanceY: Float) {
        touchYaw += distanceX * TOUCH_YAW_SCALE
        touchPitch = (touchPitch + distanceY * TOUCH_PITCH_SCALE).coerceIn(-35f, 35f)
    }

    fun resetTouch() {
        touchYaw = 0f
        touchPitch = 0f
    }

    /** Opens the nearest photo under [x], [y]; returns whether one was actually hit. */
    fun pick(x: Float, y: Float): Boolean {
        val target = pickTargets.minByOrNull {
            hypot((it.x - x).toDouble(), (it.y - y).toDouble())
        } ?: return false
        if (hypot((target.x - x).toDouble(), (target.y - y).toDouble()) > target.radius) return false
        handler.post { onAssetSelected(target.asset) }
        return true
    }

    fun release() {
        textures.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
        textures.clear()
        if (placeholder != 0) GLES20.glDeleteTextures(1, intArrayOf(placeholder), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        executor.shutdownNow()
        decoded.forEach { it.bitmap.recycle() }
        decoded.clear()
    }

    private fun draw(texture: Int) {
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val uv = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrix = GLES20.glGetUniformLocation(program, "uMvp")
        val sampler = GLES20.glGetUniformLocation(program, "uTexture")
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, vertices)
        vertices.position(3)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, vertices)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(sampler, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(uv)
    }

    private fun requestTexture(asset: MediaAsset) {
        val id = asset.id.value
        if (!pending.add(id)) return
        executor.execute {
            val bitmap = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(asset.contentUri, Size(TEXTURE_SIZE, TEXTURE_SIZE), null)
                } else {
                    context.contentResolver.openInputStream(asset.contentUri)?.use(android.graphics.BitmapFactory::decodeStream)
                        ?: error("Unable to decode media")
                }
            }.getOrNull()
            if (bitmap == null) {
                pending.remove(id)
            } else {
                decoded.add(Decoded(id, bitmap))
                requestFrame()
            }
        }
    }

    private fun uploadDecoded() {
        while (true) {
            val item = decoded.poll() ?: break
            try {
                val texture = IntArray(1)
                GLES20.glGenTextures(1, texture, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
                // GL_LINEAR only, no mipmapping: these textures come from MediaStore thumbnails,
                // which are never power-of-two (real photos aren't square). glGenerateMipmap on
                // an NPOT texture is undefined behaviour in OpenGL ES 2.0 without the
                // GL_OES_texture_npot extension -- some GPU drivers handle it fine, some crash
                // natively deep in the driver with no Java stack trace and no GL error to catch.
                // That is a plausible match for the native-crash reports with no trace at all.
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, item.bitmap, 0)
                textures.put(item.id, texture[0])?.let { previous ->
                    if (previous != texture[0]) GLES20.glDeleteTextures(1, intArrayOf(previous), 0)
                }
            } finally {
                item.bitmap.recycle()
                pending.remove(item.id)
            }
        }
    }

    private fun createPlaceholder(): Int {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(30, 34, 44))
        }
        return try {
            IntArray(1).also { texture ->
                GLES20.glGenTextures(1, texture, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            }[0]
        } finally {
            bitmap.recycle()
        }
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        return GLES20.glCreateProgram().also { result ->
            GLES20.glAttachShader(result, vertexShader)
            GLES20.glAttachShader(result, fragmentShader)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetProgramInfoLog(result) }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    private fun compile(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
    }

    private data class Decoded(val id: Long, val bitmap: Bitmap)
    private data class PickTarget(val asset: MediaAsset, val x: Float, val y: Float, val radius: Float)

    private companion object {
        const val MAX_TEXTURES = 72
        const val TEXTURE_WORKERS = 2
        const val TEXTURE_SIZE = 384
        const val MAX_VISIBLE_CARDS = 180
        const val FIELD_OF_VIEW = 62f
        const val VIEWPORT_MARGIN = 1.4f
        const val PICK_SCALE = 0.4f
        const val MIN_PICK_RADIUS = 28f
        const val MAX_PICK_RADIUS = 120f
        const val TOUCH_YAW_SCALE = 0.07f
        const val TOUCH_PITCH_SCALE = 0.06f
        const val VERTEX_STRIDE_BYTES = 5 * Float.SIZE_BYTES

        val QUAD = floatArrayOf(
            -0.5f, -0.5f, 0f, 0f, 1f,
            0.5f, -0.5f, 0f, 1f, 1f,
            -0.5f, 0.5f, 0f, 0f, 0f,
            0.5f, 0.5f, 0f, 1f, 0f,
        )

        const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
