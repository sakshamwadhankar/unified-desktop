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

    /** Strict priority: input > control > video > bench (OPTIMIZATION 5). */
    val CHANNEL_PRIORITY = byteArrayOf(CH_INPUT, CH_CONTROL, CH_VIDEO, CH_BENCH)

    fun channelName(ch: Byte) = when (ch) {
        CH_CONTROL -> "control"
        CH_INPUT -> "input"
        CH_VIDEO -> "video"
        CH_BENCH -> "bench"
        else -> "unknown"
    }

    class Message internal constructor(val channel: Byte, val payload: ByteArray, internal var offset: Int = 0) {
        val isFinished: Boolean get() = offset >= payload.size

        companion object {
            fun create(channel: Byte, payload: ByteArray): Message {
                require(isValidChannel(channel)) { "unknown channel $channel" }
                return Message(channel, payload)
            }
        }
    }

    class ProtocolException(message: String) : Exception(message)

    fun isValidChannel(ch: Byte) = ch == CH_CONTROL || ch == CH_INPUT || ch == CH_VIDEO || ch == CH_BENCH

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
                out.add(Message(channel, payload))
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
     */
    class PriorityWriter<W : java.io.OutputStream>(val out: W) {
        private val queues = HashMap<Byte, ArrayDeque<Message>>()

        init { for (ch in CHANNEL_PRIORITY) queues[ch] = ArrayDeque() }

        fun push(msg: Message): Boolean {
            require(isValidChannel(msg.channel)) { "unknown channel ${msg.channel}" }
            require(msg.payload.size <= MAX_MESSAGE_LEN) { "message too large" }
            queues[msg.channel]!!.addLast(msg)
            return true
        }

        val isEmpty: Boolean get() = queues.values.all { it.isEmpty() }

        /** Emit one frame: the highest-priority channel, one chunk per call. */
        fun emitOne(): Int? {
            for (ch in CHANNEL_PRIORITY) {
                val q = queues[ch]!!
                val head = q.peekFirst() ?: continue
                val start = head.offset
                val end = (head.offset + MAX_FRAME_PAYLOAD).coerceAtMost(head.payload.size)
                val flags = if (end < head.payload.size) FLAG_MORE_FOLLOWS else 0
                val frame = encodeFrame(ch, flags, head.payload.copyOfRange(start, end))
                out.write(frame)
                head.offset += end - start
                if (head.isFinished) q.removeFirst()
                return frame.size
            }
            return null
        }

        fun emitAll() {
            while (emitOne() != null) { /* drain */ }
            out.flush()
        }
    }
}
