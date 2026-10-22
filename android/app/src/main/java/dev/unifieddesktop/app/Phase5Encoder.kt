package dev.unifieddesktop.app

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Phase-5 compositor + hardware encoder (ARCHITECTURE H3, ALGORITHMS 1-2).
 *
 * The desktop's VirtualDisplay renders into `surfaceTexture`; a GL thread (EGL14 + GLES2,
 * the Grafika pattern) draws that external texture straight into the encoder's input
 * surface (`MediaCodec.createInputSurface`) - GPU only, no CPU copy. It draws only when a
 * new frame arrives, plus a keep-alive redraw every second (Qualcomm's encoder will not
 * repeat a static frame, LESSONS 3) and immediately on a keyframe request.
 *
 * The encoder runs as `video/avc` with low-latency/no-B-frames/long I-frame interval and
 * keyframes on demand; the output thread hands whole Annex-B access units to [listener].
 */
class Phase5Encoder(
    private val width: Int,
    private val height: Int,
    private val listener: Listener,
    private val bitrate: Int = BITRATE,
    private val fps: Int = FPS,
    initialSeq: Int = 0,
) {

    /**
     * Typing urgency window (Phase 13 latency). A frame composed while this window is open
     * is encoded at once instead of waiting for the governor's fps slot. The governor caps
     * sustained frames (15-30 fps at thermal 3) to save power, but a key or a click is a
     * single event where that extra wait is pure latency: at 15 fps the scheduled slot put
     * 45 ms between the drawn flash and the encoded frame (measured), against ~15-20 ms
     * when the frame leaves immediately. Pacing resumes when the user stops.
     */
    class UrgentFrames(private val windowNs: Long = 150_000_000L) {
        @Volatile private var untilNs = 0L
        @Volatile private var armedAtNs = 0L

        /** Arm or extend the window from [nowNs] (System.nanoTime). */
        fun arm(nowNs: Long) {
            untilNs = nowNs + windowNs
            armedAtNs = nowNs
        }

        /** True while a frame composed at [nowNs] should skip the fps pacing. */
        fun due(nowNs: Long): Boolean = nowNs < untilNs

        /** The moment of the newest input; a frame composed before it lacks its effect. */
        fun armedAt(): Long = armedAtNs
    }

    interface Listener {
        /** One encoded access unit, ready for CH_VIDEO (header packing happens in the session). */
        fun onAccessUnit(flags: Byte, seq: Int, ptsUs: Long, encodeDoneNs: Long, annexB: ByteArray)
        /** The encoder could not keep the pace; the session asks the PC for a fresh keyframe. */
        fun onQueuePressure()
    }

    companion object {
        const val TAG = "Phase5"
        const val MIME = "video/avc"
        const val BITRATE = 12_000_000
        const val FPS = 60
        const val IFRAME_INTERVAL_S = 10
        const val KEEPALIVE_MS = 1000L
        /** Submission cap: never feed the encoder faster than it drains (60 fps). */
        const val MIN_FRAME_NS = 16_600_000L
        const val VERTEX_SHADER = """
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vTextureCoord); }
        """
    }

    /** Filled by GL init; the desktop's VirtualDisplay renders into this. */
    @Volatile var surfaceTexture: SurfaceTexture? = null
        private set

    @Volatile var codecName: String = "(none)"
        private set

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    private var outThread: Thread? = null

    private val running = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    private val seq = AtomicInteger(initialSeq)
    private val keyframeRequests = AtomicInteger(0)
    private val droppedFrames = AtomicInteger(0)

    // GL objects (GL thread only)
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aTex = 0
    private var uST = 0
    private val stMatrix = FloatArray(16)
    private val vertexBuf: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val texBuf: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

    /** Buffers the virtual display queued that the GL thread has not latched yet. */
    private val framesPending = AtomicInteger(0)
    private val drawCount = AtomicInteger(0)
    @Volatile private var lastDrawNs = 0L
    @Volatile private var targetFrameNs = MIN_FRAME_NS
    /** Typing/click urgency: composed frames skip the fps pacing while it is open. */
    private val urgent = UrgentFrames()
    /** A paced (deferred) draw is already posted; GL thread only. */
    private var drawScheduled = false

    /** Build everything and wait until the GL surface texture exists. */
    /**
     * Optional keys: each one is a latency/size win, but a codec may advertise a key and then
     * reject the whole configure with it (the emulator's c2.android.avc.encoder advertises
     * KEY_PREPEND_HEADER_TO_SYNC_FRAMES but fails configure with BAD_VALUE - 2026-10-19,
     * BUGS B-056). If configure throws, the encoder is rebuilt without these and retried, so
     * one bad knob cannot cost the whole session.
     */
    private val optionalKeys = linkedMapOf(
        MediaFormat.KEY_BITRATE_MODE to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
        MediaFormat.KEY_PROFILE to MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        MediaFormat.KEY_PRIORITY to 0,                                    // 0 = realtime
        MediaFormat.KEY_MAX_B_FRAMES to 0,
        MediaFormat.KEY_LOW_LATENCY to 1,
        // Qualcomm's own low-latency switch (listed by c2.qti.avc.encoder's
        // supportedVendorParameters on the test phone); other encoders ignore the key.
        "vendor.qti-ext-enc-low-latency.enable" to 1,
        MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES to 1,
    )

    /** Phase 12 A8/B2 only; dropped first if the codec rejects it (keeps the base set). */
    private fun p12Keys(): Map<String, Int> {
        val rows = Phase12Tuning.sliceRows
        return if (rows <= 0) emptyMap() else mapOf("vendor.qti-ext-enc-slice.spacing" to rows)
    }

    /** The keys every encoder must take; nothing here is optional. */
    private fun baseFormat(): MediaFormat {
        val fmt = MediaFormat.createVideoFormat(MIME, width, height)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_S)
        // A 1920x1200 desktop is 9000 macroblocks, above level 4.2's 8704, and at 60 fps it
        // needs 540,000 MB/s: level 5.0 stops at 245,760, so 5.1 is the first level that can
        // actually carry it. (1920x1080 = 8100 MBs fits 4.2 and is why 1080p worked.) The
        // encoder otherwise picks a level that cannot hold the frame and start() fails (D8).
        // Levels 4.0-4.2 are unchanged for normal 1080p.
        val mbs = (width / 16) * (height / 16)
        if (mbs > 8192) fmt.setInteger(MediaFormat.KEY_LEVEL, 0x2001) // H.264 level 5.1
        return fmt
    }

    fun prepare(timeoutMs: Long = 4000): Boolean {
        try {
            // 0 = all keys, 1 = p12 extras dropped, 2 = all optional keys dropped.
            var dropLevel = 0
            while (true) {
                val fmt = baseFormat()
                if (dropLevel < 2) {
                    for ((k, v) in optionalKeys) setIntIfSupported(fmt, k, v)
                }
                if (dropLevel == 0 && Phase12Tuning.lowLatencyEncoder) {
                    for ((k, v) in p12Keys()) setIntIfSupported(fmt, k, v)
                }
                val c = MediaCodec.createEncoderByType(MIME)
                try {
                    c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec = c
                    codecName = c.name
                    Log.i(TAG, "encoder configured optionalKeys=${if (dropLevel == 2) "DROPPED" else if (dropLevel == 1) "p12-dropped" else "applied"}")
                    break
                } catch (e: IllegalArgumentException) {
                    try { c.release() } catch (_: Throwable) {}
                    if (dropLevel == 2) throw e
                    if (dropLevel == 0 && Phase12Tuning.lowLatencyEncoder) {
                        dropLevel = 1
                        Log.w(TAG, "encoder rejected the p12 keys, retrying with the base low-latency set: $e")
                    } else {
                        dropLevel = 2
                        Log.w(TAG, "encoder rejected the optional keys, retrying without them: $e")
                    }
                }
            }
            val c = codec!!
            if (android.os.Build.VERSION.SDK_INT >= 31) try {
                // Diagnostics: which vendor knobs could lower the encoder's own latency
                val v = c.supportedVendorParameters.filter {
                    it.contains("latency", true) || it.contains("lowlat", true) || it.contains("slice", true)
                }
                Log.i(TAG, "encoder vendor params (latency-related): $v")
            } catch (t: Throwable) {
                Log.i(TAG, "encoder vendor params unavailable: $t")
            }
            val s = try {
                c.createInputSurface()
            } catch (t: Throwable) {
                Log.e(TAG, "createInputSurface failed ${width}x$height", t)
                throw t
            }
            inputSurface = s
            try {
                c.start()
            } catch (t: Throwable) {
                Log.e(TAG, "codec start failed ${width}x$height", t)
                throw t
            }
            Log.i(TAG, "encoder name=$codecName size=${width}x$height bitrate=$bitrate fps=$fps")

            glThread = HandlerThread("p5-gl").also { it.start() }
            glHandler = Handler(glThread!!.looper)
            glHandler!!.post { glInitAndLoop(s) }
            if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "GL init timed out")
                return false
            }
            running.set(true)
            outThread = Thread({ outputLoop() }, "p5-enc-out").also { it.start() }
            return surfaceTexture != null
        } catch (t: Throwable) {
            Log.e(TAG, "encoder prepare failed", t)
            stop()
            return false
        }
    }

    private fun setIntIfSupported(fmt: MediaFormat, key: String, value: Int) {
        try { fmt.setInteger(key, value) } catch (t: Throwable) { Log.w(TAG, "format key $key unsupported: $t") }
    }

    fun requestKeyframe() {
        keyframeRequests.incrementAndGet()
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (t: Throwable) {
            Log.w(TAG, "request sync frame failed: $t")
        }
        glHandler?.post { drawFrame(System.nanoTime(), true) }
    }

    /** Immediate redraw of the last frame (input reactions must never wait for a tick). */
    fun redrawNow() = glHandler?.post { drawFrame(System.nanoTime(), true) } ?: Unit

    /**
     * A key/button/wheel just arrived on the input thread. The next frame the display
     * composes skips [targetFrameNs] pacing (see [UrgentFrames]).
     */
    fun inputUrgent() = urgent.arm(System.nanoTime())

    /** Governor pacing hook. MediaCodec stays configured for 60 fps; submission is reduced first. */
    fun setTargetFps(fps: Int) {
        targetFrameNs = 1_000_000_000L / fps.coerceIn(1, this.fps)
        Log.i(TAG, "target fps=$fps interval_ns=$targetFrameNs")
    }

    fun stats(): String =
        "frames=${seq.get()} draws=${drawCount.get()} keyreq=${keyframeRequests.get()} dropped=$droppedFrames"

    fun stop() {
        running.set(false)
        try { glHandler?.post { glTeardown() } } catch (_: Throwable) {}
        try { glThread?.quitSafely() } catch (_: Throwable) {}
        outThread?.let { try { it.join(1000) } catch (_: Throwable) {} }
        outThread = null
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
        try { inputSurface?.release() } catch (_: Throwable) {}
        inputSurface = null
        surfaceTexture = null
    }

    // ------------------------------------------------------------------ GL

    private fun glInitAndLoop(input: Surface) {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) throw RuntimeException("eglInitialize")
            val cfgAttrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID - required for a MediaCodec input surface
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val n = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, configs, 0, 1, n, 0) || n[0] == 0) {
                throw RuntimeException("eglChooseConfig n=${n[0]}")
            }
            val config = configs[0]!!
            eglContext = EGL14.eglCreateContext(
                eglDisplay, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, input, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface")
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) throw RuntimeException("eglMakeCurrent")

            val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val link = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, link, 0)
            if (link[0] == 0) throw RuntimeException("link: " + GLES20.glGetProgramInfoLog(program))
            aPos = GLES20.glGetAttribLocation(program, "aPosition")
            aTex = GLES20.glGetAttribLocation(program, "aTextureCoord")
            uST = GLES20.glGetUniformLocation(program, "uSTMatrix")

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            texId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val st = SurfaceTexture(texId)
            st.setDefaultBufferSize(width, height)
            st.setOnFrameAvailableListener({
                framesPending.incrementAndGet()
                glHandler?.post {
                    val t = System.nanoTime()
                    // Animated content (the Test window, video, a running app) queues a frame
                    // composed before the key arrived. Encoding that one first put another
                    // frame in front of the real one (measured +10-20 ms), so the urgent path
                    // drops it: a frame older than the input cannot show the input.
                    drawFrame(t, urgent.due(t), dropPreInput = true)
                }
            }, glHandler)
            surfaceTexture = st
            Log.i(TAG, "gl ready tex=$texId program=$program")
        } catch (t: Throwable) {
            Log.e(TAG, "gl init failed", t)
        } finally {
            ready.countDown()
        }
        // keep-alive redraw: Qualcomm's encoder repeats nothing by itself, so a desktop that
        // drew nothing for KEEPALIVE_MS gets the last picture again (forced, ALGORITHMS 1)
        val ka = object : Runnable {
            override fun run() {
                if (!running.get()) return
                val now = System.nanoTime()
                drawFrame(now, now - lastDrawNs >= KEEPALIVE_MS * 1_000_000L)
                glHandler?.postDelayed(this, KEEPALIVE_MS)
            }
        }
        glHandler?.postDelayed(ka, KEEPALIVE_MS)
    }

    private fun glTeardown() {
        try { surfaceTexture?.release() } catch (_: Throwable) {}
        surfaceTexture = null
        try {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        } catch (_: Throwable) {}
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    /** GL thread: draw the current frame into the encoder input surface. */
    private fun drawFrame(nowNs: Long, force: Boolean, dropPreInput: Boolean = false) {
        val st = surfaceTexture ?: return
        // Pace the encoder input. Fed faster than it can encode, the encoder's input queue
        // stays full and every frame waits behind it (measured: ~200 ms from draw to output).
        // Too soon: draw once the interval is up, then with the newest content.
        val sinceLast = nowNs - lastDrawNs
        if (!force && sinceLast < targetFrameNs) {
            if (!drawScheduled) {
                drawScheduled = true
                glHandler?.postDelayed({
                    drawScheduled = false
                    drawFrame(System.nanoTime(), false)
                }, (targetFrameNs - sinceLast) / 1_000_000L + 1)
            }
            return
        }
        try {
            // Latch EVERY buffer the virtual display queued since the last draw, so the
            // newest one is on the texture. updateTexImage takes the oldest queued buffer, so
            // latching only one per draw let a backlog build up (measured ~200 ms of lag).
            val pending = framesPending.getAndSet(0)
            val hadFrame = pending > 0
            if (hadFrame) {
                repeat(pending) { st.updateTexImage() }
                st.getTransformMatrix(stMatrix)
                if (dropPreInput && urgent.due(nowNs)) {
                    val ts = st.timestamp
                    if (ts > 0 && ts < urgent.armedAt()) return
                }
            } else if (!force) {
                // nothing new and no keep-alive due: skip the GPU work entirely
                return
            }
            if (eglSurface == EGL14.EGL_NO_SURFACE) return
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            vertexBuf.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
            GLES20.glEnableVertexAttribArray(aPos)
            texBuf.position(0)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glUniformMatrix4fv(uST, 1, false, stMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nowNs)
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                Log.w(TAG, "eglSwapBuffers failed 0x${Integer.toHexString(EGL14.eglGetError())}")
            }
            drawCount.incrementAndGet()
            lastDrawNs = nowNs
        } catch (t: Throwable) {
            Log.e(TAG, "draw failed", t)
        }
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    // ------------------------------------------------------------------ encoder output

    private var csd: ByteArray? = null

    private fun outputLoop() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val idx: Int
            try {
                idx = c.dequeueOutputBuffer(info, 10_000)
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "dequeue failed: $t")
                break
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = c.outputFormat
                val c0 = f.getByteBuffer("csd-0")
                val c1 = f.getByteBuffer("csd-1")
                val out = java.io.ByteArrayOutputStream()
                if (c0 != null) out.write(startCoded(c0))
                if (c1 != null) out.write(startCoded(c1))
                csd = out.toByteArray()
                Log.i(TAG, "output format changed csd=${csd!!.size} B")
                continue
            }
            if (idx < 0) continue
            try {
                val buf = c.getOutputBuffer(idx) ?: continue
                if (info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    buf.get(data)
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig) {
                        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        var au = toAnnexB(data)
                        if (isKey) {
                            val c2 = csd
                            if (c2 != null && !containsSps(au)) {
                                val joined = ByteArray(c2.size + au.size)
                                System.arraycopy(c2, 0, joined, 0, c2.size)
                                System.arraycopy(au, 0, joined, c2.size, au.size)
                                au = joined
                            }
                        }
                        val mySeq = seq.incrementAndGet()
                        listener.onAccessUnit(
                            if (isKey) Phase5Protocol.VFLAG_KEYFRAME else 0,
                            mySeq, info.presentationTimeUs, System.nanoTime(), au
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "output handling failed: $t")
            } finally {
                try { c.releaseOutputBuffer(idx, false) } catch (_: Throwable) {}
            }
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
        }
    }

    private fun containsSps(au: ByteArray): Boolean {
        var i = 0
        while (i + 4 <= au.size) {
            if (au[i] == 0.toByte() && au[i + 1] == 0.toByte() && au[i + 2] == 1.toByte()) {
                if ((au[i + 3].toInt() and 0x1F) == 7) return true
                i += 3
            } else i++
        }
        return false
    }

    private fun startCoded(b: ByteBuffer): ByteArray {
        val dup = b.duplicate()
        val out = ByteArray(dup.remaining())
        dup.get(out)
        return toAnnexB(out)
    }

    /**
     * MediaCodec gives Annex-B on most devices once PREPEND_HEADER is on, but some
     * Qualcomm builds still emit AVCC (4-byte length prefixes). Normalise: if the first
     * bytes are a start code keep them, otherwise convert every length-prefixed NALU.
     */
    private fun toAnnexB(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            return data
        }
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        var converted = false
        while (i + 4 <= data.size) {
            val len = ((data[i].toInt() and 0xFF) shl 24) or ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            if (len <= 0 || i + 4 + len > data.size) { converted = false; break }
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(data, i + 4, len)
            i += 4 + len
            converted = true
        }
        return if (converted && i == data.size) out.toByteArray() else data
    }
}
