package dev.unifieddesktop.app

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager

/**
 * Phase-4 AOA accessory-side support (ARCHITECTURE Q5). The PC is the USB host that
 * sends control requests 51/52/53 to switch the phone into accessory mode; after the
 * re-enumeration the phone sees a USB accessory (VID 0x18D1, PID 0x2D00) which matches
 * res/xml/usb_accessory_filter.xml (manufacturer "unified-desktop", model "desktop").
 *
 * The streams of the opened accessory feed the SAME session engine as the TCP link,
 * wrapped in TLS. Work plan for item 8: dump the on-device accessory flow here.
 */
object Phase4Aoa {
    fun um(ctx: Context) = ctx.getSystemService(UsbManager::class.java)

    fun fprShort(x: Any) = "$x".take(80)

    fun accessoryList(ctx: Context): Array<UsbAccessory> =
        um(ctx).accessoryList ?: arrayOf()

    /** Status dump for the harness "aoa-check" broadcast. */
    fun check(ctx: Context): String {
        val um = um(ctx)
        val accs = accessoryList(ctx)
        val lines = ArrayList<String>()
        lines.add("AOA accessories=${accs.size} filter-present=${hasFilter(ctx)}")
        for (a in accs) {
            lines.add(
                "AOA acc m=${a.manufacturer} model=${a.model} v=${a.version}" +
                    " granted=${um.hasPermission(a)}"
            )
        }
        if (accs.isEmpty()) lines.add("AOA none: the PC must switch the phone first (control 51/52/53)")
        for (l in lines) say(ctx, l)
        return lines.joinToString(" | ")
    }

    private fun hasFilter(ctx: Context): Boolean = try {
        ctx.resources.getIdentifier("usb_accessory_filter", "xml", ctx.packageName) != 0
    } catch (_: Throwable) {
        false
    }

    /** Try to open the FIRST accessory that already has permission; stream-based. */
    fun openFirst(ctx: Context): Boolean {
        val um = um(ctx)
        for (a in accessoryList(ctx)) {
            if (um.hasPermission(a)) {
                val fd = um.openAccessory(a)
                if (fd != null) {
                    say(ctx, "AOA opened accessory m=${a.manufacturer} model=${a.model}")
                    return true
                }
                say(ctx, "AOA open FAILED for ${a.model}")
            }
        }
        say(ctx, "AOA no accessible accessory yet")
        return false
    }

    /**
     * User-path hand-off (manager 21:55): open the accessory and give the FD to the link
     * service, so AOA needs no adb either. The service adopts the FD; the TLS/bulk session
     * over it is item-8 work.
     */
    fun handOff(ctx: Context, acc: UsbAccessory): Boolean {
        val um = um(ctx)
        if (!um.hasPermission(acc)) {
            say(ctx, "AOA handOff: permission missing for ${acc.model}")
            return false
        }
        val pfd = try {
            um.openAccessory(acc)
        } catch (t: Throwable) {
            say(ctx, "AOA handOff: openAccessory threw $t")
            null
        }
        if (pfd == null) {
            say(ctx, "AOA handOff: openAccessory returned null")
            return false
        }
        val i = Intent(ctx, Phase4LinkService::class.java)
            .setAction(Phase4LinkService.ACTION_ACCESSORY)
            .putExtra("fd", pfd.detachFd())
            .putExtra("m", acc.manufacturer)
            .putExtra("model", acc.model)
        return try {
            ctx.startService(i)
            say(ctx, "AOA handOff sent fd to link service m=${acc.manufacturer} model=${acc.model}")
            true
        } catch (t: Throwable) {
            say(ctx, "AOA handOff: startService failed $t")
            false
        }
    }

    private fun say(ctx: Context, line: String) {
        Phase4Log.say(ctx, line)
    }
}

object Phase4Log {
    val lines = ArrayList<String>()
    fun say(ctx: android.content.Context?, line: String) {
        android.util.Log.i("Phase4", line)
        try {
            val f = java.io.File(ctx!!.filesDir, "p4/p4.log")
            f.parentFile?.mkdirs()
            f.appendText("${System.currentTimeMillis()} $line\n")
        } catch (_: Throwable) {}
        if (lines.size > 200) lines.removeAt(0)
        lines.add(line)
    }
}
