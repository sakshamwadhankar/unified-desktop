package dev.unifieddesktop.app

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.security.MessageDigest

/**
 * Phase 3 (ARCHITECTURE H8, DECISIONS D6): the full-speed tools.
 *
 * Node.js and code-server's native modules ship INSIDE the APK as lib*.so files.
 * Android extracts them into applicationInfo.nativeLibraryDir, which is the only
 * place our app may execute code from (targetSdk 36; app-writable storage is
 * blocked for exec/dlopen since Android 10). Everything else of code-server -
 * all of its JavaScript - is DATA: the app downloads the pinned Termux deb at
 * run time, checks its SHA-256 and unpacks only non-ELF files (Phase3Data).
 *
 * This file owns:
 *  - the pinned APK library names (they must match lib*.so),
 *  - the native-module mapping table (JS require path -> APK library),
 *  - the environment we hand to node (LD_LIBRARY_PATH=nativeLibraryDir, SSL_CERT_DIR,
 *    HOME/TMPDIR inside our own files/cache dirs, shell = /system/bin/sh),
 *  - a small runner: start libnode.so with args, capture stdout/stderr with times.
 */
object Phase3Native {
    const val TAG = "Phase3"
    const val LOG_NAME = "p3.log"

    // APK library names (android/vscode/fetch-native.sh writes exactly these).
    const val LIB_NODE = "libnode.so"
    const val LIB_CXX = "libcxx.so"
    const val LIB_SSL = "libssl3.so"
    const val LIB_CRYPTO = "libcrypto3.so"
    const val LIB_CARES = "libcares.so"
    const val LIB_PTY = "libpty.so"
    const val LIB_WATCHER = "libwatcher.so"
    const val LIB_SPDLOG = "libspdlog.so"
    const val LIB_SQLITE = "libsqlite3node.so"
    const val LIB_WATCHDOG = "libwatchdog.so"
    const val LIB_KERBEROS = "libkerberos.so"
    const val LIB_ARGON2 = "libargon2.so"

    /**
     * The native-module mapping table, kept in one place (unit-tested).
     * Key: the path code-server/VS Code uses to require the module, relative to
     * <code-server>/lib/vscode/. Value: the lib*.so name in nativeLibraryDir.
     * The require hook (Phase3Native.hook, injected into node with -r) rewrites
     * every require of a .node file to the nativeLibraryDir copy via process.dlopen.
     */
    val NATIVE_MODULES: Map<String, String> = linkedMapOf(
        "node_modules/node-pty/build/Release/pty.node" to LIB_PTY,
        "node_modules/@parcel/watcher/build/Release/watcher.node" to LIB_WATCHER,
        "node_modules/@vscode/spdlog/build/Release/spdlog.node" to LIB_SPDLOG,
        "node_modules/@vscode/sqlite3/build/Release/vscode-sqlite3.node" to LIB_SQLITE,
        "node_modules/@vscode/native-watchdog/build/Release/watchdog.node" to LIB_WATCHDOG,
        "node_modules/kerberos/build/Release/kerberos.node" to LIB_KERBEROS,
    )

    /** code-server's own node_modules (argon2) lives one level above lib/vscode. */
    val NATIVE_MODULES_CODER: Map<String, String> = linkedMapOf(
        "node_modules/argon2/build/Release/argon2.node" to LIB_ARGON2,
    )

    /** Every lib*.so this phase puts in the APK (used by tests and the report). */
    val SHIPPED_LIBS: List<String> = listOf(
        LIB_NODE, LIB_CXX, LIB_SSL, LIB_CRYPTO, LIB_CARES, LIB_PTY, LIB_WATCHER,
        LIB_SPDLOG, LIB_SQLITE, LIB_WATCHDOG, LIB_KERBEROS, LIB_ARGON2,
    )

    /**
     * Pure mapping lookup (unit-tested): given a required path, return the lib*.so
     * name to load from nativeLibraryDir, or null when the path is not one of the
     * mapped native modules. Paths may arrive with either separator and with a
     * leading "./"; the coder table (code-server's own node_modules) is looked up
     * only when the caller says it is that tree.
     */
    fun moduleLibFor(relPath: String, coderTree: Boolean = false): String? {
        val normalized = relPath.replace('\\', '/').removePrefix("./")
        val table = if (coderTree) NATIVE_MODULES_CODER else NATIVE_MODULES
        return table[normalized]
    }

    /** Pure environment builder (unit-tested). */
    fun envFor(
        nativeLibraryDir: String,
        homeDir: String,
        tmpDir: String,
        shell: String = "/system/bin/sh",
        caDir: String = "/system/etc/security/cacerts",
    ): Map<String, String> = linkedMapOf(
        // Our libs resolve from the APK's library dir only.
        "LD_LIBRARY_PATH" to nativeLibraryDir,
        // Termux's CA path does not exist on a stock Android app; the system store does.
        "SSL_CERT_DIR" to caDir,
        "HOME" to homeDir,
        "TMPDIR" to tmpDir,
        "TMP" to tmpDir,
        "TEMP" to tmpDir,
        "SHELL" to shell,
        "PATH" to "/system/bin:/system/xbin",
        // No telemetry/update noise from Node itself; code-server's own switches
        // are set on its command line (Phase3Server).
        "NODE_NO_WARNINGS" to "1",
        "CI" to "1",
    )

    private val log = StringBuilder()
    fun say(s: String) { android.util.Log.i(TAG, s); log.append(s).append('\n') }
    fun logText(): String = log.toString()

    fun dir(ctx: Context): File = File(ctx.filesDir, "phase3").also { it.mkdirs() }
    fun homeDir(ctx: Context): File = File(dir(ctx), "home").also { it.mkdirs() }
    fun tmpDir(ctx: Context): File = File(dir(ctx), "tmp").also { it.mkdirs() }

    fun lib(ctx: Context, name: String): File = File(ctx.applicationInfo.nativeLibraryDir, name)

    data class Run(
        val exit: Int,
        val wallMs: Long,
        val firstOutputMs: Long,
        val lines: List<String>,
    ) {
        val text: String get() = lines.joinToString("\n")
    }

    /**
     * Start a program from nativeLibraryDir with our Phase-3 environment and wait
     * for it (bounded). Output is streamed into the phase-3 log with a tag so the
     * on-device log file is the evidence.
     */
    fun run(
        ctx: Context,
        bin: File,
        args: List<String>,
        tag: String,
        timeoutMs: Long = 120_000,
        extraEnv: Map<String, String> = emptyMap(),
    ): Run {
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        if (!bin.exists()) {
            say("$tag FAIL binary missing ${bin.absolutePath}")
            return Run(-1, 0, -1, listOf("missing ${bin.absolutePath}"))
        }
        val t0 = System.currentTimeMillis()
        var firstMs = -1L
        val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env.clear()
        env.putAll(envFor(ctx.applicationInfo.nativeLibraryDir, homeDir(ctx).absolutePath, tmpDir(ctx).absolutePath))
        env.putAll(extraEnv)
        var p: Process? = null
        try {
            p = pb.start()
            val pid = try { (p.javaClass.getMethod("pid").invoke(p) as? Long) ?: -1L } catch (t: Throwable) { -1L }
            say("$tag START pid=$pid args=${args.joinToString(" ")}")
            val exe = try { File("/proc/$pid/exe").canonicalFile.path } catch (t: Throwable) { "ERR $t" }
            say("$tag EXE ${exe} nativeDir=${ctx.applicationInfo.nativeLibraryDir} under=${exe.startsWith(ctx.applicationInfo.nativeLibraryDir)}")
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val readerThread = Thread {
                try {
                    var line = reader.readLine()
                    while (line != null) {
                        if (firstMs < 0) firstMs = System.currentTimeMillis() - t0
                        lines.add(line)
                        say("$tag OUT $line")
                        line = reader.readLine()
                    }
                } catch (_: Throwable) { }
            }.apply { name = "phase3-$tag-out" }.also { it.start() }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (p.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(100)
            val timedOut = p.isAlive
            if (timedOut) { p.destroy(); say("$tag TIMEOUT after ${timeoutMs} ms -> destroy") }
            val code = try { p.waitFor() } catch (_: Throwable) { -1 }
            readerThread.join(3000)
            val wall = System.currentTimeMillis() - t0
            say("$tag DONE exit=$code wall_ms=$wall first_output_ms=$firstMs timedOut=$timedOut")
            return Run(code, wall, firstMs, lines.toList())
        } catch (t: Throwable) {
            say("$tag ERROR $t")
            return Run(-1, System.currentTimeMillis() - t0, firstMs, lines.toList())
        } finally {
            try { p?.destroy() } catch (_: Throwable) { }
        }
    }

    /** node -e <script> */
    fun node(ctx: Context, script: String, tag: String, timeoutMs: Long = 120_000, extraEnv: Map<String, String> = emptyMap()): Run =
        run(ctx, lib(ctx, LIB_NODE), listOf("-e", script), tag, timeoutMs, extraEnv)

    // ---------------------------------------------------------------- probes ---

    /**
     * Item 4 core proof. Order matters: version/arch first (proves the binary runs
     * from nativeLibraryDir at all), then TLS (proves libssl3/libcrypto3 are found
     * and the system CA store works), then process.dlopen of a native module from
     * nativeLibraryDir, then the negative control: the same .node copied into
     * files/ must FAIL to dlopen (that is the Play rule, measured, not assumed).
     */
    fun probeAll(ctx: Context) {
        val dir = dir(ctx)
        say("PROBE begin nativeLibraryDir=${ctx.applicationInfo.nativeLibraryDir}")
        val v = node(ctx, "console.log('NODE', process.version, process.arch, process.platform); console.log('OPENSSL', process.versions.openssl); console.log('EXECPATH', process.execPath); console.log('CWD', process.cwd())", "node-version", 60_000)
        say("PROBE node_basic exit=${v.exit} wall_ms=${v.wallMs} first_ms=${v.firstOutputMs}")

        val tls = node(
            ctx,
            "const u=['https://open-vsx.org/','https://open-vsx.org/api/-/search?query=python&size=1'];" +
                "(async()=>{for(const x of u){try{const r=await fetch(x);const t=await r.text();console.log('TLS_OK',r.status,x,t.length,'bytes',JSON.stringify(t.slice(0,60)))}catch(e){console.log('TLS_ERR',x,e.message,(e.cause&&e.cause.message)||'')}}})()",
            "node-tls", 90_000
        )
        say("PROBE tls exit=${tls.exit} wall_ms=${tls.wallMs}")

        val libDir = ctx.applicationInfo.nativeLibraryDir
        val pty = File(libDir, LIB_PTY)
        val dlScript =
            "const m={exports:{}}; try { process.dlopen(m, process.env.UD_PTY); console.log('DLOPEN_NATIVE_OK exports=' + Object.keys(m.exports).join(',')) } catch(e) { console.log('DLOPEN_NATIVE_ERR', e.message); process.exit(4) }"
        val dl = node(ctx, dlScript, "node-dlopen-native", 60_000, mapOf("UD_PTY" to pty.absolutePath))
        say("PROBE dlopen_native exit=${dl.exit} pty=${pty.absolutePath} exists=${pty.exists()} bytes=${pty.length()}")

        // Negative control: the same file in app-writable storage.
        val copy = File(dir, "pty-copy.node")
        var copyError: String? = null
        try {
            pty.copyTo(copy, overwrite = true)
        } catch (t: Throwable) { copyError = t.toString() }
        say("PROBE pty_copy_to_files path=${copy.absolutePath} bytes=${copy.length()} err=$copyError sha=${sha256(copy)} vs_src=${sha256(pty)}")
        val dlCopyScript =
            "const m={exports:{}}; try { process.dlopen(m, process.env.UD_COPY); console.log('DLOPEN_FILES_UNEXPECTED_OK') } catch(e) { console.log('DLOPEN_FILES_ERR', e.message) }"
        val dlc = node(ctx, dlCopyScript, "node-dlopen-files", 60_000, mapOf("UD_COPY" to copy.absolutePath))
        say("PROBE dlopen_files exit=${dlc.exit} (a failure here is the EXPECTED result)")

        val libs = SHIPPED_LIBS.joinToString(" ") { n ->
            val f = File(libDir, n)
            "$n=${if (f.exists()) f.length() else -1}"
        }
        say("PROBE libs $libs")
        say("PROBE end")
    }

    fun sha256(f: File): String = try {
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) { "ERR $t" }

    fun writeLog(ctx: Context) {
        try {
            val f = File(dir(ctx), LOG_NAME)
            FileOutputStream(f).use { it.write(logText().toByteArray(Charsets.UTF_8)) }
        } catch (_: Throwable) { }
    }

    /**
     * The negative control for the whole design: Android 10+ is documented to
     * forbid EXECUTING a file in the app's writable storage (targetSdk >= 29).
     * Same bytes, same file name, different directory - only nativeLibraryDir
     * may be executed. Measured, not assumed (the same test for dlopen is in
     * probeAll; the dlopen result on this image is reported as-is).
     */
    fun probeExec(ctx: Context) {
        val src = lib(ctx, LIB_NODE)
        val copy = File(dir(ctx), "libnode-copy.so")
        try {
            src.copyTo(copy, overwrite = true)
        } catch (t: Throwable) {
            return say("EXECPROBE copy failed: $t")
        }
        say("EXECPROBE copy=${copy.absolutePath} bytes=${copy.length()} srcBytes=${src.length()} sha_copy=${sha256(copy)} sha_src=${sha256(src)}")
        try {
            val pb = ProcessBuilder(copy.absolutePath, "--version")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            val code = p.waitFor()
            say("EXECPROBE UNEXPECTED_OK exit=$code out=${out.trim().take(200)}")
        } catch (t: Throwable) {
            say("EXECPROBE files_EXEC_BLOCKED ${t.javaClass.name}: ${t.message}")
        }
        // and the same binary from nativeLibraryDir, for contrast (with our env, so
        // the linker finds libcxx.so/libssl3.so where node expects them)
        try {
            val pb = ProcessBuilder(src.absolutePath, "-e", "console.log('native exec ok', process.version)")
            pb.redirectErrorStream(true)
            pb.environment().clear()
            pb.environment().putAll(envFor(ctx.applicationInfo.nativeLibraryDir, homeDir(ctx).absolutePath, tmpDir(ctx).absolutePath))
            val p = pb.start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            val code = p.waitFor()
            say("EXECPROBE native_EXEC_OK exit=$code out=${out.trim().take(160)}")
        } catch (t: Throwable) {
            say("EXECPROBE native_EXEC_FAILED ${t.javaClass.name}: ${t.message}")
        }
    }

    /** Run a JS file from files/phase3/<name> (for quick diagnostics). */
    fun runScriptFile(ctx: Context, name: String) {
        val f = File(dir(ctx), name)
        if (!f.isFile) { say("SCRIPT missing ${f.absolutePath}"); return }
        say("SCRIPT run ${f.absolutePath} bytes=${f.length()}")
        node(ctx, f.readText(), "script-$name", 120_000)
    }
}
