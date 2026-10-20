package dev.unifieddesktop.app

import android.content.Context
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream

/**
 * Headless phase-7 driver, reached through the manifest broadcast so it works while the phone
 * screen is locked or our activity is in the background:
 *
 *   adb shell am broadcast -n dev.unifieddesktop.app/.HarnessReceiver --es phase7x "guest com.google.android.apps.tasks"
 *
 * Commands: `guest <pkg>`, `close`, `status`, `frames <n>`. Every step lands in files/p7.log.
 */
object Phase7Dispatch {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var display: VirtualDisplay? = null

    @Volatile
    private var reader: ImageReader? = null

    fun runX(ctx: Context, cmd: String) {
        P7Log.say(ctx, "P7X $cmd")
        val parts = cmd.trim().split(' ', limit = 2)
        val arg = parts.getOrNull(1)?.trim().orEmpty()
        Thread({
            try {
                when (parts[0]) {
                    "guest" -> guest(ctx, arg)
                    "close" -> close(ctx)
                    "status" -> status(ctx)
                    "frames" -> frames(ctx, arg.toIntOrNull() ?: 3)
                    else -> P7Log.say(ctx, "P7X unknown: $cmd")
                }
            } catch (t: Throwable) {
                P7Log.say(ctx, "P7X failed: $t")
            }
        }, "p7x").start()
    }

    /** Import + launch a hidden app on our own display; then prove that it draws. */
    private fun guest(ctx: Context, pkg: String) {
        if (pkg.isEmpty()) return P7Log.say(ctx, "P7X guest: no package")
        val engine = EngineBridge(ctx)
        val d = ensureDisplay(ctx) ?: return P7Log.say(ctx, "P7X guest: no display")
        val displayId = d.display.displayId
        P7Log.say(ctx, "P7X display id=$displayId name=${d.display.name}")
        val gms = engine.installGms(0)
        P7Log.say(ctx, "P7X gms ok=${gms.ok}")
        val imp = engine.importInstalled(pkg)
        P7Log.say(ctx, "P7X import ok=${imp.ok} msg=${imp.message}")
        if (!imp.ok) return
        val res = engine.launch(pkg, displayId)
        P7Log.say(ctx, "P7X launch ok=${res.ok} msg=${res.message}")
        if (!res.ok) return
        repeat(6) { n ->
            Thread.sleep(1500)
            P7Log.say(ctx, "P7X frame $n drawn=${capture(ctx, "p7g-$n")}")
        }
    }

    private fun frames(ctx: Context, n: Int) {
        ensureDisplay(ctx) ?: return P7Log.say(ctx, "P7X frames: no display")
        repeat(n.coerceIn(1, 20)) { i ->
            Thread.sleep(1000)
            P7Log.say(ctx, "P7X frame $i drawn=${capture(ctx, "p7f-$i")}")
        }
    }

    /** Finish every live guest activity; the last destroy stops the engine daemon (B-061). */
    private fun close(ctx: Context) {
        val guests = GuestPresentationHost.liveGuests()
        P7Log.say(ctx, "P7X close hostGuests=${guests.size} (the live list lives in the guest process)")
        for (activity in guests) {
            try {
                main.post { activity.finish() }
                P7Log.say(ctx, "P7X finish ${activity.javaClass.name}")
            } catch (t: Throwable) {
                P7Log.say(ctx, "P7X finish failed: $t")
            }
        }
        // B-070: the activities belong to the guest process, where GuestPresentationHost keeps
        // its own live list. Ask it to finish them over the same uid-checked input socket.
        val reply = guestClose("")
        P7Log.say(ctx, "P7X close guestReply=$reply")
    }

    /** Sends "C <pkg>" to the guest-process input server; true when one guest answered. */
    private fun guestClose(pkg: String): Boolean = try {
        android.net.LocalSocket().use { s ->
            s.connect(
                android.net.LocalSocketAddress(
                    GuestPresentationHost.INPUT_SOCKET,
                    android.net.LocalSocketAddress.Namespace.ABSTRACT
                )
            )
            s.outputStream.write(("C $pkg\n").toByteArray())
            s.outputStream.flush()
            java.io.BufferedReader(java.io.InputStreamReader(s.inputStream, "UTF-8")).readLine() == "1"
        }
    } catch (t: Throwable) {
        false
    }

    private fun status(ctx: Context) {
        val d = findDisplay(ctx)
        P7Log.say(ctx, "P7X status display=${d?.display?.displayId ?: -1} " +
            "liveGuests=${GuestPresentationHost.liveGuests().size} daemonGuests=${EngineDaemon.guests()}")
    }

    private fun findDisplay(ctx: Context): VirtualDisplay? {
        display?.takeIf { it.display.isValid }?.let { return it }
        val dm = ctx.getSystemService(DisplayManager::class.java) ?: return null
        return dm.displays.firstOrNull { NAME == it.name && it.displayId != 0 }?.let { existing ->
            P7Log.say(ctx, "P7X display ${existing.displayId} exists but frames are not ours")
            null
        }
    }

    private fun ensureDisplay(ctx: Context): VirtualDisplay? {
        findDisplay(ctx)?.let { return it }
        return try {
            val localReader = ImageReader.newInstance(WIDTH, HEIGHT, android.graphics.PixelFormat.RGBA_8888, 3,
                HardwareBuffer.USAGE_CPU_READ_OFTEN)
            val dm = ctx.getSystemService(DisplayManager::class.java)
            val created = dm.createVirtualDisplay(NAME, WIDTH, HEIGHT, 320, localReader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            reader = localReader
            display = created
            P7Log.say(ctx, "P7X created display id=${created.display.displayId} ${WIDTH}x$HEIGHT")
            created
        } catch (t: Throwable) {
            P7Log.say(ctx, "P7X createDisplay failed: $t")
            null
        }
    }

    private fun capture(ctx: Context, name: String): Boolean {
        val r = reader ?: return false
        val image = try {
            r.acquireLatestImage()
        } catch (t: Throwable) {
            null
        } ?: return false
        return try {
            val dir = File(ctx.filesDir, "phase1").also { it.mkdirs() }
            val file = File(dir, "$name.png")
            FileOutputStream(file).use { out ->
                RgbaFrames.toBitmap(image).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            P7Log.say(ctx, "P7X PNG $name.png bytes=${file.length()}")
            true
        } finally {
            image.close()
        }
    }

    private const val NAME = "phase1-owned"
    private const val WIDTH = 1280
    private const val HEIGHT = 720
}
