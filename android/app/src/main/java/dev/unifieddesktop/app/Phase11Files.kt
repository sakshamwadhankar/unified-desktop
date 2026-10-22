package dev.unifieddesktop.app

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 11 D-d: file transfer over CH_FILE, phone half. The wire format is the mirror of
 * pc/udreceiver/src/files.rs:
 *
 *   START: [1][u32 id][u64 size][u16 nameLen][name][32-byte SHA-256]
 *   DATA:  [2][u32 id][bytes]      (at most 65528 bytes, so one DATA is one wire frame)
 *   END:   [3][u32 id]
 *
 * Incoming files land in <filesDir>/desktop (the Files app's folder); outgoing files are
 * chosen in the Files window ("Send to PC"). A .part file is verified before it is
 * renamed, so a broken transfer never leaves a half file under the real name.
 */
object Phase11Files {
    const val MAX_DATA = 65536 - 8
    private val nextId = AtomicInteger(1)

    fun encodeStart(id: Int, size: Long, name: String, sha: ByteArray): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + 4 + 8 + 2 + n.size + 32)
        out[0] = 1
        putInt(out, 1, id)
        putLong(out, 5, size)
        out[13] = ((n.size ushr 8) and 0xFF).toByte()
        out[14] = (n.size and 0xFF).toByte()
        System.arraycopy(n, 0, out, 15, n.size)
        System.arraycopy(sha, 0, out, 15 + n.size, 32)
        return out
    }

    fun encodeData(id: Int, data: ByteArray, off: Int, len: Int): ByteArray {
        val out = ByteArray(5 + len)
        out[0] = 2
        putInt(out, 1, id)
        System.arraycopy(data, off, out, 5, len)
        return out
    }

    fun encodeEnd(id: Int): ByteArray {
        val out = ByteArray(5)
        out[0] = 3
        putInt(out, 1, id)
        return out
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }

    fun safeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.filter { !it.isISOControl() }
        return if (clean.isEmpty() || clean == "." || clean == "..") "file" else clean
    }

    fun uniqueFile(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (n in 2 until 10_000) {
            val f = File(dir, "$stem ($n)$ext")
            if (!f.exists()) return f
        }
        return File(dir, "$stem (overflow)$ext")
    }

    /** Sends one file as CH_FILE messages; `push` must return false when the link is gone. */
    fun sendFile(file: File, push: (ByteArray) -> Boolean): Long {
        require(file.isFile) { "not a file: $file" }
        val id = nextId.getAndIncrement()
        val sha = sha256(file)
        if (!push(encodeStart(id, file.length(), file.name, sha))) return -1
        var total = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(MAX_DATA)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                if (!push(encodeData(id, buf, 0, n))) return -1
                total += n
            }
        }
        if (!push(encodeEnd(id))) return -1
        return total
    }

    /** One incoming transfer at a time; returns the saved file on a verified END. */
    class Sink(private val dir: File) {
        private var id = 0
        private var size = 0L
        private var name = ""
        private var sha: ByteArray = ByteArray(32)
        private var part: File? = null
        private var md: MessageDigest? = null
        private var got = 0L

        fun onMessage(msg: ByteArray): File? {
            if (msg.size < 5) throw IllegalArgumentException("short file message")
            val mid = getInt(msg, 1)
            when (msg[0].toInt()) {
                1 -> {
                    size = getLong(msg, 5)
                    val nl = ((msg[13].toInt() and 0xFF) shl 8) or (msg[14].toInt() and 0xFF)
                    name = safeName(String(msg, 15, nl, Charsets.UTF_8))
                    sha = msg.copyOfRange(15 + nl, 15 + nl + 32)
                    id = mid
                    dir.mkdirs()
                    part = File(dir, ".$id.part")
                    md = MessageDigest.getInstance("SHA-256")
                    got = 0
                    part!!.outputStream().use { }
                    return null
                }
                2 -> {
                    if (mid != id) throw IllegalStateException("data for $mid, current $id")
                    val data = msg.copyOfRange(5, msg.size)
                    part!!.appendBytes(data)
                    md!!.update(data)
                    got += data.size
                    return null
                }
                3 -> {
                    if (mid != id) throw IllegalStateException("end for $mid, current $id")
                    val digest = md!!.digest()
                    val ok = digest.contentEquals(sha) && got == size
                    val p = part!!
                    md = null
                    if (!ok) {
                        p.delete()
                        throw IllegalStateException("verify failed: $got / $size bytes")
                    }
                    val finalFile = uniqueFile(dir, name)
                    if (!p.renameTo(finalFile)) throw IllegalStateException("rename failed")
                    return finalFile
                }
                else -> throw IllegalArgumentException("unknown file message ${msg[0]}")
            }
        }
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

    /** The desktop folder the Files app shows; incoming files land here. */
    fun desktopDir(ctx: Context): File = File(ctx.filesDir, "desktop").also { it.mkdirs() }

    /** Set while a PC session is live; the Files window and the harness send through it. */
    @Volatile
    private var sender: ((ByteArray) -> Boolean)? = null

    fun bindSender(s: ((ByteArray) -> Boolean)?) {
        sender = s
    }

    /** "Send to PC" for one file. Returns a short status line for the Files window. */
    fun sendToPc(file: File): String {
        val push = sender ?: return "Send to PC: no PC link"
        if (!file.isFile) return "Send to PC: not a file"
        return try {
            val n = sendFile(file) { push(it) }
            if (n < 0) "Send to PC: link lost" else "sent ${file.name} ($n B) to the PC"
        } catch (t: Throwable) {
            "Send to PC failed: ${t.javaClass.simpleName}"
        }
    }
}
