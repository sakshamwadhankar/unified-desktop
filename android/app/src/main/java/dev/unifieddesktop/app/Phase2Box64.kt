package dev.unifieddesktop.app

import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.security.MessageDigest

/**
 * Phase 2 T2: run Box64 from our APK.
 *
 * Constraint check this proves (ARCHITECTURE C1): the ONLY code we execute is
 * libbox64.so, extracted from the APK into applicationInfo.nativeLibraryDir.
 * The x86-64 test ELF is data in filesDir; box64 reads and translates it, so
 * /proc/<pid>/exe must always point inside nativeLibraryDir.
 */
object Phase2Box64 {
    const val TAG = "Phase2"
    const val LOG_NAME = "t2.log"

    /**
     * Pure-syscall x86-64 test guests (built by android/win86/build-x86test.sh from
     * x86test.S; committed nowhere as binaries, reproduced by script).
     */
    const val TEST_ELF_SHA256 = "052ae7f32cc09e4627580cb01240f630a5b2cadef06948286b118b8a6abcfd89"
    val TEST_ELF_B64 = "f0VMRgIBAQAAAAAAAAAAAAIAPgABAAAAgBEgAAAAAABAAAAAAAAAACgDAAAAAAAAAAAAAEAAOAAFAEAACAAGAAYAAAAEAAAAQAAAAAAAAABAACAAAAAAAEAAIAAAAAAAGAEAAAAAAAAYAQAAAAAAAAgAAAAAAAAAAQAAAAQAAAAAAAAAAAAAAAAAIAAAAAAAAAAgAAAAAAB9AQAAAAAAAH0BAAAAAAAAABAAAAAAAAABAAAABQAAAIABAAAAAAAAgBEgAAAAAACAESAAAAAAAGQAAAAAAAAAZAAAAAAAAAAAEAAAAAAAAAEAAAAGAAAA5AEAAAAAAADkISAAAAAAAOQhIAAAAAAAEAAAAAAAAAAQAAAAAAAAAAAQAAAAAAAAUeV0ZAYAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABoZWxsbyBmcm9tIHBoYXNlMiB4ODYtNjQgdW5kZXIgYm94NjQKAAAA6EAAAABJx8EDAAAASYP5AHQnSMfA5gAAAEjHxwEAAABIMfZIjRU6EAAATTHSDwXoEQAAAEn/yevTSMfAPAAAAEgx/w8FSMfAAQAAAEjHxwEAAABIjTV+7///SMfCJQAAAA8FwwIAAAAAAAAAAAAAAAAAAABMaW5rZXI6IExMRCAxOC4wLjMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAIAxREgAAAAAAAAAAAAAAAAAAcAAAAAAAIAjBEgAAAAAAAAAAAAAAAAAAwAAAAAAAIAuREgAAAAAAAAAAAAAAAAABEAAAAAAAMA5CEgAAAAAAAAAAAAAAAAABQAAAAAAAEAWAEgAAAAAAAAAAAAAAAAABgAAAAAAPH/JQAAAAAAAAAAAAAAAAAAAB8AAAAQAAIAgBEgAAAAAAAAAAAAAAAAAAAucm9kYXRhAC50ZXh0AC5kYXRhAC5jb21tZW50AC5zeW10YWIALnNoc3RydGFiAC5zdHJ0YWIAAGhlbGxvAHRpY2sAZG9uZQB0cwBtc2cAbXNnbGVuAF9zdGFydAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAQAAAAIAAAAAAAAAWAEgAAAAAABYAQAAAAAAACUAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAACQAAAAEAAAAGAAAAAAAAAIARIAAAAAAAgAEAAAAAAABkAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAA8AAAABAAAAAwAAAAAAAADkISAAAAAAAOQBAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAVAAAAAQAAADAAAAAAAAAAAAAAAAAAAAD0AQAAAAAAABMAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAEAAAAAAAAAHgAAAAIAAAAAAAAAAAAAAAAAAAAAAAAACAIAAAAAAADAAAAAAAAAAAcAAAAHAAAACAAAAAAAAAAYAAAAAAAAACYAAAADAAAAAAAAAAAAAAAAAAAAAAAAAMgCAAAAAAAAOAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAwAAAAAwAAAAAAAAAAAAAAAAAAAAAAAAAAAwAAAAAAACYAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAA"

    private val log = StringBuilder()
    fun say(s: String) { android.util.Log.i(TAG, s); log.append(s).append('\n') }
    fun logText(): String = log.toString()

    fun libbox64(app: android.content.Context): File =
        File(app.applicationInfo.nativeLibraryDir, "libbox64.so")

    fun writeGuest(context: android.content.Context): File {
        val dir = File(context.filesDir, "phase2"); dir.mkdirs()
        val f = File(dir, "x86test")
        val bytes = android.util.Base64.decode(TEST_ELF_B64, android.util.Base64.DEFAULT)
        if (f.length() != bytes.size.toLong()) {
            FileOutputStream(f).use { it.write(bytes) }
        }
        val md = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
        val hex = md.joinToString("") { "%02x".format(it) }
        check(hex == TEST_ELF_SHA256) { "guest x86test sha mismatch: $hex" }
        return f
    }

    fun runVersion(context: android.content.Context) {
        val box64 = libbox64(context)
        say("BOX64_BIN path=${box64.absolutePath} exists=${box64.exists()}")
        if (!box64.exists()) { say("BOX64_VERSION FAIL file missing"); return }
        try {
            val pb = ProcessBuilder(box64.absolutePath, "--version")
            pb.redirectErrorStream(true)
            val start = System.nanoTime()
            val p = pb.start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { r -> r.readText() }
            val code = p.waitFor()
            say("BOX64_VERSION exit=$code ms=${(System.nanoTime()-start)/1_000_000}")
            say("BOX64_VERSION output=[${out.trim()}]")
        } catch (t: Throwable) { say("BOX64_VERSION ERROR $t") }
    }

    fun runGuest(context: android.content.Context) {
        val elf = try { writeGuest(context) } catch (t: Throwable) {
            return say("GUEST_WRITE FAIL x86test: $t")
        }
        say("GUEST data=${elf.absolutePath} bytes=${elf.length()}")
        val box64 = libbox64(context)
        try {
            val pb = ProcessBuilder(box64.absolutePath, elf.absolutePath)
            pb.redirectErrorStream(true)
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["BOX64_LOG"] = "1"
            val start = System.currentTimeMillis()
            val p = pb.start()
            say("GUEST pid=${p.pid()} started")
            // While the guest runs, prove no code was executed from app storage:
            // for every process we start, readlink /proc/<pid>/exe must be under
            // nativeLibraryDir (G3).
            val exe = try { File("/proc/${p.pid()}/exe").canonicalFile.path } catch (t: Throwable) { "ERR $t" }
            say("GUEST_EXE proc/exe=$exe nativeDir=${context.applicationInfo.nativeLibraryDir}")
            val badPath = !exe.startsWith(context.applicationInfo.nativeLibraryDir)
            say("GUEST_EXE underNativeLibraryDir=${!badPath}")
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val readerThread = Thread {
                reader.forEachLine { say("GUEST_OUT ${it.trim()}") }
            }.apply { start() }
            // wait max runSeconds*10 for a bounded test
            val deadline = System.currentTimeMillis() + 60_000L // bounded: 60 s max for the test
            var finished = false
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive) { finished = true; break }
                Thread.sleep(200)
            }
            if (!finished) { p.destroy(); say("GUEST TIMEOUT destroy") }
            val code = p.waitFor()
            readerThread.join(2000)
            say("GUEST exit=$code wall_ms=${System.currentTimeMillis() - start}")
        } catch (t: Throwable) { say("GUEST ERROR $t") }
    }
}

private fun Process.pid(): Long =
    (this.javaClass.getMethod("pid").invoke(this) as? Long) ?: -1L
