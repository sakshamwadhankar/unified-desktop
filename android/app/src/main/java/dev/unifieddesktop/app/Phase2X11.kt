package dev.unifieddesktop.app

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.system.Os
import java.nio.file.Files

/**
 * Phase 2 (T4/T5/T6): run the X server (Xvfb, x86-64 Debian data) through libbox64
 * as a child of our app process, listening on TCP 127.0.0.1:6000 only (unix sockets
 * are disabled because our uid cannot write /tmp - proven at runtime), talk X11
 * directly from Java for GetImage captures and XTEST typing, and launch X clients
 * (xeyes, wine apps) on that display.
 *
 * Every process we start goes through ProcessBuilder(nativeLibraryDir/libbox64.so,
 * <data-file>...), so /proc/<pid>/exe stays inside nativeLibraryDir (gate G3).
 */
object Phase2X11 {
    const val TAG = "Phase2"

    private val log = StringBuilder()
    fun say(s: String) { android.util.Log.i(TAG, s); log.append(s).append('\n') }
    fun logText(): String = log.toString()

    fun writeLog(ctx: Context) {
        try {
            val dir = File(ctx.filesDir, "phase2"); dir.mkdirs()
            FileOutputStream(File(dir, "x11.log")).use { it.write(logText().toByteArray(Charsets.UTF_8)) }
        } catch (_: Throwable) { }
    }

    /** files/phase2 — evidence + temp diagnostic logs live here. */
    fun logDir(ctx: Context): File = File(ctx.filesDir, "phase2").apply { mkdirs() }

    /**
     * Gate G3 evidence for one started process: readlink /proc/<pid>/exe must
     * resolve inside nativeLibraryDir — the only code Android executes for us
     * is the APK's own lib*.so (libbox64); guests are pure data under it.
     */
    fun g3exe(tag: String, ctx: Context, pid: Int) {
        val exe = try { File("/proc/$pid/exe").canonicalFile.path } catch (t: Throwable) { "ERR $t" }
        val under = exe.startsWith(ctx.applicationInfo.nativeLibraryDir)
        say("G3_EXE $tag pid=$pid proc/exe=$exe underNativeLibraryDir=$under")
    }

    var xvfb: Process? = null
    var xvfbPid: Int = 0

    /** Process.pid() is hidden on some API levels; use the pid field. */
    fun pidOf(p: Process): Int = try {
        val f = p.javaClass.getDeclaredField("pid")
        f.isAccessible = true; f.getInt(p)
    } catch (_: Throwable) { -1 }

    private fun alive(p: Process): Boolean = try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }

    private fun dataRoot(ctx: Context): File = Phase2Data.rootDir(ctx)

    fun tmpDir(ctx: Context): File = File(dataRoot(ctx), "tmp").apply { mkdirs() }
    fun homeDir(ctx: Context): File = File(dataRoot(ctx), "home").apply { mkdirs() }

    /** env for X clients (xeyes / wine): base + DISPLAY to our TCP server. */
    fun clientEnv(ctx: Context): Map<String, String> =
        baseEnv(ctx) + mapOf("DISPLAY" to "127.0.0.1:0")

    // -------------------------------------------------------------------- T4

    /** Evidence: our uid has no writable /tmp. TCP-only transport is the reason. */
    fun proveNoTmp() {
        val sb = StringBuilder()
        for (p in listOf("/tmp", "/var/tmp", "/data/local/tmp")) {
            val probe = File(p, "ud_phase2_wprobe")
            val res = try {
                probe.delete()
                if (probe.mkdir()) { probe.delete(); "WRITABLE(mkdir ok!)" }
                else "denied(mkdir failed)"
            } catch (t: Throwable) { "denied(${t.javaClass.simpleName})" }
            sb.append("$p=$res ")
        }
        sb.append("javaTmp=${System.getProperty("java.io.tmpdir")}")
        say("TMP_PROBE $sb")
    }

    fun xvfbBin(ctx: Context): File = File(Phase2Data.rootfsDir(ctx), "usr/bin/Xvfb")

    /** Base env for every box64 launch (emulated libs + writable tmp/home). */
    fun baseEnv(ctx: Context): Map<String, String> {
        val r = Phase2Data.rootfsDir(ctx)
        return mapOf(
            "LD_LIBRARY_PATH" to Phase2Data.ldPaths(ctx),
            "BOX64_LD_LIBRARY_PATH" to Phase2Data.ldPaths(ctx),
            "BOX64_PREFER_EMULATED" to "1",
            // Android has no host glibc; box64's wrapped libc is backed by Bionic
            // plus the glibc-compat symbols compiled into libbox64 (glibcshim.c,
            // see fetch-box64.sh). The glibc family itself stays wrapped.
            "BOX64_LOG" to "1",
            "TMPDIR" to tmpDir(ctx).path,
            "UD_SHIM_TRACE" to "1",
            "HOME" to homeDir(ctx).path,
            "PATH" to "${r}/usr/bin:${r}/bin:/system/bin",
        )
    }

    /**
     * Starts the guest Xvfb via box64. Nondeterminism: the server forks a child
     * (guest dash -> xkbcomp) for the keymap compile while it is already
     * multithreaded; box64's re-exec of the fork child intermittently fails
     * (child exits 127 without any output, Pclose() sees it, server dies at
     * keyboard init). Measured on the phone 2026-10-17: 1/5 identical runs
     * survived that step, the rest died 530-877 ms in. So the start is RETRIED:
     * ready means "TCP connect succeeded AND the process stayed alive 2.5 s
     * after it" (the TCP transport is created before keyboard init, so a bare
     * connect is not proof of a usable server).
     */
    fun startXvfb(ctx: Context) {
        val bin = xvfbBin(ctx)
        if (!bin.isFile) { say("XVFB_FAIL missing $bin"); writeLog(ctx); return }
        killXvfb(false)
        // The guest Xvfb's lock dir is patched to the relative "tmp/" path (see
        // win86/patchxvfb.py), so the lock file is created inside our extracted
        // rootfs (app-writable) instead of /tmp, which the app uid cannot write.
        val rootfs = Phase2Data.rootfsDir(ctx)
        val tmpDir = File(rootfs, "tmp")
        if (!tmpDir.isDirectory) tmpDir.mkdirs()
        // The guest Xvfb uses cwd-relative ".usr/..." and ".var/..." paths (see
        // win86/patchxvfb.py); make sure the dot-symlinks and the xkm dir exist
        // even if the rootfs was re-extracted after our manual staging.
        try { if (!Files.exists(java.nio.file.Paths.get(rootfs.path, ".usr"))) Os.symlink("usr", File(rootfs, ".usr").path) } catch (_: Throwable) {}
        try { if (!Files.exists(java.nio.file.Paths.get(rootfs.path, ".var"))) Os.symlink("var", File(rootfs, ".var").path) } catch (_: Throwable) {}
        File(rootfs, "var/lib/xkb").mkdirs()
        // Clear stale X-server lock files from previous (crashed) launches so a
        // rerun never trips over "server already active" on an old pid record.
        tmpDir.listFiles()?.forEach { it.delete() }
        val args = listOf(
            Phase2Box64.libbox64(ctx).path, bin.path, ":0",
            "-screen", "0", "1280x720x24",
            // only the TCP transport (127.0.0.1:6000); Xtrans wants a transport
            // name here, not a hostname — "localhost" was rejected on the phone.
            "-nolisten", "unix",
            "-listen", "tcp",
            // Xvfb locks are created at <LOCK_DIR>/.tX<screen>-lock with LOCK_DIR
            // compiled to /tmp (Xorg os/utils.c). The app uid cannot write /tmp
            // (ProveNoTmp), so skip the lock file. Unix sockets are disabled
            // above, so no other /tmp access remains.
            "-nolock",
            "-noreset",
            // box64/bionic: InputThreadEnable .data patch (patchxvfb7.py) makes the
            // threaded-input facility inert, but the SmartScheduler also relies on
            // SIGALRM, and box64 cannot deliver SIG14 to this guest safely (the
            // handler slot resolved to unmapped 0x2000 -> SIGSEGV at boot, measured
            // 2026-10-17). -dumbSched is xorg's own switch to disable it
            // (os/utils.c sets InputThreadEnable=FALSE and SmartScheduleSignalEnable=FALSE).
            "-dumbSched",
        )
        val env = baseEnv(ctx).toMutableMap()
        val t0all = System.currentTimeMillis()
        var last: String = ""
        for (attempt in 1..6) {
            val t0 = System.currentTimeMillis()
            val p = ProcessBuilder(args).apply {
                environment().clear(); environment().putAll(env)
                directory(rootfs) // relative "tmp/" lock target lives here
            }.start()
            xvfb = p; xvfbPid = pidOf(p)
            say("XVFB pid=$xvfbPid attempt=$attempt")
            g3exe("XVFB", ctx, xvfbPid)
            Thread({ drain("XVFB_OUT", p.inputStream) }, "xvfb-out").start()
            Thread({ drain("XVFB_ERR", p.errorStream) }, "xvfb-err").start()
            // Gate 1: TCP connect (transport exists from InitOutput, which runs
            // BEFORE keyboard init - not yet proof of a usable server).
            var connectedAt = Long.MAX_VALUE
            while (System.currentTimeMillis() - t0 < 30_000) {
                if (!alive(p)) break
                try {
                    Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", 6000), 400) }
                    connectedAt = System.currentTimeMillis(); break
                } catch (_: Throwable) { Thread.sleep(120) }
            }
            // Gate 2: the death window (keyboard init / XKB child) is under
            // 1 s on this hardware; require the process to stay alive 2.5 s
            // past the connect.
            if (connectedAt < Long.MAX_VALUE) {
                var stable = true
                val until = connectedAt + 2_500
                while (System.currentTimeMillis() < until) {
                    if (!alive(p)) { stable = false; break }
                    Thread.sleep(150)
                }
                if (stable && alive(p)) {
                    last = "XVFB_READY waited_ms=${connectedAt - t0} attempt=$attempt"
                    say(last); say("XVFB_STABLE total_ms=${System.currentTimeMillis() - t0all}")
                    writeLog(ctx); return
                }
            }
            last = if (alive(p)) "XVFB_NO_STABILIZE attempt=$attempt"
                  else "XVFB_DEAD exit=${p.exitValue()} after ${System.currentTimeMillis() - t0} ms"
            say(last)
            p.destroy()
            xvfb = null; xvfbPid = -1
            writeLog(ctx)
            Thread.sleep(900) // let the kernel settle sockets/locks before retry
        }
        say("XVFB_GAVE_UP after 6 attempts")
        writeLog(ctx)
    }

    fun xvfbRunning(): Boolean {
        val p = xvfb ?: return false
        return try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }
    }

    fun ensureXvfb(ctx: Context) {
        if (!xvfbRunning()) startXvfb(ctx)
    }

    /** Debug helper: run one guest command from the rootfs (box64 chain), so
     *  guest tool behaviour can be probed from host intent extras.
     *  "direct:<relpath> <args...>" execs the guest binary directly from the
     *  app (no dash -c wrapper); anything else runs through guest sh -c. */
    fun runGuestShell(ctx: Context, cmd: String) {
        val r = Phase2Data.rootfsDir(ctx)
        try {
            val pb = if (cmd.startsWith("direct:")) {
                val rest = cmd.removePrefix("direct:")
                val rel = rest.substringBefore(' ')
                val argstr = rest.substringAfter(' ', "")
                val args = mutableListOf(
                    Phase2Box64.libbox64(ctx).path, File(r, rel).path)
                if (argstr.isNotEmpty()) args.addAll(argstr.split(' '))
                ProcessBuilder(args).apply {
                    environment().clear(); environment().putAll(baseEnv(ctx))
                    environment()["DISPLAY"] = "127.0.0.1:0"
                    directory(r)
                }
            } else ProcessBuilder(
                    Phase2Box64.libbox64(ctx).path, File(r, "bin/sh").path,
                    "-c", cmd,
                ).apply {
                    environment().clear(); environment().putAll(baseEnv(ctx))
                    environment()["DISPLAY"] = "127.0.0.1:0"
                    directory(r)
                }
            val pp = pb.start()
            say("RBIN pid=${pidOf(pp)} cmd=$cmd")
            drain("RBIN_OUT", pp.inputStream)
            drain("RBIN_ERR", pp.errorStream)
            say("RBIN_RC exit=${pp.waitFor()}")
        } catch (t: Throwable) {
            say("RBIN_FAIL $t")
        }
        writeLog(ctx)
    }

    fun killXvfb(logStop: Boolean = true) {
        xvfb?.let { it.destroy(); if (logStop) say("XVFB_STOP pid=$xvfbPid") }
        xvfb = null
    }

    fun drain(tag: String, s: InputStream) {
        val buf = ByteArray(8192)
        while (true) {
            val n = try { s.read(buf) } catch (_: Throwable) { return }
            if (n <= 0) return
            val line = String(buf, 0, n).replace('\n', ' ').trim()
            if (line.isNotBlank()) say("$tag $line")
        }
    }

    // ------------------------------------------------------- pure-Java X11 client

    // (offsets below per X11 protocol "Connection Setup": the 8-byte prelude
    // [status, reasonLen, major, minor, length-in-4B-words] is read first; the rest
    // begins with release-number.)
    private const val OP_GET_IMAGE = 73
    private const val OP_QUERY_EXTENSION = 98
    private const val OP_GET_KEYBOARD_MAPPING = 101

    class X11 private constructor(private val sock: Socket) {
        private val out: OutputStream = sock.getOutputStream()
        private val ins: InputStream = sock.getInputStream()
        var minKeycode = 8 ; private set
        var maxKeycode = 255 ; private set

        var root = 0 ; private set
        var width = 0 ; private set
        var height = 0 ; private set
        var rootDepth = 0 ; private set
        /** first free client resource id (from setup reply resource-id-base). */
        var idBase = 0 ; private set

        companion object {
            fun connect(host: String = "127.0.0.1", display: Int = 0): X11 {
                val s = Socket()
                s.connect(InetSocketAddress(host, 6000 + display), 5000)
                // unbounded reads made capturePattern hang silently once
                // (phone 2026-10-17); bound them so failures surface as
                // SocketTimeoutException in the log instead.
                s.soTimeout = 5000
                val x = X11(s)
                x.handshake()
                return x
            }
        }

        private fun put(b: Int) = out.write(b and 0xff)
        private fun putShort(v: Int) { put(v); put(v shr 8) }
        private fun putInt(v: Int) { put(v); put(v shr 8); put(v shr 16); put(v shr 24) }

        private fun readFully(n: Int): ByteArray {
            val b = ByteArray(n); var off = 0
            while (off < n) {
                val r = ins.read(b, off, n - off)
                if (r <= 0) throw IllegalStateException("X11 EOF want=$n off=$off")
                off += r
            }
            return b
        }

        private fun pad4(n: Int) = (n + 3) and (3.inv())

        private fun handshake() {
            // X11 Connection Setup prelude: order(0), pad(1), major(2-3),
            // minor(4-5), name-len(6), data-len(7), unused(8-11). Version
            // bytes live at 2-3; putting 11 at byte 6 made the server wait
            // forever for an 11-byte auth name (seen on phone 2026-10-17).
            val pre = ByteArray(12)
            pre[0] = 'l'.code.toByte()
            pre[2] = 11 // protocol-major-version = 11
            out.write(pre); out.flush()
            val head = readFully(8)
            val status = head[0].toInt() and 0xff
            require(status == 1) { "X11 setup rejected status=$status" }
            // reply head: status(0), reason-len(1), major(2-3), minor(4-5),
            // length-in-4B-words(6-7); length taken at 2-3 is the MAJOR
            // VERSION — it desynced the whole parse (phone 2026-10-17).
            val words = (head[6].toInt() and 0xff) or ((head[7].toInt() and 0xff) shl 8)
            val rest = readFully(words * 4)
            val numScreens = rest[20].toInt() and 0xff
            val numFormats = rest[21].toInt() and 0xff
            val vendorLen = (rest[16].toInt() and 0xff) or ((rest[17].toInt() and 0xff) shl 8)
            // fixed part is 32 bytes: ..23 image-byte-order, 24 bit-order,
            // 25 scan-unit, 26 min-keycode, 27 max-keycode, 28-31 unused
            minKeycode = rest[26].toInt() and 0xff
            maxKeycode = rest[27].toInt() and 0xff
            idBase = (rest[4].toInt() and 0xff) or ((rest[5].toInt() and 0xff) shl 8) or
                ((rest[6].toInt() and 0xff) shl 16) or ((rest[7].toInt() and 0xff) shl 24)
            var o = 32 + pad4(vendorLen) + numFormats * 8
            val bb = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN)
            root = bb.getInt(o)
            width = bb.getShort(o + 20).toInt() and 0xffff
            height = bb.getShort(o + 22).toInt() and 0xffff
            rootDepth = rest[o + 38].toInt() and 0xff
            val hex32 = rest.sliceArray(0 until 40).joinToString("") { "%02x".format(it) }
            say("X11 up o=$o base=0x${Integer.toHexString(java.lang.Integer.toUnsignedLong(bb.getInt(4)).toInt())} hex0..40=$hex32")
        }

        /** GetInputFocus (op 43) — cheap no-i-request sanity probe with a reply. */
        fun probeFocus() {
            put(43); put(0); putShort(1)
            out.flush()
            val rep = readFully(32)
            val hex = rep.joinToString("") { "%02x".format(it) }
            say("GetInputFocus type=${rep[0]} seq=${rep[2].toInt() or (rep[3].toInt() shl 8)} bytes=$hex")
        }

        fun getImage(): Bitmap {
            probeFocus()
            put(OP_GET_IMAGE); put(2); putShort(5)
            putInt(root); putShort(0); putShort(0); putShort(width); putShort(height)
            putInt(-1)
            out.flush()
            val rep = readFully(32)
            val ehex = rep.joinToString("") { "%02x".format(it) }
            require(rep[0].toInt() and 0xff == 1) {
                "GetImage failed type=${rep[0]} code=${rep[1]} bytes=$ehex"
            }
            val depth = rep[1].toInt() and 0xff
            val len = ByteBuffer.wrap(rep, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int * 4
            require(depth == 24 && len == width * height * 4) { "unexpected image depth=$depth len=$len" }
            val data = readFully(len)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val px = IntArray(width * height)
            for (i in px.indices) {
                px[i] = 0xFF000000.toInt() or
                    ((data[i * 4 + 2].toInt() and 0xff) shl 16) or // R
                    ((data[i * 4 + 1].toInt() and 0xff) shl 8) or  // G
                    (data[i * 4].toInt() and 0xff)                 // B
            }
            bmp.setPixels(px, 0, width, 0, 0, width, height)
            return bmp
        }

        // ---------------- XTEST ----------------
        var xtestOp = 0 ; private set
        fun initXtest() {
            val name = "XTEST".toByteArray()
            put(OP_QUERY_EXTENSION); put(0); putShort(2 + (pad4(name.size) / 4))
            putShort(name.size); out.write(name)
            repeat(pad4(name.size) - name.size) { put(0) }
            out.flush()
            val rep = readFully(32)
            val present = rep[8].toInt() and 0xff
            xtestOp = rep[9].toInt() and 0xff
            say("XTEST present=$present opcode=$xtestOp")
            // length field 0; remaining padding discarded implicitly by readFully(32)
            require(present == 1) { "XTEST extension missing" }
        }

        private var ksymsPerKc = 0
        private var kcKeysyms = emptyArray<IntArray>()

        fun initKeyboard() {
            val count = maxKeycode - minKeycode + 1
            put(OP_GET_KEYBOARD_MAPPING); put(0); putShort(2)
            put(minKeycode); put(count); putShort(0)
            out.flush()
            val rep = readFully(32)
            require(rep[0].toInt() and 0xff == 1) { "GetKeyboardMapping failed type=${rep[0]}" }
            ksymsPerKc = rep[1].toInt() and 0xff
            val len = ByteBuffer.wrap(rep, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int * 4
            val data = readFully(len)
            kcKeysyms = Array(count) { i ->
                IntArray(ksymsPerKc) { j ->
                    ByteBuffer.wrap(data, (i * ksymsPerKc + j) * 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).int
                }
            }
            say("KEYMAP kc=$minKeycode..$maxKeycode per=$ksymsPerKc")
        }

        fun keycodeFor(keys: Int): Pair<Int, Boolean>? {
            for (i in kcKeysyms.indices) {
                val ks = kcKeysyms[i]
                if (ks.size > 1 && ks[1] == keys) return (minKeycode + i) to true
                if (ks[0] == keys) return (minKeycode + i) to false
            }
            return null
        }

        private fun fakeInput(evtype: Int, detail: Int) {
            put(xtestOp); put(2); putShort(4) // minor 2 = FakeInput; 16 bytes total
            put(evtype); put(detail); put(0); put(0)
            repeat(8) { put(0) }
            out.flush()
        }

        var shiftCode = 50
        private fun findShift() {
            keycodeFor(0xFFE1 /* Shift_L */)?.let { shiftCode = it.first }
        }

        fun typeText(text: String) {
            findShift()
            for (ch in text) {
                val sym = when (ch) {
                    ' ' -> 0x20
                    in '!'..'~' -> ch.code
                    '\n' -> 0xFF0D
                    else -> 0
                }
                if (sym == 0) { say("TYPE skip '${ch}'"); continue }
                val kc = keycodeFor(sym)
                if (kc == null) { say("TYPE nokey '${ch}'"); continue }
                if (kc.second) fakeInput(2 /*Key Press*/, shiftCode)
                fakeInput(2, kc.first)
                fakeInput(3 /*Key Release*/, kc.first)
                if (kc.second) fakeInput(3, shiftCode)
            }
            say("TYPED ${text.length} chars")
        }

        fun close() { try { sock.close() } catch (_: Throwable) { } }

        /** Deterministic known pattern: 8 alternating green/black horizontal bars
         *  drawn straight onto the root window (raw CreateGC + FillRectangles). */
        fun drawBars() {
            // GC ids must come from THIS client's granted id range
            // (resource-id-base..base|mask); hardcoded ids outside it were
            // rejected with BadIDChoice (phone 2026-10-17, seq=1 CreateGC).
            val gcGreen = idBase
            val gcBlack = idBase + 1
            fun createGc(cid: Int, fg: Int) {
                // xproto CreateGC body order: [cid][drawable][value-mask][value];
                // drawable-first was read as the cid -> BadIDChoice naming root.
                put(55); put(0); putShort(5)          // hdr4 + cid4 + drawable4 + mask4 + val4 = 20B
                putInt(cid); putInt(root)
                putInt(0x4)                            // GCForeground
                putInt(fg)
            }
            createGc(gcGreen, 0x00FF00)
            createGc(gcBlack, 0x000000)
            for (i in 0 until 8) {
                val y = i * height / 8
                val h = ((i + 1) * height / 8) - y
                if (h <= 0) break
                // xproto PolyFillRectangle body: [drawable][gc][rects]; the GC is
                // a plain id, NOT the drawable, and reversed order made the
                // server look up the GC id as a window (BadDrawable, native
                // + phone 2026-10-17).
                put(70); put(3); putShort(5)          // hdr4 + drawable4 + gc4 + rect8 = 20B = 5 words
                putInt(root); putInt(if (i % 2 == 0) gcGreen else gcBlack)
                putShort(0); putShort(y)
                putShort(width); putShort(h)
            }
            out.flush()
        }
    }

    /** Capture our X server's screen to files/phase2/<name> (PNG, data only). */
    fun captureX11(ctx: Context, name: String): File? = try {
        val x = X11.connect()
        val bmp = x.getImage()
        x.close()
        val out = File(ctx.filesDir, "phase2/$name")
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        say("CAPTURE ${out.path} bytes=${out.length()} ${bmp.width}x${bmp.height}")
        writeLog(ctx)
        out
    } catch (t: Throwable) {
        say("CAPTURE_FAIL $name $t"); writeLog(ctx); null
    }

    /** Draw the known pattern and capture the X server's screen to
     *  files/phase2/<name> (PNG, data only). */
    fun capturePattern(ctx: Context, name: String): File? = try {
        Phase2X11.ensureXvfb(ctx)
        val x = X11.connect()
        x.drawBars()
        val bmp = x.getImage()
        x.close()
        val out = File(ctx.filesDir, "phase2/$name")
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val mid = bmp.getPixel(bmp.width / 2, bmp.height / 16)
        say("PATTERN_CENTER pixel=0x${Integer.toHexString(mid)} (expect 0xff00ff00)")
        say("CAPTURE ${out.path} bytes=${out.length()} ${bmp.width}x${bmp.height}")
        writeLog(ctx)
        out
    } catch (t: Throwable) {
        say("CAPTURE_FAIL $name $t"); writeLog(ctx); null
    }

    fun typeViaXtest(ctx: Context, text: String): Boolean = try {
        val x = X11.connect()
        x.initXtest()
        x.initKeyboard()
        x.typeText(text)
        x.close()
        true
    } catch (t: Throwable) {
        say("XTEST_FAIL $t"); writeLog(ctx); false
    }
}
