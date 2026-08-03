package com.fotoxplorr.app.experience

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
import com.fotoxplorr.app.media.MediaAsset
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal class PhotoWallRenderer(
    private val context: Context,
    cards: List<PhotoSceneCard>,
    private val requestFrame: () -> Unit,
    private val onAssetSelected: (MediaAsset) -> Unit,
) : GLSurfaceView.Renderer {
    private val cards = cards.sortedByDescending { it.position.z }
    private val textureCache = TextureCache(MAX_TEXTURES)
    private val pendingTextureIds = ConcurrentHashMap.newKeySet<Long>()
    private val decodedTextures = ConcurrentLinkedQueue<DecodedTexture>()
    private val loader = Executors.newFixedThreadPool(TEXTURE_WORKERS)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val picks = Collections.synchronizedList(mutableListOf<PickTarget>())
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTICES.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD_VERTICES); position(0) }

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)
    private val centreVector = floatArrayOf(0f, 0f, 0f, 1f)
    private val clipVector = FloatArray(4)

    private var program = 0
    private var placeholderTexture = 0
    private var width = 1
    private var height = 1

    @Volatile private var cameraZ = 0f
    @Volatile private var cameraY = 0f
    @Volatile private var touchYaw = 0f
    @Volatile private var touchPitch = 0f
    @Volatile private var sensorYaw = 0f
    @Volatile private var sensorPitch = 0f
    @Volatile private var gyroEnabled = true

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.015f, 0.015f, 0.022f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        placeholderTexture = createPlaceholderTexture()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = max(1, width)
        this.height = max(1, height)
        GLES20.glViewport(0, 0, this.width, this.height)
        Matrix.perspectiveM(
            projection,
            0,
            FIELD_OF_VIEW_DEGREES,
            this.width.toFloat() / this.height,
            NEAR_CLIP,
            FAR_CLIP,
        )
    }

    override fun onDrawFrame(gl: GL10?) {
        uploadDecodedTextures()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val yaw = touchYaw + if (gyroEnabled) sensorYaw * GYRO_YAW_WEIGHT else 0f
        val pitch = (touchPitch + if (gyroEnabled) sensorPitch * GYRO_PITCH_WEIGHT else 0f)
            .coerceIn(-MAX_PITCH, MAX_PITCH)
        val yawRadians = Math.toRadians(yaw.toDouble())
        val pitchRadians = Math.toRadians(pitch.toDouble())
        val directionX = kotlin.math.sin(yawRadians).toFloat() * kotlin.math.cos(pitchRadians).toFloat()
        val directionY = kotlin.math.sin(pitchRadians).toFloat()
        val directionZ = -kotlin.math.cos(yawRadians).toFloat() * kotlin.math.cos(pitchRadians).toFloat()
        Matrix.setLookAtM(
            view,
            0,
            0f,
            cameraY,
            cameraZ,
            directionX,
            cameraY + directionY,
            cameraZ + directionZ,
            0f,
            1f,
            0f,
        )

        val currentPicks = ArrayList<PickTarget>(MAX_VISIBLE_CARDS)
        var visibleCount = 0
        for (card in cards) {
            val depth = cameraZ - card.position.z
            if (depth < MIN_VISIBLE_DEPTH) continue
            if (depth > MAX_VISIBLE_DEPTH) break
            if (visibleCount >= MAX_VISIBLE_CARDS) break

            val textureId = textureCache[card.asset.id.value] ?: placeholderTexture.also {
                requestTexture(card.asset)
            }
            drawCard(card, textureId)
            projectForPicking(card, depth)?.let(currentPicks::add)
            visibleCount += 1
        }
        synchronized(picks) {
            picks.clear()
            picks.addAll(currentPicks.sortedBy { it.depth })
        }
    }

    fun drag(distanceX: Float, distanceY: Float) {
        touchYaw = (touchYaw + distanceX * DRAG_YAW_SCALE).coerceIn(-MAX_YAW, MAX_YAW)
        moveDepth(distanceY * DRAG_DEPTH_SCALE)
    }

    fun moveDepth(delta: Float) {
        val furthest = cards.lastOrNull()?.position?.z ?: -10f
        cameraZ = (cameraZ + delta).coerceIn(furthest + MIN_END_MARGIN, MAX_CAMERA_Z)
    }

    fun setSensorOrientation(yaw: Float, pitch: Float) {
        sensorYaw = yaw
        sensorPitch = pitch
    }

    fun setGyroEnabled(enabled: Boolean) {
        gyroEnabled = enabled
    }

    fun resetCamera() {
        cameraZ = 0f
        cameraY = 0f
        touchYaw = 0f
        touchPitch = 0f
    }

    fun pick(x: Float, y: Float) {
        val target = synchronized(picks) {
            picks.minByOrNull { pick -> hypot((pick.x - x).toDouble(), (pick.y - y).toDouble()) }
        } ?: return
        val distance = hypot((target.x - x).toDouble(), (target.y - y).toDouble())
        if (distance <= max(MIN_PICK_RADIUS, target.radius)) {
            mainHandler.post { onAssetSelected(target.asset) }
        }
    }

    fun focusNearest(x: Float, y: Float) {
        val target = synchronized(picks) {
            picks.minByOrNull { pick -> hypot((pick.x - x).toDouble(), (pick.y - y).toDouble()) }
        } ?: return
        cameraZ = (target.cardZ + FOCUS_DISTANCE).coerceAtMost(MAX_CAMERA_Z)
        touchYaw = 0f
    }

    fun release() {
        textureCache.clear()
        if (placeholderTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(placeholderTexture), 0)
            placeholderTexture = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        loader.shutdownNow()
        decodedTextures.forEach { it.bitmap.recycle() }
        decodedTextures.clear()
    }

    private fun drawCard(card: PhotoSceneCard, textureId: Int) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, card.position.x, card.position.y, card.position.z)
        Matrix.rotateM(model, 0, card.yawDegrees, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, card.pitchDegrees, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, card.width, card.height, 1f)
        Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)

        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrix = GLES20.glGetUniformLocation(program, "uMvp")
        val texture = GLES20.glGetUniformLocation(program, "uTexture")
        val opacity = GLES20.glGetUniformLocation(program, "uOpacity")

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, VERTEX_STRIDE, vertexBuffer)
        vertexBuffer.position(3)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE, vertexBuffer)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        GLES20.glUniform1f(opacity, card.opacity)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(texture, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
    }

    private fun projectForPicking(card: PhotoSceneCard, depth: Float): PickTarget? {
        Matrix.multiplyMV(clipVector, 0, mvp, 0, centreVector, 0)
        if (clipVector[3] <= 0f) return null
        val normalizedX = clipVector[0] / clipVector[3]
        val normalizedY = clipVector[1] / clipVector[3]
        if (kotlin.math.abs(normalizedX) > PICK_VIEWPORT_MARGIN ||
            kotlin.math.abs(normalizedY) > PICK_VIEWPORT_MARGIN
        ) return null
        val screenX = (normalizedX * 0.5f + 0.5f) * width
        val screenY = (0.5f - normalizedY * 0.5f) * height
        val radius = (min(card.width, card.height) / depth * height * PICK_PERSPECTIVE_SCALE)
            .coerceIn(MIN_PICK_RADIUS, MAX_PICK_RADIUS)
        return PickTarget(card.asset, screenX, screenY, radius, depth, card.position.z)
    }

    private fun requestTexture(asset: MediaAsset) {
        val id = asset.id.value
        if (!pendingTextureIds.add(id)) return
        loader.execute {
            val bitmap = runCatching { decodeThumbnail(asset) }.getOrNull()
            if (bitmap == null) {
                pendingTextureIds.remove(id)
                return@execute
            }
            decodedTextures.add(DecodedTexture(id, bitmap))
            requestFrame()
        }
    }

    private fun decodeThumbnail(asset: MediaAsset): Bitmap {
        val resolver = context.contentResolver
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(asset.contentUri, Size(TEXTURE_SIZE, TEXTURE_SIZE), null)
        } else {
            resolver.openInputStream(asset.contentUri)?.use(android.graphics.BitmapFactory::decodeStream)
                ?: error("Unable to decode ${asset.displayName}")
        }
        val scaled = if (bitmap.width > TEXTURE_SIZE || bitmap.height > TEXTURE_SIZE) {
            val ratio = min(TEXTURE_SIZE.toFloat() / bitmap.width, TEXTURE_SIZE.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * ratio).toInt()),
                max(1, (bitmap.height * ratio).toInt()),
                true,
            ).also { if (it !== bitmap) bitmap.recycle() }
        } else {
            bitmap
        }
        return if (scaled.config == Bitmap.Config.ARGB_8888) {
            scaled
        } else {
            scaled.copy(Bitmap.Config.ARGB_8888, false).also { if (it !== scaled) scaled.recycle() }
        }
    }

    private fun uploadDecodedTextures() {
        while (true) {
            val decoded = decodedTextures.poll() ?: break
            try {
                val texture = IntArray(1)
                GLES20.glGenTextures(1, texture, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR_MIPMAP_LINEAR,
                )
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, decoded.bitmap, 0)
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                textureCache.put(decoded.mediaId, texture[0])
            } finally {
                decoded.bitmap.recycle()
                pendingTextureIds.remove(decoded.mediaId)
            }
        }
    }

    private fun createPlaceholderTexture(): Int {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(35, 35, 42))
        }
        return try {
            val texture = IntArray(1)
            GLES20.glGenTextures(1, texture, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            texture[0]
        } finally {
            bitmap.recycle()
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { linkedProgram ->
            GLES20.glAttachShader(linkedProgram, vertexShader)
            GLES20.glAttachShader(linkedProgram, fragmentShader)
            GLES20.glLinkProgram(linkedProgram)
            val status = IntArray(1)
            GLES20.glGetProgramiv(linkedProgram, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val message = GLES20.glGetProgramInfoLog(linkedProgram)
                GLES20.glDeleteProgram(linkedProgram)
                error("OpenGL program link failed: $message")
            }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    private fun compileShader(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("OpenGL shader compile failed: $message")
        }
    }

    private inner class TextureCache(private val maxEntries: Int) {
        private val map = object : LinkedHashMap<Long, Int>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Int>): Boolean {
                if (size <= maxEntries) return false
                GLES20.glDeleteTextures(1, intArrayOf(eldest.value), 0)
                return true
            }
        }

        operator fun get(id: Long): Int? = map[id]

        fun put(id: Long, texture: Int) {
            map.put(id, texture)?.let { previous ->
                if (previous != texture) GLES20.glDeleteTextures(1, intArrayOf(previous), 0)
            }
        }

        fun clear() {
            map.values.forEach { texture -> GLES20.glDeleteTextures(1, intArrayOf(texture), 0) }
            map.clear()
        }
    }

    private data class DecodedTexture(val mediaId: Long, val bitmap: Bitmap)
    private data class PickTarget(
        val asset: MediaAsset,
        val x: Float,
        val y: Float,
        val radius: Float,
        val depth: Float,
        val cardZ: Float,
    )

    private companion object {
        const val FIELD_OF_VIEW_DEGREES = 56f
        const val NEAR_CLIP = 0.25f
        const val FAR_CLIP = 120f
        const val MIN_VISIBLE_DEPTH = 0.7f
        const val MAX_VISIBLE_DEPTH = 58f
        const val MAX_VISIBLE_CARDS = 180
        const val MAX_TEXTURES = 72
        const val TEXTURE_WORKERS = 2
        const val TEXTURE_SIZE = 384
        const val VERTEX_STRIDE = 5 * Float.SIZE_BYTES
        const val DRAG_YAW_SCALE = 0.075f
        const val DRAG_DEPTH_SCALE = 0.018f
        const val MAX_YAW = 78f
        const val MAX_PITCH = 32f
        const val GYRO_YAW_WEIGHT = 0.38f
        const val GYRO_PITCH_WEIGHT = 0.42f
        const val MAX_CAMERA_Z = 1f
        const val MIN_END_MARGIN = 10f
        const val FOCUS_DISTANCE = 5.5f
        const val PICK_PERSPECTIVE_SCALE = 0.42f
        const val PICK_VIEWPORT_MARGIN = 1.4f
        const val MIN_PICK_RADIUS = 34f
        const val MAX_PICK_RADIUS = 140f

        val QUAD_VERTICES = floatArrayOf(
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
            uniform float uOpacity;
            varying vec2 vTexCoord;
            void main() {
                vec4 colour = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(colour.rgb, colour.a * uOpacity);
            }
        """
    }
}
