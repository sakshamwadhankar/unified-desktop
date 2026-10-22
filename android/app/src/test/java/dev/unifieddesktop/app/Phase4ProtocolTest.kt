package dev.unifieddesktop.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Wire-protocol tests; mirrors of the Rust protocol.rs test vectors. */
class Phase4ProtocolTest {
    private val P = Phase4Protocol

    @Test
    fun frameRoundTrip() {
        val f = P.encodeFrame(P.CH_INPUT, 0, "hello".toByteArray())
        assertEquals(P.HEADER_LEN + 5, f.size)
        assertEquals(1, f[0].toInt())
        assertEquals(P.CH_INPUT, f[1])
        assertEquals(5, ((f[4].toInt() and 0xFF) shl 24) or (f[5].toInt() and 0xFF) shl 16 or (f[6].toInt() and 0xFF) shl 8 or (f[7].toInt() and 0xFF))
        val msgs = Phase4Protocol.Decoder().feed(f)
        assertEquals(1, msgs.size)
        assertEquals(P.CH_INPUT, msgs[0].channel)
        assertArrayEquals("hello".toByteArray(), msgs[0].payload)
    }

    @Test
    fun rejectsOversizeAndUnknownChannel() {
        val big = ByteArray(P.MAX_FRAME_PAYLOAD + 1)
        try { P.encodeFrame(P.CH_VIDEO, 0, big); fail("expected chunk-cap error") } catch (_: Exception) {}
        val raw = P.encodeFrame(P.CH_VIDEO, 0, ByteArray(16))
        raw[4] = 0xFF.toByte()
        try { Phase4Protocol.Decoder().feed(raw); fail("expected oversize reject") } catch (e: Phase4Protocol.ProtocolException) {
            assertTrue(e.message!!.contains("chunk cap"))
        }
        try { Phase4Protocol.Message.create(9.toByte(), byteArrayOf(1, 2, 3)); fail("expected unknown channel") } catch (_: Exception) {}
        try { P.encodeFrame(9.toByte(), 0, byteArrayOf(1)); fail("expected unknown channel") } catch (_: Exception) {}
    }

    @Test
    fun decoderSplitsAcrossFeedBoundaries() {
        val f = P.encodeFrame(P.CH_BENCH, 0, "abcdefghijklmnopqrstuvwxyz".toByteArray())
        val dec = Phase4Protocol.Decoder()
        assertNull(dec.feed(f.copyOfRange(0, 3)).firstOrNull())
        assertNull(dec.feed(f.copyOfRange(3, 10)).firstOrNull())
        assertNull(dec.feed(f.copyOfRange(10, 12)).firstOrNull())
        val msgs = dec.feed(f.copyOfRange(12, f.size))
        assertEquals(1, msgs.size)
        assertArrayEquals(f.copyOfRange(8, f.size), msgs[0].payload)
        // and two frames in one feed both come out
        val two = f + f
        assertEquals(2, dec.feed(two).size)
    }

    @Test
    fun largeMessageIsChunked() {
        val msg = ByteArray(P.MAX_FRAME_PAYLOAD * 2 + 17) { 0xAB.toByte() }
        val sink = java.io.ByteArrayOutputStream()
        val w = Phase4Protocol.PriorityWriter(sink)
        w.push(Phase4Protocol.Message(P.CH_VIDEO, msg))
        while (w.emitOne() != null) {}
        val msgs = Phase4Protocol.Decoder().feed(sink.toByteArray())
        assertEquals(3, msgs.size)
        for ((i, m) in msgs.withIndex()) {
            assertEquals(P.CH_VIDEO, m.channel)
            assertTrue(m.payload.size <= P.MAX_FRAME_PAYLOAD)
            if (i != msgs.size - 1) assertEquals(P.MAX_FRAME_PAYLOAD, m.payload.size)
        }
        val total = msgs[0].payload.size + msgs[1].payload.size + msgs[2].payload.size
        assertEquals(P.MAX_FRAME_PAYLOAD * 2 + 17, total)
    }

    /**
     * The manager's required test: a 1 MiB bench logical message is draining chunk by
     * chunk and the input frame arrives BETWEEN two emits; it must hit the wire before
     * the SECOND bench chunk (an input frame never waits behind more than one 64 KiB
     * chunk of the big message).
     */
    @Test
    fun inputFrameQueuedBehindLargeBenchMessageWaitsAtMostOneChunk() {
        val sink = java.io.ByteArrayOutputStream()
        val w = Phase4Protocol.PriorityWriter(sink)
        w.push(Phase4Protocol.Message(P.CH_BENCH, ByteArray(P.MAX_MESSAGE_LEN)))
        w.emitOne() // bench chunk 1 leaves
        w.push(Phase4Protocol.Message(P.CH_INPUT, byteArrayOf(1, 2, 3))) // input arrives mid-drain
        while (w.emitOne() != null) {}
        val by = sink.toByteArray()
        val msgs = Phase4Protocol.Decoder().feed(by)
        assertTrue(msgs.size >= 17)
        assertEquals(P.CH_BENCH, msgs[0].channel)
        assertEquals(P.MAX_FRAME_PAYLOAD, msgs[0].payload.size)
        assertEquals(P.CH_INPUT, msgs[1].channel)
        assertArrayEquals(byteArrayOf(1, 2, 3), msgs[1].payload)
        val benchCount = msgs.count { it.channel == P.CH_BENCH }
        val expectedChunks = (P.MAX_MESSAGE_LEN + P.MAX_FRAME_PAYLOAD - 1) / P.MAX_FRAME_PAYLOAD
        assertEquals(expectedChunks, benchCount)
        // input frame (index 1) must appear before bench chunk 2 (index 2)
        assertEquals(P.CH_BENCH, msgs[2].channel)
    }

    @Test
    fun strictPriorityAcrossArrivals() {
        val sink = java.io.ByteArrayOutputStream()
        val w = Phase4Protocol.PriorityWriter(sink)
        w.push(Phase4Protocol.Message(P.CH_BENCH, byteArrayOf(4)))
        w.push(Phase4Protocol.Message(P.CH_CONTROL, byteArrayOf(3)))
        w.push(Phase4Protocol.Message(P.CH_INPUT, byteArrayOf(1)))
        w.push(Phase4Protocol.Message(P.CH_INPUT, byteArrayOf(2)))
        while (w.emitOne() != null) {}
        val msgs = Phase4Protocol.Decoder().feed(sink.toByteArray())
        assertEquals(listOf(1, 2, 3, 4), msgs.map { it.payload[0].toInt() })
        assertEquals(listOf(P.CH_INPUT, P.CH_INPUT, P.CH_CONTROL, P.CH_BENCH), msgs.map { it.channel })
    }

    @Test
    fun keepaliveFlagEchoShapedRight() {
        val f = P.encodeFrame(P.CH_CONTROL, P.FLAG_KEEPALIVE_ECHO, ByteArray(0))
        assertEquals(P.HEADER_LEN, f.size)
        assertEquals(P.FLAG_KEEPALIVE_ECHO, f[2])
        val msgs = Phase4Protocol.Decoder().feed(f)
        // Empty payload frame - payload is empty
        assertEquals(0, msgs[0].payload.size)
        assertTrue(msgs[0].payload.isEmpty())
    }

    // ---- pairing channel gate (manager review 2026-10-18): the decision is a pure
    // function so an unpaired PC can never use the data channels. -----------------

    /** Unpaired (no prompt up yet): only the control channel passes. */
    @Test
    fun gateUnpairedAllowsOnlyControl() {
        assertTrue(P.mayProcessChannel(P.CH_CONTROL, Phase4Protocol.Gate.UNPAIRED))
        assertFalse(P.mayProcessChannel(P.CH_BENCH, Phase4Protocol.Gate.UNPAIRED))
        assertFalse(P.mayProcessChannel(P.CH_INPUT, Phase4Protocol.Gate.UNPAIRED))
        assertFalse(P.mayProcessChannel(P.CH_VIDEO, Phase4Protocol.Gate.UNPAIRED))
    }

    /** Pairing pending (prompt on screen, no Yes yet): only the control channel passes. */
    @Test
    fun gatePairingPendingAllowsOnlyControl() {
        assertTrue(P.mayProcessChannel(P.CH_CONTROL, Phase4Protocol.Gate.PAIRING))
        assertFalse(P.mayProcessChannel(P.CH_BENCH, Phase4Protocol.Gate.PAIRING))
        assertFalse(P.mayProcessChannel(P.CH_INPUT, Phase4Protocol.Gate.PAIRING))
        assertFalse(P.mayProcessChannel(P.CH_VIDEO, Phase4Protocol.Gate.PAIRING))
    }

    /** Paired (pinned, or Yes tapped in this session): all four channels pass. */
    @Test
    fun gatePairedAllowsAllChannels() {
        for (ch in byteArrayOf(P.CH_CONTROL, P.CH_BENCH, P.CH_INPUT, P.CH_VIDEO)) {
            assertTrue("channel ${P.channelName(ch)} must pass when paired", P.mayProcessChannel(ch, Phase4Protocol.Gate.PAIRED))
        }
    }

    // ---- dead-link watchdog (manager review 2026-10-18): pure predicate, tested. ----

    /** Inside the window nothing is closed (a healthy keep-alive gap stays open). */
    @Test
    fun watchdogStaysQuietWithinWindow() {
        assertFalse(P.watchdogExpired(1_000, 1_000 + P.DEAD_TIMEOUT_MS))
        assertFalse(P.watchdogExpired(1_000, 1_000 + P.DEAD_TIMEOUT_MS - 1))
    }

    /** Past the window the supervisor closes the socket (unblocks a stuck write()). */
    @Test
    fun watchdogExpiresPastWindow() {
        assertTrue(P.watchdogExpired(1_000, 1_000 + P.DEAD_TIMEOUT_MS + 1))
    }

    // ---- Phase 11 (D-a): audio / clip / file / cam channels and their priority ----

    @Test
    fun phase11ChannelsRoundTripAndRejectUnknown() {
        for (ch in byteArrayOf(P.CH_AUDIO, P.CH_CLIP, P.CH_FILE, P.CH_CAM)) {
            assertTrue("channel $ch must be valid", P.isValidChannel(ch))
            val f = P.encodeFrame(ch, 0, "p11".toByteArray())
            val msgs = Phase4Protocol.Decoder().feed(f)
            assertEquals(1, msgs.size)
            assertEquals(ch, msgs[0].channel)
            assertArrayEquals("p11".toByteArray(), msgs[0].payload)
            assertTrue("channel $ch must have a name", P.channelName(ch) != "unknown")
        }
        assertFalse(P.isValidChannel(9))
        try {
            Phase4Protocol.Message.create(9, byteArrayOf(1))
            fail("channel 9 must be refused")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    /** audio + clip preempt video, cam + file follow it, all before bench. */
    @Test
    fun phase11StrictPriorityOrder() {
        val out = java.io.ByteArrayOutputStream()
        val w = Phase4Protocol.PriorityWriter(out)
        for (ch in byteArrayOf(P.CH_BENCH, P.CH_FILE, P.CH_CAM, P.CH_VIDEO, P.CH_CLIP, P.CH_AUDIO, P.CH_CONTROL, P.CH_INPUT)) {
            w.push(Phase4Protocol.Message.create(ch, byteArrayOf(ch)))
        }
        w.emitAll()
        val frames = Phase4Protocol.Decoder().feed(out.toByteArray())
        assertEquals(
            listOf(P.CH_INPUT, P.CH_CONTROL, P.CH_AUDIO, P.CH_CLIP, P.CH_VIDEO, P.CH_CAM, P.CH_FILE, P.CH_BENCH),
            frames.map { it.channel }
        )
    }

    /** A 1 MiB CH_FILE message splits into 16 wire chunks and rejoins byte-identical. */
    @Test
    fun phase11FileMessageChunksAndRejoins() {
        val data = ByteArray(Phase4Protocol.MAX_MESSAGE_LEN) { (it % 251).toByte() }
        val out = java.io.ByteArrayOutputStream()
        val w = Phase4Protocol.PriorityWriter(out)
        w.push(Phase4Protocol.Message.create(P.CH_FILE, data))
        w.emitAll()
        val frames = Phase4Protocol.Decoder().feed(out.toByteArray())
        assertEquals(16, frames.size)
        val joined = java.io.ByteArrayOutputStream()
        for (f in frames) {
            assertEquals(P.CH_FILE, f.channel)
            joined.write(f.payload)
        }
        assertArrayEquals(data, joined.toByteArray())
    }
}
