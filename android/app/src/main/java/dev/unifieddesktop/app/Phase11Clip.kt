package dev.unifieddesktop.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 11 D-c: text clipboard, phone half.
 *
 * The Android clipboard IS our desktop clipboard for WebView windows (VS Code and friends
 * paste/copy through it), and the X11 bridge (Notepad++) reads the same text from here.
 * Both directions are hash-guarded against an echo loop; nothing logs clipboard contents,
 * only the byte length and a SHA-256 prefix.
 *
 * Wire format (CH_CLIP): the UTF-8 bytes of the text, at most 1 MiB (Phase4Protocol caps a
 * logical message at 1 MiB, so one text never needs its own length prefix).
 */
object Phase11Clip {
    const val MAX_BYTES = 1 shl 20

    private val main = Handler(Looper.getMainLooper())
    private val lastHash = AtomicLong(0)
    private var cm: ClipboardManager? = null
    private var send: ((ByteArray) -> Unit)? = null
    private var registered = false
    private var appCtx: Context? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { onLocalChanged() }

    /** Called once when the link service starts; `send` pushes one CH_CLIP message. */
    fun start(ctx: Context, send: (ByteArray) -> Unit) {
        if (registered) return
        val c = ctx.applicationContext
        val m = c.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm = m
        this.send = send
        appCtx = c
        m.addPrimaryClipChangedListener(listener)
        registered = true
        Phase4Log.say(c, "P11CLIP start (Android clipboard <-> CH_CLIP)")
    }

    fun stop(ctx: Context) {
        if (!registered) return
        try { cm?.removePrimaryClipChangedListener(listener) } catch (_: Throwable) {}
        cm = null
        send = null
        appCtx = null
        registered = false
        Phase4Log.say(ctx.applicationContext, "P11CLIP stop")
    }

    /** A CH_CLIP text arrived from the PC (session thread). */
    fun onRemote(payload: ByteArray) {
        if (payload.isEmpty() || payload.size > MAX_BYTES) return
        val copy = payload.copyOf()
        main.post { applyRemote(copy) }
    }

    private fun applyRemote(bytes: ByteArray) {
        val ctx = appCtx ?: return
        val m = cm ?: return
        if (hash(bytes) == lastHash.get()) return // our own text echoed back
        val text = try { String(bytes, Charsets.UTF_8) } catch (_: Throwable) { return }
        try {
            m.setPrimaryClip(ClipData.newPlainText("unified-desktop", text))
            lastHash.set(hash(bytes))
            Phase4Log.say(ctx, "P11CLIP <- PC len=${bytes.size} sha=${shaPrefix(bytes)}")
        } catch (t: Throwable) {
            Phase4Log.say(ctx, "P11CLIP set refused ${t.javaClass.simpleName}")
        }
    }

    private fun onLocalChanged() {
        val ctx = appCtx ?: return
        val m = cm ?: return
        val text = try {
            val clip = m.primaryClip ?: return
            if (clip.itemCount == 0) return
            clip.getItemAt(0).text?.toString() ?: return
        } catch (_: Throwable) {
            return
        }
        if (text.isEmpty()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) {
            Phase4Log.say(ctx, "P11CLIP local ${bytes.size} bytes > cap, not sent")
            return
        }
        val h = hash(bytes)
        if (h == lastHash.get()) return // the text we just applied from the PC
        lastHash.set(h)
        send?.invoke(bytes)
        Phase4Log.say(ctx, "P11CLIP -> PC len=${bytes.size} sha=${shaPrefix(bytes)}")
    }

    /**
     * Harness only (phase11x broadcast): put `text` on the Android clipboard exactly as an
     * app's Copy would, so the listener path desk->PC can be exercised without a UI tap.
     */
    fun setLocal(ctx: Context, text: String) {
        val m = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        m.setPrimaryClip(ClipData.newPlainText("unified-desktop-test", text))
        Phase4Log.say(ctx, "P11CLIP test set len=${text.toByteArray(Charsets.UTF_8).size}")
    }

    /** Harness only: prove what the clipboard holds without logging its contents. */
    fun showLocal(ctx: Context) {
        val m = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val t = try { m?.primaryClip?.getItemAt(0)?.text?.toString() } catch (_: Throwable) { null }
        val b = t?.toByteArray(Charsets.UTF_8)
        Phase4Log.say(
            ctx,
            if (b == null) "P11CLIP show none" else "P11CLIP show len=${b.size} sha=${shaPrefix(b)}"
        )
    }

    /** Same FNV-1a 64 as pc/udreceiver/src/clip.rs (loop guard only). */    fun hash(bytes: ByteArray): Long {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (b in bytes) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return h
    }

    private fun shaPrefix(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(4)
            .joinToString("") { "%02x".format(it) }
}
