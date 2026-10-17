package dev.unifieddesktop.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Phase 2 (T6): run Wine (Kron4ek 11.19 amd64, pure DATA) through libbox64 on the
 * Xvfb display, boot a WINEPREFIX under our private files, and start Notepad++
 * (Notepad++ portable zip unpacked as data). All processes come from
 * ProcessBuilder(nativeLibraryDir/libbox64.so, ...) -> /proc/<pid>/exe is libbox64.
 */
object Phase2Wine {
    const val TAG = "Phase2"

    private val log = StringBuilder()
    fun say(s: String) { android.util.Log.i(TAG, s); log.append(s).append('\n') }
    fun logText(): String = log.toString()

    fun writeLog(ctx: Context) {
        try {
            val dir = File(ctx.filesDir, "phase2"); dir.mkdirs()
            FileOutputStream(File(dir, "wine.log")).use { it.write(logText().toByteArray(Charsets.UTF_8)) }
        } catch (_: Throwable) { }
    }

    var wineProc: Process? = null
    var winePid: Int = 0

    fun wineDir(ctx: Context): File = File(Phase2Data.rootDir(ctx), "wine")
    fun wineBin(ctx: Context): File = File(wineDir(ctx), "wine-11.19-amd64/bin/wine")
    fun prefixDir(ctx: Context): File = File(Phase2Data.rootDir(ctx), "wineprefix")
    fun nppDir(ctx: Context): File = File(Phase2Data.rootDir(ctx), "apps/npp")
    fun nppExe(ctx: Context): File = File(nppDir(ctx), "notepad++.exe")

    private fun wineEnv(ctx: Context): Map<String, String> {
        val m = Phase2X11.baseEnv(ctx).toMutableMap()
        // wine ships its own libraries inside the tarball; add them for externals
        val w = wineDir(ctx)
        m["DISPLAY"] = "127.0.0.1:0"
        m["WINEPREFIX"] = prefixDir(ctx).path
        m["LD_LIBRARY_PATH"] = listOf(
            "${w.path}/wine-11.19-amd64/lib64",
            m["LD_LIBRARY_PATH"] ?: "",
        ).joinToString(":")
        m["BOX64_LD_LIBRARY_PATH"] = m["LD_LIBRARY_PATH"] ?: ""
        // box64 reads LD_LIBRARY_PATH itself; keep both in sync
        return m
    }

    /** Launch wine with args; logs /proc/<pid>/exe evidence, does not wait. */
    fun start(ctx: Context, waitForExit: Boolean, vararg args: String): Int {
        val bin = wineBin(ctx)
        if (!bin.isFile) { say("WINE_FAIL missing $bin"); return -1 }
        val argv = listOf(Phase2Box64.libbox64(ctx).path, bin.absolutePath) + args
        val p = ProcessBuilder(argv).apply {
            environment().clear()
            environment().putAll(wineEnv(ctx))
            redirectErrorStream(true)
        }
        val t0 = System.currentTimeMillis()
        val r = p.start()
        wineProc = r
        winePid = Phase2X11.pidOf(r)
        say("WINE pid=$winePid argv=${args.joinToString(" ")}")
        Phase2X11.g3exe("WINE", ctx, winePid)
        if (waitForExit) {
            val out = StringBuilder()
            r.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = try { ins.read(buf) } catch (_: Throwable) { break }
                    if (n <= 0) break
                    val s = String(buf, 0, n).replace('\n', ' ').trim()
                    if (s.isNotBlank()) out.append(s).append('\n')
                }
            }
            val code = r.waitFor()
            say("WINE_EXIT code=$code ms=${System.currentTimeMillis() - t0} out=[${out.take(4000)}]")
        } else {
            Thread({
                val buf = ByteArray(8192)
                try {
                    while (true) {
                        val n = r.inputStream.read(buf)
                        if (n <= 0) break
                        val s = String(buf, 0, n).replace('\n', ' ').trim()
                        if (s.isNotBlank()) {
                            say("WINE_OUT $s")
                            // flush continuously: a crashed dispatch must not lose
                            // the wine output (the say() buffer only lives here).
                            writeLog(ctx)
                        }
                    }
                    say("WINE_EOF exit=${r.exitValue()} ms=${System.currentTimeMillis() - t0}")
                    writeLog(ctx)
                } catch (t: Throwable) {
                    say("WINE_OUT_ERR $t")
                    writeLog(ctx)
                }
            }, "wine-out").start()
        }
        return winePid
    }

    fun boot(ctx: Context) {
        Phase2X11.ensureXvfb(ctx)
        // first-run prefix bootstrap (does real work the first time)
        start(ctx, waitForExit = false, "wineboot", "-i")
        // flush the spawn + G3 lines immediately; the drain thread keeps writing
        Phase2Wine.writeLog(ctx)
    }

    fun startNpp(ctx: Context) {
        if (!nppExe(ctx).isFile) { say("WINE fail: ${nppExe(ctx)} missing"); return }
        Phase2X11.ensureXvfb(ctx)
        start(ctx, waitForExit = false, nppExe(ctx).absolutePath)
        writeLog(ctx)
    }

    fun killWine(logStop: Boolean = true) {
        wineProc?.let { it.destroy(); if (logStop) say("WINE_STOP pid=$winePid") }
        wineProc = null
    }
}
