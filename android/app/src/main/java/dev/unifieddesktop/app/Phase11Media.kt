package dev.unifieddesktop.app

import android.util.Base64
import android.webkit.WebView

/**
 * Phase 11 D-e framing, phone side. Two small wire headers shared with the PC halves:
 *
 *   CAM:   [1][u64 seq][u64 ts_ms][u16 width][u16 height][u8 format (1 = JPEG)][u32 len][jpeg]
 *   AUDIO: [u32 seq][u64 ts_ms][s16 interleaved 48 kHz stereo]  (10 ms = 960 samples)
 *
 * The CAM message is one logical message (the Phase-4 writer may split it into 64 KiB
 * frames with MORE_FOLLOWS; the service reassembles before calling [parseCam]). AUDIO is
 * small enough to always be one frame. [script] is the JS bridge call for the shim page;
 * keeping it a pure function lets the unit test pin it without a WebView.
 */
object Phase11Media {
    const val CAM_HEADER = 26
    const val AUDIO_HEADER = 12
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val PACKET_FRAMES = 480

    data class CamFrame(
        val seq: Long,
        val tsMs: Long,
        val width: Int,
        val height: Int,
        val format: Int,
        val jpeg: ByteArray,
    )

    data class PcmPacket(val seq: Int, val tsMs: Long, val samples: ShortArray)

    fun encodeCam(seq: Long, tsMs: Long, width: Int, height: Int, format: Int, jpeg: ByteArray): ByteArray {
        require(width in 1..65535 && height in 1..65535) { "bad frame size" }
        val out = ByteArray(CAM_HEADER + jpeg.size)
        out[0] = 1
        putLong(out, 1, seq)
        putLong(out, 9, tsMs)
        out[17] = (width ushr 8).toByte(); out[18] = width.toByte()
        out[19] = (height ushr 8).toByte(); out[20] = height.toByte()
        out[21] = format.toByte()
        putInt(out, 22, jpeg.size)
        System.arraycopy(jpeg, 0, out, CAM_HEADER, jpeg.size)
        return out
    }

    fun parseCam(msg: ByteArray): CamFrame {
        if (msg.size < CAM_HEADER || msg[0].toInt() != 1) throw IllegalArgumentException("not a cam frame")
        val len = getInt(msg, 22)
        if (len < 0 || CAM_HEADER + len != msg.size) throw IllegalArgumentException("bad cam length")
        return CamFrame(
            getLong(msg, 1), getLong(msg, 9),
            ((msg[17].toInt() and 0xFF) shl 8) or (msg[18].toInt() and 0xFF),
            ((msg[19].toInt() and 0xFF) shl 8) or (msg[20].toInt() and 0xFF),
            msg[21].toInt() and 0xFF,
            msg.copyOfRange(CAM_HEADER, msg.size),
        )
    }

    fun encodePcm(seq: Int, tsMs: Long, samples: ShortArray): ByteArray {
        val out = ByteArray(AUDIO_HEADER + samples.size * 2)
        putInt(out, 0, seq)
        putLong(out, 4, tsMs)
        for (i in samples.indices) {
            out[AUDIO_HEADER + i * 2] = (samples[i].toInt() ushr 8).toByte()
            out[AUDIO_HEADER + i * 2 + 1] = samples[i].toByte()
        }
        return out
    }

    fun parsePcm(msg: ByteArray): PcmPacket {
        if (msg.size < AUDIO_HEADER || (msg.size - AUDIO_HEADER) % 2 != 0) throw IllegalArgumentException("bad pcm")
        val samples = ShortArray((msg.size - AUDIO_HEADER) / 2)
        for (i in samples.indices) {
            samples[i] = (((msg[AUDIO_HEADER + i * 2].toInt() and 0xFF) shl 8) or
                (msg[AUDIO_HEADER + i * 2 + 1].toInt() and 0xFF)).toShort()
        }
        return PcmPacket(getInt(msg, 0), getLong(msg, 4), samples)
    }

    /** The exact JS call the shim page exposes as `udCam.pushFrame(b64, w, h)`. */
    fun script(frame: CamFrame, b64: String): String =
        "window.udCam&&udCam.pushFrame('$b64',${frame.width},${frame.height})"

    /** Push one decoded PC camera frame to the shim page (base64 JPEG). */
    fun pushCamFrame(web: WebView, frame: CamFrame) {
        val b64 = Base64.encodeToString(frame.jpeg, Base64.NO_WRAP)
        val js = script(frame, b64)
        web.post { web.evaluateJavascript(js, null) }
    }

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
    }

    private fun putLong(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = (v ushr (56 - 8 * i)).toByte()
    }

    private fun getInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun getLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }
}
