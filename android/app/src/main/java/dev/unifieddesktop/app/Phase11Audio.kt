package dev.unifieddesktop.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.SystemClock

/**
 * Phase 11 D-b: capture the phone's own playback (the desktop's sound) and hand it to the PC.
 *
 * Android's AudioPlaybackCapture requires a live MediaProjection. The engine below takes the
 * projection the activity got from `MediaProjectionManager.createScreenCaptureIntent()` (the
 * consent dialog is the owner's), reads 48 kHz stereo s16 in exact 10 ms packets, and calls
 * [push] with one AUDIO message per packet (see Phase11Media for the 12-byte header). The PC
 * side is the jitter buffer + WASAPI player in `pc/udreceiver/src/audio.rs`.
 *
 * Nothing is recorded to storage: the same ShortArray is reused for every read and the packets
 * only exist in memory on their way to the link.
 */
object Phase11Audio {
    const val PACKET_SAMPLES = Phase11Media.PACKET_FRAMES * Phase11Media.CHANNELS // 960 shorts

    private fun audioFormat() = AudioFormat.Builder()
        .setSampleRate(Phase11Media.SAMPLE_RATE)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
        .build()

    fun captureConfig(projection: MediaProjection): AudioPlaybackCaptureConfiguration =
        AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

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

        // The user grant is the MediaProjection consent dialog; RECORD_AUDIO is declared and
        // requested in the wiring step (next work), and the builder is inside try/catch.
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
            log("P11AUDIO start rate=48000 ch=2 packet_ms=10")
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
                val msg = Phase11Media.encodePcm(seq, SystemClock.elapsedRealtime(), samples)
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
