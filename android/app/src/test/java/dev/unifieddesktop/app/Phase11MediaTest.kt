package dev.unifieddesktop.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 11 D-e: CAM/audio framing and the JS bridge string. */
class Phase11MediaTest {

    @Test
    fun camFrameRoundTrips() {
        val jpeg = ByteArray(3000) { (it % 251).toByte() }
        val m = Phase11Media.encodeCam(7, 1_700_000_000_123, 1280, 720, 1, jpeg)
        assertEquals(Phase11Media.CAM_HEADER + jpeg.size, m.size)
        val f = Phase11Media.parseCam(m)
        assertEquals(7L, f.seq)
        assertEquals(1_700_000_000_123L, f.tsMs)
        assertEquals(1280, f.width)
        assertEquals(720, f.height)
        assertEquals(1, f.format)
        assertArrayEquals(jpeg, f.jpeg)
    }

    @Test
    fun shortOrLyingCamFrameIsRejected() {
        try {
            Phase11Media.parseCam(ByteArray(4))
            throw AssertionError("short frame must fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("cam"))
        }
        val m = Phase11Media.encodeCam(1, 2, 640, 480, 1, ByteArray(10))
        m[25] = 99 // length field now lies
        try {
            Phase11Media.parseCam(m)
            throw AssertionError("bad length must fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("length"))
        }
    }

    @Test
    fun tenMillisecondPcmPacketRoundTrips() {
        val samples = ShortArray(Phase11Media.PACKET_FRAMES * Phase11Media.CHANNELS) { i ->
            (if (i % 2 == 0) 1000 else -1000).toShort()
        }
        val m = Phase11Media.encodePcm(42, 1_700_000_000_456, samples)
        assertEquals(12 + 1920, m.size)
        val p = Phase11Media.parsePcm(m)
        assertEquals(42, p.seq)
        assertEquals(1_700_000_000_456L, p.tsMs)
        assertArrayEquals(samples, p.samples)
    }

    @Test
    fun oddPcmLengthIsRejected() {
        try {
            Phase11Media.parsePcm(ByteArray(13))
            throw AssertionError("odd PCM must fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("pcm"))
        }
    }

    @Test
    fun bridgeScriptIsExact() {
        val f = Phase11Media.CamFrame(1, 2, 640, 480, 1, ByteArray(0))
        assertEquals(
            "window.udCam&&udCam.pushFrame('QUJD',640,480)",
            Phase11Media.script(f, "QUJD")
        )
    }
}
