package dev.unifieddesktop.app

import java.util.ArrayDeque

/**
 * Phase-4 wire protocol (docs/PHASE4_PROTOCOL.md) - the exact mirror of pc/udreceiver's
 * protocol.rs. Frame: [u8 version][u8 channel][u8 flags][u8 res][u32 BE payload len] +
 * payload. On-the-wire chunks are capped at 64 KiB; larger logical messages stream with
 * the MORE_FOLLOWS flag; the priority writer re-takes the channel decision before every
 * chunk so a small input/control frame never waits behind more than one chunk.
 *
 * Channels: 1 control, 2 input, 3 video, 4 bench (priority input > control > video > bench).
 */
object Phase4Protocol {
    const val VERSION: Byte = 1
    const val HEADER_LEN = 8
    const val MAX_FRAME_PAYLOAD = 65536
    const val MAX_MESSAGE_LEN = 1024 * 1024

    const val CH_CONTROL: Byte = 1
    const val CH_INPUT: Byte = 2
    const val CH_VIDEO: Byte = 3
    const val CH_BENCH: Byte = 4

    const val FLAG_KEEPALIVE_ECHO: Byte = 0b1
    const val FLAG_MORE_FOLLOWS: Byte = 0b10

    /** Link-dead policy (docs/PHASE4_PROTOCOL.md): no inbound frame for this long = dead. */
    const val DEAD_TIMEOUT_MS = 15_000L

    /** Strict priority: input > control > video > bench (OPTIMIZATION 5). */
    val CHANNEL_PRIORITY = byteArrayOf(CH_INPUT, CH_CONTROL, CH_VIDEO, CH_BENCH)

    fun channelName(ch: Byte) = when (ch) {
        CH_CONTROL -> "control"
        CH_INPUT -> "input"
        CH_VIDEO -> "video"
        CH_BENCH -> "bench"
        else -> "unknown"
    }

    class Message internal constructor(
        val channel: Byte,
        val payload: ByteArray,
        /** Base flags for every frame of this message (Phase-5 keep-alive echo). */
        val flags: Byte = 0,
        internal var offset: Int = 0,
    ) {
        val isFinished: Boolean get() = offset >= payload.size

        companion object {
            fun create(channel: Byte, payload: ByteArray, flags: Byte = 0): Message {
                require(isValidChannel(channel)) { "unknown channel $channel" }
                return Message(channel, payload, flags)
            }
        }
    }

    class ProtocolException(message: String) : Exception(message)

    fun isValidChannel(ch: Byte) = ch == CH_CONTROL || ch == CH_INPUT || ch == CH_VIDEO || ch == CH_BENCH

    /**
     * Pairing gate state of one session (manager review 2026-10-18): a PC that is not
     * pinned yet may use the control channel only (pairing frames + keep-alive); bench
     * (data), input and video frames are dropped until the user taps Yes (this session
     * becomes PAIRED) or the PC is already pinned (PAIRED from the first frame).
     */
    enum class Gate {
        /** No pin for this PC and no prompt shown (transient start state): control only. */
        UNPAIRED,

        /** The pairing prompt is on screen; the user has not tapped yet: control only. */
        PAIRING,

        /** PC pinned (trusted store) or user tapped Yes in this session: all channels. */
        PAIRED,
    }

    /** Pure channel gate: may a frame on [channel] be processed in [gate] state? */
    fun mayProcessChannel(channel: Byte, gate: Gate): Boolean = when (gate) {
        Gate.PAIRED -> true
        Gate.PAIRING -> channel == CH_CONTROL
        Gate.UNPAIRED -> channel == CH_CONTROL
    }

    /**
     * Watchdog predicate: a session armed for the dead-link rule is expired when nothing
     * has come in for [DEAD_TIMEOUT_MS]. Pure function so it is unit-testable (the
     * supervisor thread that calls it lives in Phase4LinkService).
     */
    fun watchdogExpired(lastInboundMs: Long, nowMs: Long): Boolean =
        nowMs - lastInboundMs > DEAD_TIMEOUT_MS

    /** One on-the-wire frame: header + payload (payload must respect the chunk cap). */
    fun encodeFrame(channel: Byte, flags: Byte, payload: ByteArray): ByteArray {
        require(isValidChannel(channel)) { "unknown channel $channel" }
        require(payload.size <= MAX_FRAME_PAYLOAD) { "payload ${payload.size} > chunk cap" }
        val out = ByteArray(HEADER_LEN + payload.size)
        out[0] = VERSION
        out[1] = channel
        out[2] = flags
        out[3] = 0
        val len = payload.size
        out[4] = ((len ushr 24) and 0xFF).toByte()
        out[5] = ((len ushr 16) and 0xFF).toByte()
        out[6] = ((len ushr 8) and 0xFF).toByte()
        out[7] = (len and 0xFF).toByte()
        System.arraycopy(payload, 0, out, HEADER_LEN, len)
        return out
    }

    /** Stateful byte-stream -> frames decoder (drops nothing; counts exhausted bytes). */
    class Decoder {
        private val buf = java.io.ByteArrayOutputStream()

        fun feed(data: ByteArray): List<Message> {
            buf.write(data)
            val bytes = buf.toByteArray()
            val out = ArrayList<Message>()
            var pos = 0
            while (bytes.size - pos >= HEADER_LEN) {
                val version = bytes[pos]
                val channel = bytes[pos + 1]
                val flags = bytes[pos + 2]
                val plen = (
                    ((bytes[pos + 4].toInt() and 0xFF).toLong() shl 24) or
                    ((bytes[pos + 5].toInt() and 0xFF).toLong() shl 16) or
                    ((bytes[pos + 6].toInt() and 0xFF).toLong() shl 8) or
                    (bytes[pos + 7].toInt() and 0xFF).toLong()
                )
                if (version != VERSION) throw ProtocolException("version $version != $VERSION")
                if (plen > MAX_FRAME_PAYLOAD) throw ProtocolException("payload $plen > chunk cap")
                if (!isValidChannel(channel)) throw ProtocolException("channel $channel")
                if (bytes.size - pos < HEADER_LEN + plen) break
                val plenInt = plen.toInt()
                val payload = bytes.copyOfRange(pos + HEADER_LEN, pos + HEADER_LEN + plenInt)
                out.add(Message(channel, payload, flags))
                pos += HEADER_LEN + plenInt
            }
            if (pos == bytes.size) {
                buf.reset()
            } else {
                buf.reset()
                buf.write(bytes, pos, bytes.size - pos)
            }
            return out
        }

        /** feed a slice of a reusable read buffer (the common network case) */
        fun feed(data: ByteArray, len: Int): List<Message> =
            feed(if (len == data.size) data else data.copyOf(len))
    }

    /**
     * Chunked priority writer mirroring protocol.rs: in-progress messages stay at their
     * queue front; the priority decision is re-taken before every chunk.
     *
     * Phase 5 made it thread-safe and non-blocking for producers: queue access happens
     * under [qLock] (a short critical section) while the socket write happens OUTSIDE it,
     * so a stalled link can never block an encoder/input thread pushing a message.
     */
    class PriorityWriter<W : java.io.OutputStream>(val out: W) {
        private val queues = HashMap<Byte, ArrayDeque<Message>>()
        private val qLock = Object()

        init { for (ch in CHANNEL_PRIORITY) queues[ch] = ArrayDeque() }

        fun push(msg: Message): Boolean {
            require(isValidChannel(msg.channel)) { "unknown channel ${msg.channel}" }
            require(msg.payload.size <= MAX_MESSAGE_LEN) { "message too large" }
            synchronized(qLock) { queues[msg.channel]!!.addLast(msg) }
            return true
        }

        val isEmpty: Boolean
            get() = synchronized(qLock) { queues.values.all { it.isEmpty() } }

        /** How many whole messages are waiting on [ch] (Phase-5 video drop policy). */
        fun pendingCount(ch: Byte): Int = synchronized(qLock) { queues[ch]?.size ?: 0 }

        /**
         * Emit one frame: the highest-priority channel, one chunk per call. Call it from ONE
         * thread only (Phase 5: the session's drain loop): the socket write happens outside
         * the queue lock, so two emitters could put a message's chunks out of order.
         */
        fun emitOne(): Int? {
            val frame = synchronized(qLock) {
                for (ch in CHANNEL_PRIORITY) {
                    val q = queues[ch]!!
                    val head = q.peekFirst() ?: continue
                    val start = head.offset
                    val end = (head.offset + MAX_FRAME_PAYLOAD).coerceAtMost(head.payload.size)
                    val more = if (end < head.payload.size) FLAG_MORE_FOLLOWS else 0.toByte()
                    val f = encodeFrame(ch, (head.flags.toInt() or more.toInt()).toByte(), head.payload.copyOfRange(start, end))
                    head.offset += end - start
                    if (head.isFinished) q.removeFirst()
                    return@synchronized f
                }
                null
            } ?: return null
            out.write(frame)
            return frame.size
        }

        fun emitAll() {
            while (emitOne() != null) { /* drain */ }
            out.flush()
        }
    }
}
