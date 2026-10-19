package dev.unifieddesktop.app

import dev.unifieddesktop.app.Phase5Protocol.ChunkAssembler
import dev.unifieddesktop.app.Phase5Protocol.InputEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Phase-5 wire formats: video header, input events, VK mapping, chunk assembly. */
class Phase5ProtocolTest {
    private val P = Phase5Protocol

    // ---------------------------------------------------------------- video

    @Test
    fun videoHeaderRoundTrip() {
        val au = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33)
        val wire = P.packVideoFrame(P.VFLAG_KEYFRAME, 42, 123_456_789L, 987_654_321_000L, au)
        assertEquals(P.VIDEO_HEADER_LEN + au.size, wire.size)
        assertEquals(1, wire[0].toInt())
        val f = P.parseVideoFrame(wire)
        assertTrue(f.isKeyframe)
        assertFalse(f.isConfig)
        assertEquals(42, f.seq)
        assertEquals(123_456_789L, f.ptsUs)
        assertEquals(987_654_321_000L, f.encodeDoneNs)
        assertArrayEquals(au, f.annexB)
    }

    @Test
    fun videoHeaderNonKeyAndConfigFlags() {
        val f = P.parseVideoFrame(P.packVideoFrame(P.VFLAG_CONFIG, 1, 7L, 9L, ByteArray(3)))
        assertFalse(f.isKeyframe)
        assertTrue(f.isConfig)
        assertEquals(7L, f.ptsUs)
        assertEquals(9L, f.encodeDoneNs)
        assertEquals(3, f.annexB.size)
    }

    @Test
    fun videoHeaderRejectsShortAndWrongVersion() {
        try {
            P.parseVideoFrame(ByteArray(P.VIDEO_HEADER_LEN - 1))
            fail("short message must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("header"))
        }
        val bad = P.packVideoFrame(0, 0, 0, 0, ByteArray(0))
        bad[0] = 9
        try {
            P.parseVideoFrame(bad)
            fail("wrong version must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("version"))
        }
    }

    // ---------------------------------------------------------------- input

    @Test
    fun inputRoundTripAllEventTypes() {
        val events = listOf(
            InputEvent.Move(1920, 1080),
            InputEvent.Button(P.BTN_LEFT, true, 500, 300, 123456789L),
            InputEvent.Button(P.BTN_LEFT, false, 500, 300, 123456800L),
            InputEvent.Wheel(-3, 10, 20, 42L),
            InputEvent.Key(true, 0x41, 0x1E, P.MOD_SHIFT or P.MOD_CTRL, 4294967295L),
            InputEvent.Key(false, 0x41, 0x1E, P.MOD_SHIFT or P.MOD_CTRL, 4294967296L),
            InputEvent.ReleaseAll,
        )
        val wire = P.packInputEvents(events)
        // 5 + 10 + 10 + 10 + 9 + 9 + 1
        assertEquals(57, wire.size)
        val back = P.parseInputEvents(wire)
        assertEquals(events.size, back.size)
        assertEquals(InputEvent.Move(1920, 1080), back[0])
        val b0 = back[1] as InputEvent.Button
        assertEquals(P.BTN_LEFT, b0.button)
        assertTrue(b0.down)
        assertEquals(500, b0.x)
        assertEquals(300, b0.y)
        assertEquals(123456789L, b0.tPcUs)
        val b1 = back[2] as InputEvent.Button
        assertFalse(b1.down)
        val w = back[3] as InputEvent.Wheel
        assertEquals(-3, w.delta)
        assertEquals(20, w.y)
        val k0 = back[4] as InputEvent.Key
        assertTrue(k0.down)
        assertEquals(0x41, k0.vk)
        assertEquals(0x1E, k0.scan)
        assertEquals(P.MOD_SHIFT or P.MOD_CTRL, k0.mods)
        assertEquals(4294967295L, k0.tPcUs)   // the low 32 bits wrap, both ends agree
        val k1 = back[5] as InputEvent.Key
        assertFalse(k1.down)
        assertEquals(0L, k1.tPcUs)   // 0x1_0000_0000 truncates to 0 on the wire
        assertEquals(InputEvent.ReleaseAll, back[6])
    }

    @Test
    fun inputRejectsUnknownTypeAndTruncation() {
        try {
            P.parseInputEvents(byteArrayOf(99, 0, 0, 0, 0))
            fail("unknown type must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unknown input event"))
        }
        try {
            P.parseInputEvents(byteArrayOf(P.EVT_KEY, 1, 0x41, 0x1E, 0))
            fail("truncated key must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("truncated"))
        }
    }

    @Test
    fun emptyInputMessageParsesToNothing() {
        assertTrue(P.parseInputEvents(ByteArray(0)).isEmpty())
    }

    // ---------------------------------------------------------------- VK map

    @Test
    fun vkLettersDigitsAndNamedKeys() {
        assertEquals(29, P.vkToKeycode(0x41))   // A
        assertEquals(54, P.vkToKeycode(0x5A))   // Z
        assertEquals(7, P.vkToKeycode(0x30))    // 0
        assertEquals(16, P.vkToKeycode(0x39))   // 9
        assertEquals(66, P.vkToKeycode(0x0D))   // Enter
        assertEquals(67, P.vkToKeycode(0x08))   // Backspace
        assertEquals(19, P.vkToKeycode(0x26))   // Up
        assertEquals(20, P.vkToKeycode(0x28))   // Down
        assertEquals(21, P.vkToKeycode(0x25))   // Left
        assertEquals(22, P.vkToKeycode(0x27))   // Right
        assertEquals(59, P.vkToKeycode(0x10))   // Shift
        assertEquals(113, P.vkToKeycode(0x11))  // Ctrl
        assertEquals(57, P.vkToKeycode(0x12))   // Alt
        assertEquals(131, P.vkToKeycode(0x70))  // F1
        assertEquals(142, P.vkToKeycode(0x7B))  // F12
        assertEquals(62, P.vkToKeycode(0x20))   // Space
        assertEquals(111, P.vkToKeycode(0x1B))  // Esc
        assertEquals(61, P.vkToKeycode(0x09))   // Tab
        assertEquals(112, P.vkToKeycode(0x2E))  // Delete
    }

    @Test
    fun vkNoLetterCollisionWithMetaAndDigits() {
        // KEYCODE_A..Z are 29..54, KEYCODE_0..9 are 7..16: no overlap at all
        val letterCodes = (0x41..0x5A).map { P.vkToKeycode(it) }
        val digitCodes = (0x30..0x39).map { P.vkToKeycode(it) }
        assertEquals(26, letterCodes.toSet().size)
        assertEquals(10, digitCodes.toSet().size)
        assertTrue(letterCodes.intersect(digitCodes.toSet()).isEmpty())
    }

    @Test
    fun vkUnmappedIsDroppedNotGuessed() {
        assertEquals(0, P.vkToKeycode(0x00))
        assertEquals(0, P.vkToKeycode(0xFF))
        assertEquals(0, P.vkToKeycode(0x13))   // PAUSE
    }

    @Test
    fun modsToMetaStateBits() {
        assertEquals(0, P.modsToMetaState(0))
        assertEquals(android.view.KeyEvent.META_SHIFT_ON, P.modsToMetaState(P.MOD_SHIFT))
        assertEquals(android.view.KeyEvent.META_CTRL_ON, P.modsToMetaState(P.MOD_CTRL))
        assertEquals(android.view.KeyEvent.META_ALT_ON, P.modsToMetaState(P.MOD_ALT))
        assertEquals(
            android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_CTRL_ON or
                android.view.KeyEvent.META_ALT_ON,
            P.modsToMetaState(P.MOD_SHIFT or P.MOD_CTRL or P.MOD_ALT)
        )
    }

    // ---------------------------------------------------------------- chunk assembly

    @Test
    fun chunkAssemblerJoinsAndSplits() {
        val a = ChunkAssembler()
        assertNull(a.feed(Phase4Protocol.FLAG_MORE_FOLLOWS, byteArrayOf(1, 2, 3)))
        assertNull(a.feed(Phase4Protocol.FLAG_MORE_FOLLOWS, byteArrayOf(4, 5)))
        val msg = a.feed(0, byteArrayOf(6))
        assertNotNull(msg)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), msg)
        // after a complete message the assembler is reusable
        assertArrayEquals(byteArrayOf(9), a.feed(0, byteArrayOf(9)))
    }

    @Test
    fun chunkAssemblerRejectsOverCap() {
        val a = ChunkAssembler(maxMessage = 8)
        assertNull(a.feed(Phase4Protocol.FLAG_MORE_FOLLOWS, ByteArray(6)))
        try {
            a.feed(Phase4Protocol.FLAG_MORE_FOLLOWS, ByteArray(6))
            fail("over cap must throw")
        } catch (e: Phase4Protocol.ProtocolException) {
            assertTrue(e.message!!.contains("cap"))
        }
    }

    // ---------------------------------------------------------------- writer

    @Test
    fun priorityWriterReportsQueueDepthAndKeepsFlags() {
        val sink = java.io.ByteArrayOutputStream()
        val w = Phase4Protocol.PriorityWriter(sink)
        w.push(Phase4Protocol.Message.create(Phase4Protocol.CH_BENCH, ByteArray(200_000)))
        assertEquals(1, w.pendingCount(Phase4Protocol.CH_BENCH))
        assertEquals(0, w.pendingCount(Phase4Protocol.CH_VIDEO))
        w.push(Phase4Protocol.Message.create(Phase4Protocol.CH_VIDEO, ByteArray(100_000)))
        assertEquals(1, w.pendingCount(Phase4Protocol.CH_VIDEO))
        // input jumps the queue even behind a 200 KB bench message
        w.push(
            Phase4Protocol.Message.create(
                Phase4Protocol.CH_CONTROL, byteArrayOf(1, 2), Phase4Protocol.FLAG_KEEPALIVE_ECHO
            )
        )
        w.emitOne()
        val frames = Phase4Protocol.Decoder().feed(sink.toByteArray())
        assertEquals(Phase4Protocol.CH_CONTROL, frames[0].channel)
        assertEquals(Phase4Protocol.FLAG_KEEPALIVE_ECHO, frames[0].flags)
        // the big bench message is still there, chunked
        assertTrue(w.pendingCount(Phase4Protocol.CH_BENCH) >= 1)
    }

    @Test
    fun priorityWriterChunksStayUnderTheFrameCap() {
        val sink = java.io.ByteArrayOutputStream()
        val w = Phase4Protocol.PriorityWriter(sink)
        w.push(Phase4Protocol.Message.create(Phase4Protocol.CH_VIDEO, ByteArray(200_000) { 7 }))
        while (w.emitOne() != null) { /* drain */ }
        val frames = Phase4Protocol.Decoder().feed(sink.toByteArray())
        assertEquals(4, frames.size)   // 65536+65536+65536+3392
        assertEquals(Phase4Protocol.FLAG_MORE_FOLLOWS, frames[0].flags)
        assertEquals(0, frames[3].flags.toInt())
        val joined = ChunkAssembler()
        var done: ByteArray? = null
        for (f in frames) done = joined.feed(f.flags, f.payload)
        assertNotNull(done)
        assertEquals(200_000, done!!.size)
        assertEquals(7.toByte(), done[199_999])
    }

    // ---- B-047: the PC asks for video; the phone must parse the request
    @Test
    fun controlVideoOnParsesAndMatchesThePcConstant() {
        assertEquals("video-on", Phase5Protocol.CTRL_VIDEO_ON)
        assertEquals(Phase5Protocol.Control.VideoOn, Phase5Protocol.parseControl("video-on"))
        // trailing whitespace/newlines are tolerated
        assertEquals(Phase5Protocol.Control.VideoOn, Phase5Protocol.parseControl("  video-on\n"))
    }

    @Test
    fun controlParsesTheOtherMessagesExactly() {
        assertEquals(Phase5Protocol.Control.NeedKeyframe, Phase5Protocol.parseControl("need-keyframe"))
        assertEquals(Phase5Protocol.Control.ReleaseAll, Phase5Protocol.parseControl("release-all"))
        assertEquals(
            Phase5Protocol.Control.ClockSyncRequest("123456789"),
            Phase5Protocol.parseControl("csq 123456789")
        )
        assertEquals(
            Phase5Protocol.Control.PcVideoStats("vq 60 12"),
            Phase5Protocol.parseControl("vq 60 12")
        )
        // prefixes that only look like known messages are unknown, never guessed
        assertEquals(Phase5Protocol.Control.Unknown("video-on-now"), Phase5Protocol.parseControl("video-on-now"))
        assertEquals(Phase5Protocol.Control.Unknown("csq"), Phase5Protocol.parseControl("csq"))
        assertEquals(Phase5Protocol.Control.Unknown(""), Phase5Protocol.parseControl(""))
    }
}
