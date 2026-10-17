package dev.unifieddesktop.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Phase 2 (T6): run Wine (Kron4ek 11.19 amd64, pure DATA) through libbox64 on the
 * Xvfb display, boot a WINEPREFIX under our private files, and start Notepad++
 * (Notepad++ portable zip unpacked as data). All processes come from
 * ProcessBuilder(nativeLibraryDir/libbox64.so, ...) -> /proc/<pid>/exe is libbox64.
 */
object Phase2Wine {
    const val TAG = "Phase2"

    private val log = StringBuilder()
    fun say(s: String) {
        android.util.Log.i(TAG, s)
        if (log.length < LOG_CAP) {
            log.append(s).append('\n')
            if (log.length >= LOG_CAP) log.append("(wine log truncated at $LOG_CAP chars)\n")
        }
    }
    fun logText(): String = log.toString()
    private const val LOG_CAP = 6_000_000

    fun writeLog(ctx: Context) {
        try {
            val dir = File(ctx.filesDir, "phase2"); dir.mkdirs()
            FileOutputStream(File(dir, "wine.log")).use { it.write(logText().toByteArray(Charsets.UTF_8)) }
        } catch (_: Throwable) { }
    }

    var wineProc: Process? = null
    var winePid: Int = 0
    var wsrvProc: Process? = null
    var wsrvPid: Int = 0

    /** Extra environment for the next wine start (e.g. WINEDEBUG for a traced run). */
    var extraEnv: Map<String, String> = emptyMap()

    // ---- wine data patches (applied on the phone; B-017 / B-019 / round 3) --
    //
    // Two byte-exact, length-preserving patch families. Every site is rewritten
    // only when its exact bytes are found (unique occurrence); for the default
    // 11.19 pin the final sha256 of each file is pinned and verified on disk, so
    // a wrong/different wine build can never be patched silently. Round 3 adds
    // candidate pins (9.0 / 10.0 / 11.0): their sites were located byte-exactly
    // in the pinned tarballs on the laptop (2026-10-17) and the tarball itself is
    // SHA-256 verified at download, so only byte guards are used for them.
    //
    // 1) server socket dir under WINEPREFIX (B-017 follow-up, all pins).
    //    The plain x86-64 builds hardcode "/tmp/.wine-%u[...]" for the wineserver
    //    socket directory; Android's /tmp is not writable by an app, so the server
    //    died with "mkdir /tmp/.wine-<uid>: Permission denied". Both sides chdir
    //    to $WINEPREFIX before building that path (server: server/request.c
    //    create_server_dir; client: ntdll setup_config_dir -> init_server_dir),
    //    so dropping the leading "/tmp/" and NUL-padding the tail by the same 5
    //    bytes makes the path prefix-relative and identical on both sides.
    //    Strings per pin (from the tarballs, all unique in their files):
    //      9.0  ntdll     "/tmp/.wine-%u/server-%s"
    //      9.0  server    "/tmp/.wine-%u"
    //      10.0/11.0/11.19 ntdll, 10.0/11.0/11.19 server: as below.
    //
    // 2) 39-bit host address-space asserts (B-017, wine >= 9.17 only).
    //    wine >= 9.17 computes the host address-space limit at runtime. On this
    //    39-bit device it is 0x7fffff0000, and virtual_init calls
    //    anon_mmap_alloc with a size that is not a multiple of the host page, so
    //    the inlined sanity assert `assert(!(size & host_page_mask))` fired and
    //    wineboot SIGABRTed 149 ms in. An mmap length need not be page aligned
    //    (the kernel rounds it up), so making the guard test 0 is safe.
    //    9.0/10.0 have no such assert (checked in their source and binaries);
    //    11.0 has it in two copies (virtual_init inlined + the out-of-line
    //    anon_mmap_alloc), both defused; 11.19 keeps the round-2 single site.
    //
    // sha256 pins for 11.19 (pristine -> patched):
    //   ntdll.so   26c76dc2.. -> c378cd70..
    //   wineserver d40f733c.. -> d01d4f75..
    private const val NTDLL_ORIG_SHA = "26c76dc28af8578b4fe127ef03f934e704893fec86a0b693e23480a6cf899c14"
    private const val NTDLL_PATCHED_SHA = "c378cd70685ac1fbf01034aef2b95f3d1bfbb6bf94b4447aa2389a5d867387af"
    private const val WSRV_ORIG_SHA = "d40f733c90aca3f1e8d4bf86b9b2a5c3ec8c5f4fa12f1b061c0adc6422d52e01"
    private const val WSRV_PATCHED_SHA = "d01d4f7516661cc7de0729dae6c3b66ee3b7a2cd0af85cfe7666b217fd7d07ea"

    internal class Site(val old: ByteArray, val next: ByteArray, val note: String)

    private fun ascii(s: String, pad: Int = 0) = (s + "\u0000".repeat(pad)).toByteArray(Charsets.UTF_8)

    private fun xb(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] =
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        return out
    }

    /** "/tmp/.wine-..." -> ".wine-..." with the 5 removed bytes re-added as NUL
     *  pad: one contiguous literal of equal length, prefixed by the cwd. */
    internal fun prefixRelSite(s: String, note: String): Site {
        val core = s.removePrefix("/tmp/")
        return Site(ascii(s), ascii(core, s.length - core.length), note)
    }

    internal fun hexSite(oldHex: String, nextHex: String, note: String): Site =
        Site(xb(oldHex), xb(nextHex), note)

    private const val CLIENT_STR_9 = "/tmp/.wine-%u/server-%s"
    private const val CLIENT_STR_10PLUS = "/tmp/.wine-%u/server-%llx-%llx"
    private const val SERVER_STR = "/tmp/.wine-%u"

    /** Exact site list per pin (client ntdll.so, server wineserver). */
    internal fun wineSitesFor(pinId: String): Pair<List<Site>, List<Site>> = when (pinId) {
        "9.0" -> listOf(prefixRelSite(CLIENT_STR_9, "server dir -> prefix-relative")) to
            listOf(prefixRelSite(SERVER_STR, "server dir -> prefix-relative"))
        "10.0" -> listOf(prefixRelSite(CLIENT_STR_10PLUS, "server dir -> prefix-relative")) to
            listOf(prefixRelSite(SERVER_STR, "server dir -> prefix-relative"))
        "11.0" -> listOf(
            hexSite("f7c6ff0f00000f8589000000", "f7c6000000000f8589000000",
                "39bit assert virtual_init test 0xfff->0"),
            hexSite("f7c7ff0f0000751a89f2", "f7c700000000751a89f2",
                "39bit assert anon_mmap_alloc test 0xfff->0"),
            prefixRelSite(CLIENT_STR_10PLUS, "server dir -> prefix-relative"),
        ) to listOf(prefixRelSite(SERVER_STR, "server dir -> prefix-relative"))
        else -> listOf(
            hexSite("f7c6f80f00000f85", "f7c6000000000f85", "39bit assert test 0xff8->0"),
            prefixRelSite(CLIENT_STR_10PLUS, "server dir -> prefix-relative"),
        ) to listOf(prefixRelSite(SERVER_STR, "server dir -> prefix-relative"))
    }

    private fun sha256Hex(b: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(b)
        val sb = StringBuilder(64)
        for (x in d) sb.append(String.format("%02x", x))
        return sb.toString()
    }

    private fun occurrences(hay: ByteArray, needle: ByteArray): MutableList<Int> {
        val out = mutableListOf<Int>()
        if (needle.isEmpty()) return out
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            out.add(i)
            if (out.size > 4) break // enough to know it is ambiguous
        }
        return out
    }

    /** Apply the site list to one file; verify the pinned final sha256 on disk
     *  when the pin has one (11.19). Returns false on any guard failure. */
    private fun patchFile(f: File, origSha: String?, finalSha: String?, sites: List<Site>, label: String): Boolean {
        if (!f.isFile) { say("WINE_PATCH_FAIL $label missing ${f.path}"); return false }
        var bytes = f.readBytes()
        val before = sha256Hex(bytes)
        if (finalSha != null && before == finalSha) { say("WINE_PATCH $label already-patched sha256=${before.take(16)}e"); return true }
        if (origSha != null && before != origSha) say("WINE_PATCH $label note: input sha256=$before (not pristine; using byte guards)")
        var changed = false
        for (site in sites) {
            val hits = occurrences(bytes, site.old)
            if (hits.size == 1) {
                val copy = bytes.copyOf()
                for (i in site.next.indices) copy[hits[0] + i] = site.next[i]
                bytes = copy
                changed = true
                say("WINE_PATCH $label ${site.note} @0x${hits[0].toString(16)}")
            } else if (hits.isEmpty()) {
                if (occurrences(bytes, site.next).isEmpty()) {
                    say("WINE_PATCH_FAIL $label ${site.note}: pattern not found"); return false
                }
                say("WINE_PATCH $label ${site.note} already applied")
            } else {
                say("WINE_PATCH_FAIL $label ${site.note}: ${hits.size} occurrences"); return false
            }
        }
        val after = sha256Hex(bytes)
        if (finalSha != null && after != finalSha) { say("WINE_PATCH_FAIL $label self-check sha256=$after"); return false }
        if (!changed) return true // every site already in patched form; nothing to write
        val tmp = File(f.parentFile, f.name + ".new")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { say("WINE_PATCH_FAIL $label rename"); tmp.delete(); return false }
        val onDisk = sha256Hex(f.readBytes())
        if (finalSha != null && onDisk != finalSha) { say("WINE_PATCH_FAIL $label on-disk sha256=$onDisk"); return false }
        if (finalSha == null) {
            for (site in sites) if (occurrences(f.readBytes(), site.next).isEmpty()) {
                say("WINE_PATCH_FAIL $label post-write verify: ${site.note} missing"); return false
            }
        }
        say("WINE_PATCH $label patched ${before.take(16)}e -> ${after.take(16)}e")
        return true
    }

    /** Idempotently apply every wine data patch for the active pin. */
    fun ensureWinePatched(ctx: Context): Boolean {
        val pin = Phase2Data.winePin(ctx)
        val (clientSites, serverSites) = wineSitesFor(pin.id)
        val root = pinRoot(ctx)
        val strict = pin.id == "11.19"
        val ntdll = patchFile(
            File(root, "lib/wine/x86_64-unix/ntdll.so"),
            if (strict) NTDLL_ORIG_SHA else null,
            if (strict) NTDLL_PATCHED_SHA else null,
            clientSites, "ntdll.so")
        val server = patchFile(
            File(root, "bin/wineserver"),
            if (strict) WSRV_ORIG_SHA else null,
            if (strict) WSRV_PATCHED_SHA else null,
            serverSites, "wineserver")
        return ntdll && server
    }

    // ---- prefix self-heal (B-021) ------------------------------------------
    //
    // A saved system.reg whose HKLM\System\CurrentControlSet symlink has an
    // EMPTY SymbolicLinkValue (`"SymbolicLinkValue"=hex(0):`) poisons the whole
    // prefix: the wine server's key_lookup_name rejects any open/create of
    // CurrentControlSet with STATUS_OBJECT_NAME_NOT_FOUND (0xC0000034), and
    // kernelbase's create_key then alternates create/open on the SAME name
    // (~0x1D0 bytes of stack per level) until the ~2 MB host stack overflows:
    // wineboot SIGSEGVs at 0x80001000, "could not load kernel32.dll, status
    // c0000135", exit 53. Measured on the phone 2026-10-17 on the round-1
    // staged prefix (system.reg 340 B, the link saved as hex(0)).
    //
    // A correct link is saved as `hex(6):` + the target path (UTF-16LE); the
    // server creates it that way whenever system.reg is absent, and every
    // in-app path saves hex(6) (verified on the phone after a fresh init).
    // A poisoned link can therefore only exist in a prefix that never booted,
    // so dropping the hive set (the server recreates system+user defaults on
    // its next start) loses nothing.
    //
    // The second heal trigger is the boot marker: <prefix>/.ud-boot-ok is
    // written only when `wineboot -i` exits 0. Hives without the marker mean
    // the last boot never completed (crash, app killed) — reset and boot
    // fresh, so a half-built registry can never wedge later boots.
    internal const val POISON_LINK = "\"SymbolicLinkValue\"=hex(0):"
    private const val BOOT_OK_MARK = ".ud-boot-ok"

    fun bootMarker(ctx: Context): File = File(prefixDir(ctx), BOOT_OK_MARK)

    /** Detect+remove hives from a prefix whose last boot never completed.
     *  True when hives were removed and the caller must restart the server
     *  (killing any live one first) so it re-inits the registry from scratch. */
    fun healBrokenPrefix(ctx: Context): Boolean {
        val pfx = prefixDir(ctx)
        val hives = listOf("system.reg", "user.reg", "userdef.reg").map { File(pfx, it) }
        val sys = hives[0]
        if (!sys.isFile) return false
        val poisoned = try {
            sys.readBytes().toString(Charsets.ISO_8859_1).contains(POISON_LINK)
        } catch (t: Throwable) {
            say("PREFIX_HEAL read failed: $t")
            return false
        }
        val bootOk = bootMarker(ctx).isFile
        if (!poisoned && bootOk) return false
        val gone = hives.count { it.isFile && it.delete() }
        bootMarker(ctx).delete()
        val why = if (poisoned) "empty CurrentControlSet SymbolicLinkValue"
                  else "last wineboot -i never completed (no $BOOT_OK_MARK)"
        say("PREFIX_HEAL $why; removed $gone hive file(s); a fresh server init will rebuild them")
        writeLog(ctx)
        return true
    }

    /** Delete every saved hive and report how many were removed. boot() uses
     *  this unconditionally: a wineserver that loads saved hives loses the
     *  CurrentControlSet link (proven 2026-10-17), so only a fresh init is
     *  safe. The .update-timestamp file (not a hive) survives, so wineboot -i
     *  stays on its fast path. */
    private fun dropHives(ctx: Context): Int {
        val pfx = prefixDir(ctx)
        return listOf("system.reg", "user.reg", "userdef.reg").map { File(pfx, it) }
            .count { it.isFile && it.delete() }
    }

    // ---- prefix module symlinks (B-023) ------------------------------------
    //
    // wineboot -i never fills drive_c/windows/system32 in this environment: the
    // fake-DLL update child aborts under box64, so the Windows system
    // directories stay empty. Wine's builtin fallback resolves only a couple of
    // bootstrap modules by name; everything else must exist as a real file or
    // load/exec fails — pre-farm, starting explorer.exe died with c0000034 and
    // winex11.drv never loaded (the display driver is loaded through the
    // registry GraphicsDriver value + NtUserLoadDriver, which needs the file).
    // Linking every module from the wine tree into system32 / syswow64
    // (1008 + 1104 symlinks on the phone) made winex11.so load and explorer.exe
    // start. Refreshed on every boot and app start; existing entries are left
    // alone, so a second call is ~2000 stat()s and no writes.
    internal fun farmPlan(srcNames: List<String>, existing: Set<String>): List<String> =
        srcNames.filter { it !in existing }

    fun ensurePrefixBinaries(ctx: Context): Boolean {
        val wineLib = File(pinRoot(ctx), "lib/wine")
        val windows = File(prefixDir(ctx), "drive_c/windows")
        var made = 0
        var failed = 0
        for ((sub, target) in listOf("x86_64-windows" to "system32", "i386-windows" to "syswow64")) {
            val src = File(wineLib, sub)
            if (!src.isDirectory) continue
            val dst = File(windows, target)
            dst.mkdirs()
            val entries = src.listFiles() ?: continue
            val onDisk = entries.filter { File(dst, it.name).exists() }.map { it.name }.toSet()
            for (name in farmPlan(entries.map { it.name }, onDisk)) {
                val link = File(dst, name)
                try {
                    // a dangling link from an older tree layout fails exists()
                    // and must be replaced, not kept
                    link.delete()
                    android.system.Os.symlink(File(src, name).absolutePath, link.absolutePath)
                    made++
                } catch (t: Throwable) {
                    failed++
                    if (failed == 1) say("PREFIX_BINARIES link failed: $t")
                }
            }
        }
        if (made > 0 || failed > 0) say("PREFIX_BINARIES linked=$made failed=$failed")
        return failed == 0
    }

    // ---- safety cap (B-022) ------------------------------------------------
    //
    // A wine run exploded to 333 orphaned processes under our uid (wine kept
    // starting explorer.exe /desktop instances, B-022) and took the phone's
    // system_server down with it. While wine runs, this guard samples our uid's
    // process count and the system's MemAvailable every 5 s and kills the whole
    // tree before that can happen again. The decision is a pure function so it
    // is unit-tested: trip above 40 processes or below 600 MB available.
    internal const val SAFETY_MAX_PROCS = 40
    internal const val SAFETY_MIN_MEM_MB = 600

    internal fun safetyCapTrips(procCount: Int, memAvailableMb: Int): Boolean =
        procCount > SAFETY_MAX_PROCS || memAvailableMb < SAFETY_MIN_MEM_MB

    private fun readUid(dir: File): Int? = try {
        val m = Regex("^Uid:\\s+(\\d+)", RegexOption.MULTILINE).find(File(dir, "status").readText())
        m?.groupValues?.get(1)?.toInt()
    } catch (_: Throwable) { null }

    private fun ourUidProcCount(): Int {
        val uid = android.os.Process.myUid()
        var n = 0
        File("/proc").listFiles { f -> f.name.all { it.isDigit() } }?.forEach { d ->
            if (readUid(d) == uid) n++
        }
        return n
    }

    private fun memAvailableMb(): Int = try {
        File("/proc/meminfo").readLines().first { it.startsWith("MemAvailable:") }
            .split(Regex("\\s+"))[1].toInt() / 1024
    } catch (_: Throwable) { Int.MAX_VALUE }

    /** SIGKILL every process of our uid except this one (the watchdog's
     *  `kill -9 -1`, but in-app so the log survives). */
    fun killAllOurProcesses() {
        val uid = android.os.Process.myUid()
        val me = android.os.Process.myPid()
        File("/proc").listFiles { f -> f.name.all { it.isDigit() } }?.forEach { d ->
            try {
                val pid = d.name.toInt()
                if (pid != me && readUid(d) == uid) android.os.Process.killProcess(pid)
            } catch (_: Throwable) { }
        }
    }

    private var guardThread: Thread? = null

    private fun startSafetyGuard(ctx: Context) {
        if (guardThread?.isAlive == true) return
        guardThread = Thread({
            say("SAFETY_GUARD start max_procs=$SAFETY_MAX_PROCS min_mem_mb=$SAFETY_MIN_MEM_MB")
            writeLog(ctx)
            while (true) {
                Thread.sleep(5000)
                val n = ourUidProcCount()
                val m = memAvailableMb()
                if (safetyCapTrips(n, m)) {
                    say("SAFETY_GUARD TRIP procs=$n memMB=$m -> killing the whole tree")
                    killAllOurProcesses()
                    writeLog(ctx)
                    return@Thread
                }
            }
        }, "safety-guard").apply { start() }
    }

    fun wineDir(ctx: Context): File = File(Phase2Data.rootDir(ctx), "wine")

    /** The unpacked tree of the active pin (each candidate has its own dir). */
    fun pinRoot(ctx: Context): File = File(wineDir(ctx), Phase2Data.winePin(ctx).dir)

    fun wineBin(ctx: Context): File = File(pinRoot(ctx), "bin/wine")

    /** The direct wine loader of the active pin (B-024: 11.x bin/wine dies in
     *  map_shared_user_data with c0000018; the 64-bit-only loader boots). */
    fun loaderBin(ctx: Context): File =
        File(pinRoot(ctx), Phase2Data.winePin(ctx).loaderRel)

    /** wineboot's real file: the bare name "wineboot" is not resolved by wine's
     *  builtin fallback here, so boot passes this explicit path. */
    fun winebootExe(ctx: Context): File =
        File(pinRoot(ctx), "lib/wine/x86_64-windows/wineboot.exe")

    fun wineserverBin(ctx: Context): File = File(pinRoot(ctx), "bin/wineserver")

    /** Per-pin prefix (round 3): candidates must never share a prefix. The
     *  default pin takes over the round-2 "wineprefix" once, which keeps the
     *  fast-path .update-timestamp. */
    fun prefixDir(ctx: Context): File {
        val pin = Phase2Data.winePin(ctx)
        val dir = File(Phase2Data.rootDir(ctx), "wineprefix-" + pin.id)
        if (pin.id == Phase2Data.DEFAULT_WINE_PIN && !dir.exists()) {
            val legacy = File(Phase2Data.rootDir(ctx), "wineprefix")
            if (legacy.isDirectory && legacy.renameTo(dir)) say("PREFIX_MIGRATE wineprefix -> ${dir.name}")
        }
        return dir
    }

    /** Harness action `pin:<id>` (broadcast extra) / WINE_PIN env knob. */
    fun setPin(ctx: Context, id: String) {
        val f = File(ctx.filesDir, "phase2/pin.txt")
        if (id.isBlank()) {
            f.delete()
            say("PIN cleared -> default ${Phase2Data.DEFAULT_WINE_PIN}")
        } else {
            val known = Phase2Data.WINE_PINS.firstOrNull { it.id == id }
            if (known == null) {
                say("PIN unknown [$id] known=${Phase2Data.WINE_PINS.joinToString(",") { it.id }}")
            } else {
                f.parentFile?.mkdirs()
                f.writeText(id + "\n")
                say("PIN set -> $id (url=${known.url.substringAfterLast('/')})")
            }
        }
        writeLog(ctx)
    }
    fun nppDir(ctx: Context): File = File(Phase2Data.rootDir(ctx), "apps/npp")
    fun nppExe(ctx: Context): File = File(nppDir(ctx), "notepad++.exe")

    // ---- wineserver supervision -------------------------------------------
    //
    // Wine itself spawns the server with posix_spawn (ntdll start_server). Under
    // box64 that child dies right after it binds its socket, leaving the client
    // blocked forever on its reply pipe (frozen CPU time; see B-020). The app
    // therefore starts and supervises bin/wineserver itself as a plain
    // ProcessBuilder exec of libbox64.so; wine's client then finds the listening
    // socket in $WINEPREFIX and connects, never spawning anything. Verified on
    // the phone: supervised server + wineboot -i -> exit 0, prefix initialized.

    private fun serverBaseDir(ctx: Context): File =
        File(prefixDir(ctx), ".wine-" + android.os.Process.myUid())

    private fun serverDirs(ctx: Context): List<File> =
        serverBaseDir(ctx).listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("server-") } ?: emptyList()

    /** True when a live wineserver accepts connections (a stale socket file does not). */
    private var lastProbeErr = ""
    private fun serverAlive(ctx: Context): Boolean {
        lastProbeErr = ""
        for (srv in serverDirs(ctx)) {
            val sock = File(srv, "socket")
            if (!sock.exists()) continue
            try {
                android.net.LocalSocket().use { s ->
                    s.connect(android.net.LocalSocketAddress(
                        sock.absolutePath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                    return true
                }
            } catch (t: Throwable) {
                lastProbeErr = "LocalSocket(${srv.name}): $t"
            }
        }
        return false
    }

    /** The live server also holds a POSIX lock on <serverdir>/lock (wine -k uses it). */
    private fun lockHeldByServer(ctx: Context): Boolean {
        for (srv in serverDirs(ctx)) {
            val lock = File(srv, "lock")
            if (!lock.exists()) continue
            try {
                java.io.RandomAccessFile(lock, "rw").use { raf ->
                    val l = raf.channel.tryLock()
                    if (l == null) return true else l.release()
                }
            } catch (_: java.nio.channels.OverlappingFileLockException) {
                return true
            } catch (_: Throwable) { }
        }
        return false
    }

    /** Real wineserver daemon pid: wine's server daemonizes (fork + launcher exit,
     *  PPID 1, fds 0/1 on /dev/null), so the launcher pid is not the daemon pid. */
    fun wineserverPid(): Int {
        val dirs = File("/proc").listFiles { f -> f.name.all { it.isDigit() } } ?: return -1
        for (d in dirs) {
            try {
                if (File(d, "comm").readText().trim() == "wineserver") return d.name.toInt()
            } catch (_: Throwable) { }
        }
        return -1
    }

    /** Start the wineserver once (persistent) and wait until it accepts connections. */
    fun ensureWineserver(ctx: Context): Boolean {
        if (serverAlive(ctx)) { say("WINESERVER already-listening"); return true }
        val base = serverBaseDir(ctx)
        // A stale server dir (dead server) makes bind() fail with EADDRINUSE.
        base.listFiles()?.filter { it.isDirectory && it.name.startsWith("server-") }
            ?.forEach { it.deleteRecursively() }
        val pfx = prefixDir(ctx)
        if (!pfx.isDirectory) {  // the server exits if WINEPREFIX is missing
            pfx.mkdirs()
            try { android.system.Os.chmod(pfx.path, 448 /* 0700 */) } catch (_: Throwable) { }
        }
        val bin = wineserverBin(ctx)
        if (!bin.isFile) { say("WINESRV_FAIL missing $bin"); return false }
        val dbgFile = File(File(ctx.filesDir, "phase2"), "wsdebug.txt")
        val argv = mutableListOf(Phase2Box64.libbox64(ctx).path, bin.absolutePath, "-p", "3600")
        // Dev aid: a marker file files/phase2/wsdebug.txt starts the server with
        // -f (skip daemonize, stderr stays on our pipe) and -d (request/reply
        // trace); the drain below captures it as WINESERVER_OUT lines.
        if (dbgFile.isFile) {
            argv += listOf("-d", "-f")
            say("WINESERVER debug-trace on (-d -f)")
        }
        val r = ProcessBuilder(argv).apply {
            environment().clear()
            environment().putAll(wineEnv(ctx))
            redirectErrorStream(true)
        }.start()
        wsrvProc = r
        wsrvPid = Phase2X11.pidOf(r)
        val t0 = System.currentTimeMillis()
        say("WINESERVER pid=$wsrvPid -p 3600")
        Phase2X11.g3exe("WINESERVER", ctx, wsrvPid)
        Thread({
            if (dbgFile.isFile) {
                // trace mode: keep every server line in its own uncapped file
                // (the in-memory phase2 logs keep only the first 6 MB)
                try {
                    val f = File(File(ctx.filesDir, "phase2"), "wsrv.log")
                    java.io.FileWriter(f, false).use { w ->
                        r.inputStream.bufferedReader().forEachLine { line ->
                            w.write(line); w.write("\n"); w.flush()
                        }
                    }
                } catch (_: Throwable) { }
            } else try { Phase2X11.drain("WINESERVER_OUT", r.inputStream) } catch (_: Throwable) { }
            say("WINESERVER_EOF exit=${try { r.waitFor() } catch (_: Throwable) { -1 }}")
            writeLog(ctx)
        }, "wineserver-out").start()
        val deadline = t0 + 30000
        while (System.currentTimeMillis() < deadline) {
            if (serverAlive(ctx)) {
                say("WINESERVER ready ms=${System.currentTimeMillis() - t0} daemon=${wineserverPid()}")
                writeLog(ctx)
                return true
            }
            if (lockHeldByServer(ctx) && System.currentTimeMillis() - t0 > 4000) {
                // the lock is held by the server but our java connect probe failed
                // (Android java.net quirk); the lock only exists while the server runs
                say("WINESERVER ready-lock ms=${System.currentTimeMillis() - t0} " +
                    "daemon=${wineserverPid()} probe-err=[$lastProbeErr]")
                writeLog(ctx)
                return true
            }
            Thread.sleep(250)
        }
        say("WINESRV_FAIL not listening after 30 s (launcher alive=${r.isAlive} " +
            "lock=${lockHeldByServer(ctx)} probe-err=[$lastProbeErr])")
        writeLog(ctx)
        return false
    }

    private fun wineEnv(ctx: Context): Map<String, String> {
        val m = Phase2X11.baseEnv(ctx).toMutableMap()
        // wine ships its own libraries inside the tarball; add them for externals
        val w = pinRoot(ctx)
        val pin = Phase2Data.winePin(ctx)
        m["DISPLAY"] = "127.0.0.1:0"
        m["WINEPREFIX"] = prefixDir(ctx).path
        m["LD_LIBRARY_PATH"] = listOf(
            "${w.path}/lib64",
            m["LD_LIBRARY_PATH"] ?: "",
        ).joinToString(":")
        m["BOX64_LD_LIBRARY_PATH"] = m["LD_LIBRARY_PATH"] ?: ""
        // Wine spawns its own children (CreateProcess -> re-exec of the wine
        // loader). Under box64 those children cannot derive the build layout
        // from /proc/self/exe, so the fresh-prefix update child (rundll32)
        // aborted with "could not load kernel32.dll, status c0000135" and the
        // boot never completed. Pin the DLL path and the loader path so every
        // child finds them without guessing.
        m["WINEDLLPATH"] = "${w.path}/lib/wine/x86_64-windows"
        m["WINELOADER"] = File(w, pin.loaderRel).path
        // box64 reads LD_LIBRARY_PATH itself; keep both in sync
        m.putAll(extraEnv)
        return m
    }

    /** Launch the wine CLI (args are wine's own arguments); boot/startNpp pass
     *  explicit binaries instead. The direct loader is the only working entry
     *  under box64 (B-024). */
    fun start(ctx: Context, waitForExit: Boolean, vararg args: String): Int =
        startBin(ctx, loaderBin(ctx), waitForExit, *args)

    /** Run any x86-64 binary from the wine tree under box64 with the wine env. */
    fun startBin(ctx: Context, bin: File, waitForExit: Boolean, vararg args: String): Int {
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
                var lastFlush = 0L
                try {
                    while (true) {
                        val n = r.inputStream.read(buf)
                        if (n <= 0) break
                        val s = String(buf, 0, n).replace('\n', ' ').trim()
                        if (s.isNotBlank()) {
                            say("WINE_OUT $s")
                            // flush often enough that a crash keeps the output, but
                            // not on every 8 KiB chunk: a traced run (WINEDEBUG) can
                            // stream megabytes and writeLog rewrites the whole log.
                            val now = System.currentTimeMillis()
                            if (now - lastFlush > 3000) { writeLog(ctx); lastFlush = now }
                        }
                    }
                    say("WINE_EOF exit=${try { r.waitFor() } catch (_: Throwable) { -1 }} ms=${System.currentTimeMillis() - t0}")
                    writeLog(ctx)
                } catch (t: Throwable) {
                    say("WINE_OUT_ERR $t")
                    writeLog(ctx)
                }
            }, "wine-out").start()
        }
        return winePid
    }

    fun boot(ctx: Context, winedebug: String? = null) {
        Phase2X11.ensureXvfb(ctx)
        // B-021: kill the server BEFORE touching the hives. A live server keeps
        // the registry in memory and re-saves system.reg while the heal deletes
        // the files; round 2 proved that heal-then-kill leaves the poisoned link
        // behind even though the delete "succeeded".
        killWine(logStop = false)
        // round 2 (2026-10-17) proved a second layer: a server that LOADS saved
        // hives loses the CurrentControlSet link — a fresh server on a good
        // hex(6) system.reg rewrote it as hex(0) within seconds and every
        // registry access then recursed to a stack overflow, while the same
        // prefix booted fine from an empty init. The link is only ever correct
        // right after a fresh init, so every boot drops the hives; the heals
        // below are then the normal path and .update-timestamp keeps
        // wineboot -i at ~35 s.
        healBrokenPrefix(ctx)   // reports poisoned / never-booted hives
        val dropped = dropHives(ctx)
        say("PREFIX_HIVES dropped=$dropped (a fresh server init rebuilds them)")
        if (!ensureWinePatched(ctx)) { say("WINE_BOOT_ABORT wine patch failed"); writeLog(ctx); return }
        ensurePrefixBinaries(ctx)
        if (!ensureWineserver(ctx)) { say("WINE_BOOT_ABORT wineserver failed"); writeLog(ctx); return }
        extraEnv = if (winedebug.isNullOrEmpty()) emptyMap() else mapOf("WINEDEBUG" to winedebug)
        // B-022: never let a wine run grow past the safety cap again
        startSafetyGuard(ctx)
        // first-run prefix bootstrap (does real work the first time). Explicit
        // paths are mandatory under box64: the bare name "wineboot" is not
        // found by wine's builtin fallback, and bin/wine dies in
        // map_shared_user_data (c0000018) — the direct loader works (B-024).
        // The marker is cleared first and re-written only on exit 0, so a failed
        // boot can never be trusted by the next one (stale-marker fix).
        bootMarker(ctx).delete()
        say("PREFIX_MARK cleared; re-written only when wineboot -i exits 0")
        startBin(ctx, loaderBin(ctx), waitForExit = false, winebootExe(ctx).absolutePath, "-i")
        // A completed boot is the marker every later boot trusts; any other
        // exit (crash, kill, timeout) leaves it absent so the next boot heals.
        val p = wineProc
        Thread({
            val code = try { p?.waitFor() } catch (_: Throwable) { -1 }
            if (code == 0) {
                try { bootMarker(ctx).writeText("wineboot -i exit 0 @ ${System.currentTimeMillis()}\n") } catch (_: Throwable) {}
                say("PREFIX_BOOT_OK wineboot -i exit 0; $BOOT_OK_MARK written")
            } else say("PREFIX_BOOT_FAIL wineboot exit=$code (no $BOOT_OK_MARK)")
            writeLog(ctx)
        }, "wineboot-wait").start()
        // flush the spawn + G3 lines immediately; the drain thread keeps writing
        Phase2Wine.writeLog(ctx)
    }

    /**
     * Diagnostic: run any tree binary under box64 with the wine env.
     * Paths are relative to the active pin's root. Example:
     * wineexec:bin/wineserver,-d,2,-p,3600
     */
    fun execTree(ctx: Context, spec: String = "") {
        val parts = spec.replace(' ', ',').split(',').filter { it.isNotBlank() }
        if (parts.isEmpty()) { say("WINEEXEC fail: empty path"); return }
        val bin = File(pinRoot(ctx), parts[0])
        say("WINEEXEC ${parts.joinToString(" ")}")
        startBin(ctx, bin, waitForExit = false, *parts.drop(1).toTypedArray())
        writeLog(ctx)
    }

    fun startNpp(ctx: Context) {
        if (!nppExe(ctx).isFile) { say("WINE fail: ${nppExe(ctx)} missing"); return }
        Phase2X11.ensureXvfb(ctx)
        ensureWinePatched(ctx)
        ensurePrefixBinaries(ctx)
        if (!ensureWineserver(ctx)) { say("WINE fail: no wineserver"); return }
        startSafetyGuard(ctx)
        startBin(ctx, loaderBin(ctx), waitForExit = false, nppExe(ctx).absolutePath)
        writeLog(ctx)
    }

    fun killWine(logStop: Boolean = true) {
        wineProc?.let { it.destroy(); if (logStop) say("WINE_STOP pid=$winePid") }
        wineProc = null
        val wsPid = wineserverPid()
        if (wsPid > 0) {
            android.os.Process.killProcess(wsPid)
            if (logStop) say("WINESERVER_STOP pid=$wsPid")
        }
        wsrvProc?.destroy()
        wsrvProc = null
    }
}
