package dev.unifieddesktop.app

import android.content.Context
import android.security.NetworkSecurityPolicy
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom

/**
 * Item 6: start code-server from the app and prove it is up.
 *
 * code-server runs as a **child of the app process**, started from
 * `<nativeLibraryDir>/libnode.so` (the only place Android lets us execute code from) with
 * our environment (Phase3Native.envFor). Security, all enforced here:
 *  - it binds to `127.0.0.1:<random free port>` only - never 0.0.0.0,
 *  - `auth: password` with a fresh random password per start (`PASSWORD` env + config),
 *  - never `--auth none`,
 *  - telemetry and update checks are off, workspace trust is off (the gate flow edits
 *    files; a trust prompt would block a WebView-only session),
 *  - the CA store, HOME, TMPDIR and SHELL are the stock-Android ones (Phase3Native).
 *
 * The "it is up" proof is an HTTP GET of code-server's own `/healthz`, made by the app
 * over the loopback interface, and the process facts are read from /proc.
 */
object Phase3Server {

    private fun say(s: String) { Phase3Native.say(s) }

    data class Session(val pid: Long, val port: Int, val password: String, val startMs: Long)

    /** The preload that logs every native-addon load with its resolved path (asset). */
    fun hookFile(ctx: Context): File {
        val f = File(Phase3Native.dir(ctx), "hook.js")
        ctx.assets.open("phase3/hook.js").use { src -> FileOutputStream(f).use { src.copyTo(it) } }
        return f
    }

    fun configFile(ctx: Context): File = File(Phase3Native.dir(ctx), "config.yaml")
    fun sessionFile(ctx: Context): File = File(Phase3Native.dir(ctx), "session.txt")
    fun outFile(ctx: Context): File = File(Phase3Native.dir(ctx), "code-server.out")
    fun userDataDir(ctx: Context): File = File(Phase3Native.dir(ctx), "cs-data").also { it.mkdirs() }
    fun extensionsDir(ctx: Context): File = File(Phase3Native.dir(ctx), "cs-ext").also { it.mkdirs() }

    /** Pure config text (unit-tested): loopback only, password auth, no telemetry. */
    fun configYaml(port: Int, password: String): String = buildString {
        append("bind-addr: 127.0.0.1:").append(port).append('\n')
        append("auth: password\n")
        append("password: ").append(password).append('\n')
        append("cert: false\n")
        append("disable-telemetry: true\n")
        append("disable-update-check: true\n")
        append("disable-workspace-trust: true\n")
    }

    /** Pure: 32 lowercase hex chars from [rnd] (unit-tested for shape). */
    fun newPassword(rnd: SecureRandom = SecureRandom()): String {
        val b = ByteArray(16)
        rnd.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** A free loopback port: bind port 0, read what the kernel gave us, close it. */
    fun freePort(): Int = ServerSocket().use { s ->
        s.bind(InetSocketAddress("127.0.0.1", 0))
        s.localPort
    }

    /** GET /healthz over loopback: (http code or 0 on error, ms, first bytes of the answer). */
    fun healthz(port: Int, connectMs: Int = 2000, readMs: Int = 4000): Triple<Int, Long, String> {
        val t0 = System.currentTimeMillis()
        try {
            val c = URL("http://127.0.0.1:$port/healthz").openConnection() as HttpURLConnection
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.instanceFollowRedirects = false
            val code = c.responseCode
            val body = try {
                c.inputStream.use { it.readBytes().decodeToString().take(120) }
            } catch (_: Throwable) { "" }
            c.disconnect()
            return Triple(code, System.currentTimeMillis() - t0, body)
        } catch (t: Throwable) {
            return Triple(0, System.currentTimeMillis() - t0, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ start / stop --

    /**
     * Start code-server and wait for /healthz. Returns the session on success, null on
     * failure (both are logged with the numbers the report needs).
     */
    fun start(ctx: Context, waitMs: Long = 180_000): Session? {
        val root = Phase3Data.root(ctx)
        val entry = File(root, "out/node/entry.js")
        if (!entry.isFile) {
            say("CS_START FAIL entry missing ${entry.absolutePath} - run cs-fetch first")
            return null
        }
        val node = Phase3Native.lib(ctx, Phase3Native.LIB_NODE)
        // Re-create the native-module links every start: an `adb install -r` (or any app
        // update) moves nativeLibraryDir to a new /data/app/~~... path, so every symlink
        // made by an earlier unpack dangles (measured 13:11: stat ENOENT on argon2.node ->
        // MODULE_NOT_FOUND killed the first start). linkNativeModules re-creates any link
        // whose target is not the current libDir; measured as created=7 already=0 here.
        val links = Phase3Data.linkNativeModules(ctx)
        say("CS_START relink created=${links.first} already=${links.second}")
        val policy = NetworkSecurityPolicy.getInstance()
        say("CS_START cleartext_127=${policy.isCleartextTrafficPermitted("127.0.0.1")} " +
                "cleartext_localhost=${policy.isCleartextTrafficPermitted("localhost")} " +
                "cleartext_example=${policy.isCleartextTrafficPermitted("example.com")}")

        val port = freePort()
        val password = newPassword()
        val conf = configFile(ctx).also { it.writeText(configYaml(port, password)) }
        val hook = hookFile(ctx)
        say("CS_START port=$port pw=random(${password.length}hex) conf=${conf.absolutePath} " +
                "node=${node.absolutePath} entry=${entry.absolutePath} hook=${hook.absolutePath}")

        val t0 = System.currentTimeMillis()
        val pb = ProcessBuilder(
            listOf(
                node.absolutePath,
                // --preserve-symlinks: the native modules are *symlinks* named `*.node`
                // (measured: `process.dlopen` of such a link works, but the CJS loader
                // realpaths the request (tryFile/toRealPath), so `require()` saw
                // `libargon2.so`, picked the `.js` handler and SyntaxError'd on ELF bytes).
                // Preserving symlinks keeps the `.node` name for the loader, and dlopen
                // resolves the link itself (probe A6).
                "--preserve-symlinks",
                "-r", hook.absolutePath, entry.absolutePath, "--config", conf.absolutePath,
            )
        )
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env.clear()
        env.putAll(Phase3Native.envFor(
            ctx.applicationInfo.nativeLibraryDir,
            Phase3Native.homeDir(ctx).absolutePath,
            Phase3Native.tmpDir(ctx).absolutePath,
        ))
        env.putAll(
            linkedMapOf(
                "UD_LIB_DIR" to ctx.applicationInfo.nativeLibraryDir,
                // code-server's own switches (the config file carries the same values, the
                // env is what the docs call the supported way to pass the password).
                "PASSWORD" to password,
                "NO_PROXY" to "127.0.0.1,localhost",
                "XDG_DATA_HOME" to Phase3Native.dir(ctx).absolutePath,
                "XDG_CONFIG_HOME" to Phase3Native.dir(ctx).absolutePath,
                "XDG_CACHE_HOME" to Phase3Native.tmpDir(ctx).absolutePath,
            )
        )
        var p: Process? = null
        try {
            p = pb.start()
            val pid = pidOf(p)
            say("CS_START pid=$pid launched_ms=${System.currentTimeMillis() - t0}")
            val exe = try { File("/proc/$pid/exe").canonicalFile.path } catch (_: Throwable) { "?" }
            say("CS_START pid=$pid exe=$exe underNativeLibDir=${exe.startsWith(ctx.applicationInfo.nativeLibraryDir)}")
            streamTo(ctx, p, pid)
        } catch (t: Throwable) {
            say("CS_START FAIL launch: $t")
            return null
        }

        // Wait for code-server's own health endpoint.
        val deadline = System.currentTimeMillis() + waitMs
        var attempt = 0
        var lastCode = -1
        var firstResponseMs = -1L
        var healthMs = -1L
        var body = ""
        while (System.currentTimeMillis() < deadline) {
            attempt++
            val (code, ms, text) = healthz(port)
            if (code != 0 && firstResponseMs < 0) firstResponseMs = System.currentTimeMillis() - t0
            if (code != lastCode || attempt % 10 == 0) {
                say("CS_HEALTH attempt=$attempt code=$code ms=$ms elapsed=${System.currentTimeMillis() - t0} body=${text.take(60)}")
                lastCode = code
            }
            if (code == 200) { healthMs = System.currentTimeMillis() - t0; body = text; break }
            if (!p.isAlive && attempt > 2) {
                say("CS_START FAIL process exited code=${try { p.exitValue() } catch (_: Throwable) { -1 }} " +
                        "after_attempts=$attempt")
                break
            }
            Thread.sleep(300)
        }
        val pid = pidOf(p)
        if (healthMs > 0) {
            say("CS_START OK pid=$pid port=$port healthz=200 body=${body.take(40)} " +
                    "first_response_ms=$firstResponseMs health_ms=$healthMs wait_attempts=$attempt")
            val session = Session(pid, port, password, healthMs)
            try {
                sessionFile(ctx).writeText("pid=$pid\nport=$port\npassword=$password\nhealthMs=$healthMs\n")
            } catch (t: Throwable) { say("CS_START session write failed: $t") }
            describe(ctx, pid)
            return session
        }
        say("CS_START FAIL no healthz=200 within ${waitMs} ms (attempts=$attempt lastCode=$lastCode) pid=$pid alive=${p.isAlive}")
        tail(ctx, 15)
        return null
    }

    /** (Re)write the process facts of [pid] into the log: state, RSS, PSS, CPU ticks, fds. */
    fun describe(ctx: Context, pid: Long) {
        say("CS_PROC pid=$pid ${procLine(pid)}")
        for (c in pidsMatching(Phase3Data.root(ctx).absolutePath)) {
            if (c == pid) continue
            say("CS_PROC child pid=$c ${procLine(c)}")
        }
    }

    fun stop(ctx: Context): Boolean {
        val needle = Phase3Data.root(ctx).absolutePath
        val pids = pidsMatching(needle)
        if (pids.isEmpty()) { say("CS_STOP nothing running (no pid with $needle in cmdline)"); return true }
        for (pid in pids) {
            say("CS_STOP TERM pid=$pid")
            run("/system/bin/kill", "-TERM", pid.toString())
        }
        var left = pids
        for (i in 1..20) {
            Thread.sleep(200)
            left = pidsMatching(needle)
            if (left.isEmpty()) break
        }
        for (pid in left) {
            say("CS_STOP KILL pid=$pid")
            run("/system/bin/kill", "-KILL", pid.toString())
        }
        Thread.sleep(300)
        val after = pidsMatching(needle)
        say("CS_STOP pids=${pids.size} survived=${after.size}${if (after.isEmpty()) "" else " " + after.joinToString(",")}")
        return after.isEmpty()
    }

    /** status: liveness, /healthz, process facts, output tail. */
    fun status(ctx: Context) {
        val marker = sessionFile(ctx)
        val needle = Phase3Data.root(ctx).absolutePath
        val pids = pidsMatching(needle)
        // The session file is app-private, but it does carry the password; redact it from
        // anything that goes to logcat (logcat is world-readable on debuggable builds).
        val sessionText = if (marker.isFile) marker.readText().replace("\n", " ") else "-"
        say("CS_STATUS pids=${pids.joinToString(",").ifEmpty { "-" }} session=${sessionText.replace(Regex("password=[0-9a-f]+"), "password=<redacted>")}")
        val port = marker.takeIf { it.isFile }?.readText()?.lineSequence()
            ?.firstOrNull { it.startsWith("port=") }?.substringAfter('=')?.trim()?.toIntOrNull()
        if (port != null) {
            val (code, ms, text) = healthz(port)
            say("CS_STATUS healthz code=$code ms=$ms body=${text.take(60)}")
        }
        for (pid in pids) say("CS_PROC pid=$pid ${procLine(pid)}")
        tail(ctx, 12)
    }

    // ------------------------------------------------------------------------ helpers --

    private fun streamTo(ctx: Context, p: Process, pid: Long) {
        val out = outFile(ctx)
        Thread({
            var n = 0
            try {
                BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                    FileOutputStream(out, false).use { fos ->
                        var line = r.readLine()
                        while (line != null) {
                            n++
                            fos.write((line + "\n").toByteArray())
                            val interesting = line.contains("listening", ignoreCase = true) ||
                                    line.contains("error", ignoreCase = true) ||
                                    line.contains("warn", ignoreCase = true) ||
                                    line.startsWith("[ud-hook]")
                            if (n <= 120 || interesting || n % 25 == 0) say("CS_OUT[$pid#$n] ${line.take(400)}")
                            line = r.readLine()
                        }
                    }
                }
            } catch (_: Throwable) { }
            say("CS_OUT[$pid] stream ended lines=$n")
        }, "phase3-cs-out").start()
    }

    private fun tail(ctx: Context, lines: Int) {
        val f = outFile(ctx)
        if (!f.isFile) return
        val all = try { f.readLines() } catch (_: Throwable) { return }
        say("CS_TAIL lines=${all.size}")
        for (l in all.takeLast(lines)) say("CS_TAIL ${l.take(300)}")
    }

    private fun pidOf(p: Process): Long =
        try { (p.javaClass.getMethod("pid").invoke(p) as? Long) ?: -1L } catch (_: Throwable) { -1L }

    private fun run(vararg cmd: String): Boolean = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.inputStream.use { it.readBytes() }
        p.waitFor() == 0
    } catch (t: Throwable) { say("CS_RUN FAIL ${cmd.joinToString(" ")}: $t"); false }

    /**
     * Every process of our uid whose command line mentions [needle]. /proc of other uids
     * is not readable and is not ours to look at; [needle] is our own app-private path, so
     * this only ever returns our own processes.
     */
    fun pidsMatching(needle: String): List<Long> {
        val out = mutableListOf<Long>()
        val proc = File("/proc")
        for (d in proc.listFiles() ?: return out) {
            val pid = d.name.toLongOrNull() ?: continue
            if (pid == android.os.Process.myPid().toLong()) continue
            val cmd = try { File(d, "cmdline").readBytes().decodeToString() } catch (_: Throwable) { continue }
            if (cmd.contains(needle)) out.add(pid)
        }
        return out.sorted()
    }

    /** One line of process facts: state, threads, RSS, PSS, CPU ticks. */
    fun procLine(pid: Long): String {
        val stat = try { File("/proc/$pid/stat").readText() } catch (_: Throwable) { return "gone" }
        val close = stat.lastIndexOf(')')
        val fields = stat.substring(close + 2).split(' ')
        val state = fields.getOrNull(0) ?: "?"
        val utime = fields.getOrNull(11) ?: "?"
        val stime = fields.getOrNull(12) ?: "?"
        val threads = fields.getOrNull(17) ?: "?"
        val status = try { File("/proc/$pid/status").readText() } catch (_: Throwable) { "" }
        val rss = Regex("VmRSS:\\s+(\\d+) kB").find(status)?.groupValues?.get(1) ?: "?"
        val pss = try {
            Regex("^Pss:\\s+(\\d+) kB", RegexOption.MULTILINE)
                .find(File("/proc/$pid/smaps_rollup").readText())?.groupValues?.get(1) ?: "?"
        } catch (_: Throwable) { "?" }
        val cmd = try { File("/proc/$pid/cmdline").readBytes().decodeToString().replace('\u0000', ' ').trim() } catch (_: Throwable) { "?" }
        return "state=$state threads=$threads rss_kb=$rss pss_kb=$pss cpu_ticks=${utime}+${stime} cmd=${cmd.take(120)}"
    }
}
