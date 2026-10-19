package dev.unifieddesktop.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase-5 wire formats (video + input + control), the exact mirror of
 * pc/udreceiver/src/phase5.rs. Pure data: no Android API, so it is unit-testable.
 *
 * Video (channel 3): every logical message is `header | Annex-B access unit`.
 *   offset size field
 *   0      1    format version (1)
 *   1      1    flags: bit0 = keyframe, bit1 = codec-config (SPS/PPS) frame
 *   2      2    reserved (0)
 *   4      4    frame sequence, big-endian u32 (wraps)
 *   8      8    encoder presentation time in microseconds, big-endian i64
 *   16     8    phone nanoTime when the encoder released the buffer, big-endian i64
 *   --------------------------------------------- 24 bytes
 *   >=24        Annex-B access unit bytes
 * Large access units are split into 64 KiB chunks by the Phase-4 writer with
 * MORE_FOLLOWS; the receiver (and [ChunkAssembler]) joins them back.
 *
 * Input (channel 2): a message is a concatenation of events, each one starts with a
 * type byte. Pointer moves may be coalesced, everything else never is.
 *   1 MOVE        [1][x u16][y u16]                       5
 *   2 BUTTON      [2][btn u8][down u8][x u16][y u16][t_pc u32]  11
 *   3 WHEEL       [3][delta i16][x u16][y u16][t_pc u32]  11
 *   4 KEY         [4][down u8][vk u8][scan u8][mods u8][t_pc u32] 9
 *   5 RELEASE_ALL [5]                                     1
 * `t_pc` is the low 32 bits of the PC's microsecond monotonic clock; the clock-sync
 * exchange on the control channel turns it into phone-clock microseconds.
 *
 * Control (channel 1) payload = UTF-8 text (Phase-4 style):
 *   "need-keyframe"                 PC -> phone: send a keyframe now
 *   "csq <t_pc_us>"                 PC -> phone: clock-sync request
 *   "csr <t_pc_us> <t_phone_us>"    phone -> PC: clock-sync reply
 *   "vstat <seq> <pts_us> <enc_done_ns>"  phone -> PC, once a second: video stats
 *   "istat <applied> <dropped> <keys_down>" phone -> PC, once a second: input stats
 */
object Phase5Protocol {
    const val VIDEO_VERSION: Byte = 1
    const val VIDEO_HEADER_LEN = 24

    const val VFLAG_KEYFRAME: Byte = 0b1
    const val VFLAG_CONFIG: Byte = 0b10

    const val DESKTOP_W = 1920
    const val DESKTOP_H = 1080

    const val EVT_MOVE: Byte = 1
    const val EVT_BUTTON: Byte = 2
    const val EVT_WHEEL: Byte = 3
    const val EVT_KEY: Byte = 4
    const val EVT_RELEASE_ALL: Byte = 5

    const val BTN_LEFT = 0
    const val BTN_RIGHT = 1
    const val BTN_MIDDLE = 2

    const val MOD_SHIFT = 0b1
    const val MOD_CTRL = 0b10
    const val MOD_ALT = 0b100
    const val MOD_META = 0b1000

    const val CTRL_NEED_KEYFRAME = "need-keyframe"
    const val CTRL_RELEASE_ALL = "release-all"

    /**
     * B-047: the PC asks for video with this message (`udreceiver view` sends it after the
     * channel gate is open). `pair` and `bench` never send it, so they carry no video.
     */
    const val CTRL_VIDEO_ON = "video-on"

    /** A parsed control-channel message. Pure data: unit-testable (Phase5ProtocolTest). */
    sealed class Control {
        object NeedKeyframe : Control()
        object ReleaseAll : Control()
        object VideoOn : Control()
        /** `csq <t_pc_us>`; the stamp is echoed back verbatim in `csr`. */
        data class ClockSyncRequest(val pcStamp: String) : Control()
        /** `vq ...`: the PC's own video stats, logged on this side. */
        data class PcVideoStats(val text: String) : Control()
        data class Unknown(val text: String) : Control()
    }

    fun parseControl(text: String): Control {
        val t = text.trim()
        return when {
            t == CTRL_NEED_KEYFRAME -> Control.NeedKeyframe
            t == CTRL_RELEASE_ALL -> Control.ReleaseAll
            t == CTRL_VIDEO_ON -> Control.VideoOn
            t.startsWith("csq ") -> Control.ClockSyncRequest(t.removePrefix("csq ").trim())
            t.startsWith("vq ") -> Control.PcVideoStats(t)
            else -> Control.Unknown(t)
        }
    }

    class VideoFrame(
        val flags: Byte,
        val seq: Int,
        val ptsUs: Long,
        val encodeDoneNs: Long,
        val annexB: ByteArray,
    ) {
        val isKeyframe: Boolean get() = (flags.toInt() and VFLAG_KEYFRAME.toInt()) != 0
        val isConfig: Boolean get() = (flags.toInt() and VFLAG_CONFIG.toInt()) != 0
    }

    fun packVideoFrame(flags: Byte, seq: Int, ptsUs: Long, encodeDoneNs: Long, annexB: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(VIDEO_HEADER_LEN + annexB.size).order(ByteOrder.BIG_ENDIAN)
        out.put(VIDEO_VERSION)
        out.put(flags)
        out.putShort(0)
        out.putInt(seq)
        out.putLong(ptsUs)
        out.putLong(encodeDoneNs)
        out.put(annexB)
        return out.array()
    }

    fun parseVideoFrame(msg: ByteArray): VideoFrame {
        require(msg.size >= VIDEO_HEADER_LEN) { "video message ${msg.size} < header" }
        val b = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN)
        val ver = b.get()
        require(ver == VIDEO_VERSION) { "video version $ver != $VIDEO_VERSION" }
        val flags = b.get()
        b.getShort() // reserved
        val seq = b.getInt()
        val pts = b.getLong()
        val done = b.getLong()
        val au = ByteArray(msg.size - VIDEO_HEADER_LEN)
        b.get(au)
        return VideoFrame(flags, seq, pts, done, au)
    }

    sealed class InputEvent {
        data class Move(val x: Int, val y: Int) : InputEvent()
        data class Button(val button: Int, val down: Boolean, val x: Int, val y: Int, val tPcUs: Long) : InputEvent()
        data class Wheel(val delta: Int, val x: Int, val y: Int, val tPcUs: Long) : InputEvent()
        data class Key(val down: Boolean, val vk: Int, val scan: Int, val mods: Int, val tPcUs: Long) : InputEvent()
        object ReleaseAll : InputEvent()
    }

    /** Serialize a batch of events into one input-channel message. */
    fun packInputEvents(events: List<InputEvent>): ByteArray {
        var size = 0
        for (e in events) size += when (e) {
            is InputEvent.Move -> 5
            is InputEvent.Button -> 11
            is InputEvent.Wheel -> 11
            is InputEvent.Key -> 9
            InputEvent.ReleaseAll -> 1
        }
        val out = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        for (e in events) when (e) {
            is InputEvent.Move -> { out.put(EVT_MOVE); out.putShort(e.x.toShort()); out.putShort(e.y.toShort()) }
            is InputEvent.Button -> {
                out.put(EVT_BUTTON); out.put(e.button.toByte()); out.put(if (e.down) 1 else 0)
                out.putShort(e.x.toShort()); out.putShort(e.y.toShort()); out.putInt(e.tPcUs.toInt())
            }
            is InputEvent.Wheel -> {
                out.put(EVT_WHEEL); out.putShort(e.delta.toShort())
                out.putShort(e.x.toShort()); out.putShort(e.y.toShort()); out.putInt(e.tPcUs.toInt())
            }
            is InputEvent.Key -> {
                out.put(EVT_KEY); out.put(if (e.down) 1 else 0); out.put(e.vk.toByte())
                out.put(e.scan.toByte()); out.put(e.mods.toByte()); out.putInt(e.tPcUs.toInt())
            }
            InputEvent.ReleaseAll -> out.put(EVT_RELEASE_ALL)
        }
        return out.array()
    }

    /** Parse one input-channel message into its events. Trailing garbage is an error. */
    fun parseInputEvents(msg: ByteArray): List<InputEvent> {
        val b = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN)
        val out = ArrayList<InputEvent>()
        while (b.remaining() > 0) {
            when (val type = b.get()) {
                EVT_MOVE -> {
                    require(b.remaining() >= 4) { "truncated move" }
                    out.add(InputEvent.Move(b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF))
                }
                EVT_BUTTON -> {
                    require(b.remaining() >= 9) { "truncated button" }
                    val btn = b.get().toInt()
                    val down = b.get().toInt() != 0
                    val x = b.short.toInt() and 0xFFFF
                    val y = b.short.toInt() and 0xFFFF
                    out.add(InputEvent.Button(btn, down, x, y, b.int.toLong() and 0xFFFFFFFFL))
                }
                EVT_WHEEL -> {
                    require(b.remaining() >= 9) { "truncated wheel" }
                    val d = b.short.toInt()
                    val x = b.short.toInt() and 0xFFFF
                    val y = b.short.toInt() and 0xFFFF
                    out.add(InputEvent.Wheel(d, x, y, b.int.toLong() and 0xFFFFFFFFL))
                }
                EVT_KEY -> {
                    require(b.remaining() >= 8) { "truncated key" }
                    val down = b.get().toInt() != 0
                    val vk = b.get().toInt() and 0xFF
                    val scan = b.get().toInt() and 0xFF
                    val mods = b.get().toInt() and 0xFF
                    out.add(InputEvent.Key(down, vk, scan, mods, b.int.toLong() and 0xFFFFFFFFL))
                }
                EVT_RELEASE_ALL -> out.add(InputEvent.ReleaseAll)
                else -> throw IllegalArgumentException("unknown input event type $type")
            }
        }
        return out
    }

    /** Windows VK code -> Android KeyEvent.KEYCODE_*. 0 = not mapped (dropped). */
    fun vkToKeycode(vk: Int): Int = when {
        vk in 0x41..0x5A -> 29 + (vk - 0x41)        // A..Z -> KEYCODE_A(29)..KEYCODE_Z(54)
        vk in 0x30..0x39 -> 7 + (vk - 0x30)         // 0..9 -> KEYCODE_0(7)..KEYCODE_9(16)
        vk in 0x70..0x7B -> 131 + (vk - 0x70)       // F1..F12 -> KEYCODE_F1(131)..KEYCODE_F12(142)
        vk in 0x60..0x69 -> 7 + (vk - 0x60)         // numpad 0..9 -> KEYCODE_0..9
        else -> when (vk) {
            0x0D -> 66      // ENTER
            0x08 -> 67      // BACKSPACE
            0x09 -> 61      // TAB
            0x1B -> 111     // ESCAPE
            0x20 -> 62      // SPACE
            0x2E -> 112     // DELETE -> FORWARD_DEL
            0x25 -> 21      // LEFT
            0x26 -> 19      // UP
            0x27 -> 22      // RIGHT
            0x28 -> 20      // DOWN
            0x24 -> 122     // HOME
            0x23 -> 123     // END
            0x21 -> 92      // PRIOR -> PAGE_UP
            0x22 -> 93      // NEXT -> PAGE_DOWN
            0x10 -> 59      // SHIFT -> SHIFT_LEFT
            0x11 -> 113     // CONTROL -> CTRL_LEFT
            0x12 -> 57      // MENU (Alt) -> ALT_LEFT
            0x5B, 0x5C -> 117 // LWIN/RWIN -> META_LEFT
            0x14 -> 115     // CAPITAL -> CAPS_LOCK
            0x2D -> 124     // INSERT
            0xBA -> 74      // OEM_1 ';' -> SEMICOLON
            0xBB -> 81      // OEM_PLUS '=' -> EQUALS
            0xBC -> 55      // OEM_COMMA ',' -> COMMA
            0xBD -> 69      // OEM_MINUS '-' -> MINUS
            0xBE -> 56      // OEM_PERIOD '.' -> PERIOD
            0xBF -> 76      // OEM_2 '/' -> SLASH
            0xC0 -> 68      // OEM_3 '`' -> GRAVE
            0xDB -> 71      // OEM_4 '[' -> LEFT_BRACKET
            0xDC -> 73      // OEM_5 '\' -> BACKSLASH
            0xDD -> 72      // OEM_6 ']' -> RIGHT_BRACKET
            0xDE -> 75      // OEM_7 '\'' -> APOSTROPHE
            // Non-ASCII printable keys never map: the PC sends shifted characters as
            // text separately (B-039 family), so an unmapped VK is dropped, not guessed.
            else -> 0
        }
    }

    /**
     * The US-layout character an Android keycode + meta state produces, or NUL when the key
     * is not printable. Pure math so the shifted-punctuation mapping is unit-tested
     * (Phase5ProtocolTest.shiftedPunctuationVkToChar) instead of relying on the framework's
     * KeyCharacterMap: VK 0xBA + Shift must be ':', VK 0x31 + Shift must be '!', and so on
     * (BUGS B-059/B-062).
     */
    fun keyCodeToChar(keyCode: Int, meta: Int): Char {
        val shift = (meta and android.view.KeyEvent.META_SHIFT_ON) != 0
        if (keyCode in 29..54) { // A..Z
            val c = 'a' + (keyCode - 29)
            return if (shift) c.uppercaseChar() else c
        }
        if (keyCode in 7..16) { // 0..9
            val digit = keyCode - 7
            return if (shift) ")!@#$%^&*("[digit] else ('0' + digit)
        }
        if (keyCode == 62) return ' '
        return when (keyCode) {
            74 -> if (shift) ':' else ';'      // SEMICOLON
            81 -> if (shift) '+' else '='      // EQUALS
            55 -> if (shift) '<' else ','      // COMMA
            69 -> if (shift) '_' else '-'      // MINUS
            56 -> if (shift) '>' else '.'      // PERIOD
            76 -> if (shift) '?' else '/'      // SLASH
            68 -> if (shift) '~' else '`'      // GRAVE
            71 -> if (shift) '{' else '['      // LEFT_BRACKET
            73 -> if (shift) '|' else '\\'     // BACKSLASH
            72 -> if (shift) '}' else ']'      // RIGHT_BRACKET
            75 -> if (shift) '"' else '\''     // APOSTROPHE
            else -> '\u0000'
        }
    }

    /** Android meta state for a PC modifier bitmask. */
    fun modsToMetaState(mods: Int): Int {
        var meta = 0
        if (mods and MOD_SHIFT != 0) meta = meta or android.view.KeyEvent.META_SHIFT_ON
        if (mods and MOD_CTRL != 0) meta = meta or android.view.KeyEvent.META_CTRL_ON
        if (mods and MOD_ALT != 0) meta = meta or android.view.KeyEvent.META_ALT_ON
        if (mods and MOD_META != 0) meta = meta or android.view.KeyEvent.META_META_ON
        return meta
    }

    /**
     * Join MORE_FOLLOWS chunks of one channel back into whole messages. Phase-4 chunks
     * are at most 64 KiB, a video access unit is often larger, so the receiver must
     * reassemble before it can parse. Tested in Phase5ProtocolTest.
     */
    class ChunkAssembler(private val maxMessage: Int = Phase4Protocol.MAX_MESSAGE_LEN) {
        private val buf = java.io.ByteArrayOutputStream()

        /** Returns a complete message when [flags] closes one, else null. */
        fun feed(flags: Byte, payload: ByteArray): ByteArray? {
            if (buf.size() + payload.size > maxMessage) {
                buf.reset()
                throw Phase4Protocol.ProtocolException("assembled message over cap")
            }
            buf.write(payload)
            val more = (flags.toInt() and Phase4Protocol.FLAG_MORE_FOLLOWS.toInt()) != 0
            if (more) return null
            val out = buf.toByteArray()
            buf.reset()
            return out
        }
    }
}
