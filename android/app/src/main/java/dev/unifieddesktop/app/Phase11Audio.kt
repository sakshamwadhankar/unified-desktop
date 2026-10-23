package dev.unifieddesktop.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Phase 11 D-b: capture the phone's own playback (the desktop's sound) and hand it to the PC.
 *
 * AudioPlaybackCapture needs RECORD_AUDIO plus a live MediaProjection from the user's consent
 * dialog. Android 14+ rules honoured here: the link service is foreground with the
 * `mediaProjection` type before `getMediaProjection` is called, each capture session uses a
 * fresh consent, and the projection is stopped when the session ends.
 *
 * Privacy: the configuration matches **only our own uid** (`addMatchingUid`), so no other
 * app's audio can ever enter the stream. Nothing is recorded to storage: one ShortArray is
 * reused for every read and the packets only exist in memory on their way to the link.
 */
object Phase11Audio {
    const val TAG = "Phase11Audio"
    const val PACKET_SAMPLES = Phase11Media.PACKET_FRAMES * Phase11Media.CHANNELS // 960 shorts
    const val REQ_RECORD_AUDIO = 41
    const val REQ_CONSENT = 42

    @Volatile private var appCtx: Context? = null
    @Volatile private var pendingCode = 0
    @Volatile private var pendingData: Intent? = null
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var capture: Capture? = null
    @Volatile private var sink: ((ByteArray) -> Boolean)? = null

    private fun log(line: String) = Log.i(TAG, line)

    fun audioFormat() = AudioFormat.Builder()
        .setSampleRate(Phase11Media.SAMPLE_RATE)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
        .build()

    /**
     * Only our uid may be captured. The earlier usage-based match would also have captured
     * other apps' media, which the privacy rule forbids.
     */
    fun captureConfig(projection: MediaProjection): AudioPlaybackCaptureConfiguration =
        AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUid(Process.myUid())
            .build()

    /** The result of the consent dialog, held until the link service is ready for it. */
    fun storeConsent(ctx: Context, resultCode: Int, data: Intent?) {
        appCtx = ctx.applicationContext
        pendingCode = resultCode
        pendingData = data
        log("P11AUDIO consent stored code=$resultCode data=${data != null}")
    }

    fun hasConsent(): Boolean = pendingData != null && pendingCode == android.app.Activity.RESULT_OK

    /** True while phone sound is really being captured for the PC. */
    fun isActive(): Boolean = capture != null

    /** Called by the link service once its foreground type includes mediaProjection. */
    fun startWithStoredConsent() {
        val data = pendingData ?: run { log("P11AUDIO no consent"); return }
        if (capture != null) { log("P11AUDIO already capturing"); return }
        val ctx = appCtx ?: run { log("P11AUDIO no context"); return }
        val mgr = ctx.getSystemService(MediaProjectionManager::class.java)
        val p = try {
            mgr.getMediaProjection(pendingCode, data)
        } catch (t: Throwable) {
            log("P11AUDIO getMediaProjection failed ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (p == null) { pendingData = null; return }
        // The consent data must not be reused: Android 14 refuses a second getMediaProjection.
        pendingData = null
        pendingCode = 0
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                log("P11AUDIO projection stopped by the system")
                stopCapture("projection stop")
            }
        }, Handler(Looper.getMainLooper()))
        val push = sink
        if (push == null) {
            log("P11AUDIO consent ready, no session yet")
            return
        }
        startCapture(p, push)
    }

    fun bindSink(push: (ByteArray) -> Boolean) {
        sink = push
        val p = projection
        if (p != null && capture == null) startCapture(p, push)
        else if (p == null) log("P11AUDIO session open, waiting for consent")
    }

    fun unbindSink(reason: String) {
        sink = null
        stopCapture(reason)
    }

    private fun startCapture(p: MediaProjection, push: (ByteArray) -> Boolean) {
        val c = Capture(p, push) { log(it) }
        if (c.start()) {
            capture = c
        } else {
            log("P11AUDIO capture start FAILED")
            stopCapture("start failed")
        }
    }

    fun stopCapture(reason: String) {
        val c = capture
        capture = null
        c?.stop()
        val p = projection
        projection = null
        try { p?.stop() } catch (_: Throwable) {}
        if (c != null || p != null) log("P11AUDIO stopped ($reason); a new session needs a new consent")
    }

    /**
     * One running capture. [start] returns false (and logs why) when AudioRecord refuses the
     * configuration; [stop] joins the reader so no native audio object outlives the session.
     */
    class Capture(
        private val projection: MediaProjection,
        private val push: (ByteArray) -> Boolean,
        private val log: (String) -> Unit,
    ) {
        @Volatile private var running = false
        private var thread: Thread? = null
        private var record: AudioRecord? = null

        @SuppressLint("MissingPermission")
        fun start(): Boolean {
            val min = AudioRecord.getMinBufferSize(
                Phase11Media.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (min <= 0) {
                log("P11AUDIO no AudioRecord (minBuffer=$min)")
                return false
            }
            val rec = try {
                AudioRecord.Builder()
                    .setAudioFormat(audioFormat())
                    .setBufferSizeInBytes(maxOf(min, PACKET_SAMPLES * 2 * 4))
                    .setAudioPlaybackCaptureConfig(captureConfig(projection))
                    .build()
            } catch (t: Throwable) {
                log("P11AUDIO build failed ${t.javaClass.simpleName}: ${t.message}")
                return false
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                log("P11AUDIO not initialized state=${rec.state}")
                rec.release()
                return false
            }
            record = rec
            running = true
            rec.startRecording()
            thread = Thread({ readLoop(rec) }, "p11-audio-capture").also { it.start() }
            log("P11AUDIO start rate=48000 ch=2 packet_ms=10 uid=${Process.myUid()}")
            return true
        }

        private fun readLoop(rec: AudioRecord) {
            val samples = ShortArray(PACKET_SAMPLES)
            var seq = 0
            var packets = 0L
            var bytes = 0L
            while (running) {
                var filled = 0
                while (filled < samples.size && running) {
                    val n = rec.read(samples, filled, samples.size - filled, AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    filled += n
                }
                if (filled < samples.size) break // stopped, or the record died
                // Same clock frame as the PC clock sync (nanoTime), not elapsedRealtime:
                // the two differ by deep-sleep time, which made every arrival age invalid.
                val msg = Phase11Media.encodePcm(seq, System.nanoTime() / 1_000_000, samples)
                if (!push(msg)) {
                    log("P11AUDIO link gone after $packets packets")
                    break
                }
                seq = seq + 1
                packets++
                bytes += msg.size
                if (packets % 100 == 0L) log("P11AUDIO packets=$packets bytes=$bytes")
            }
            log("P11AUDIO end packets=$packets bytes=$bytes")
        }

        fun stop() {
            running = false
            try { record?.stop() } catch (_: Throwable) {}
            try { thread?.join(1000) } catch (_: Throwable) {}
            try { record?.release() } catch (_: Throwable) {}
            record = null
            thread = null
        }
    }
}
