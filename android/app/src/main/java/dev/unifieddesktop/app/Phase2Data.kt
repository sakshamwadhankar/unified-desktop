package dev.unifieddesktop.app

import android.content.Context
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Phase 2 (T3): bring the x86-64 runtime (Debian libs, Wine, Box64 lib bundle) and the
 * apps (Notepad++) onto the phone as DATA under files/win86. Everything here is
 * downloaded from pinned URLs, SHA-256-checked, and unpacked with pure-Java readers.
 * Nothing in this tree is ever executed by Android; libbox64.so reads it as data.
 */
object Phase2Data {
    const val TAG = "Phase2"
    const val LOG_NAME = "t3.log"

    // Pinned pieces: asset digests verified by the uploader's sha256sums + local
    // verification at download time.
    //
    // Round 3 (D13 status note): the Wine pin is switchable. Each candidate is a
    // Kron4ek amd64 build with its own tarball, its own unpack dir and its own
    // WINEPREFIX (see Phase2Wine.prefixDir). SHA-256 sources: the GitHub release
    // asset digest for 11.x, the release's sha256sums.txt for 9.0/10.0 (both
    // verified locally on the laptop 2026-10-17). loaderRel is the entry that is
    // the 64-bit-only loader of that wine era (B-024: 11.x bin/wine dies in
    // map_shared_user_data with c0000018; 9.x/10.x use bin/wine64).
    data class WinePin(
        val id: String,
        val url: String,
        val sha256: String,
        val dir: String,
        val loaderRel: String,
    )

    val WINE_PINS: List<WinePin> = listOf(
        WinePin("11.19",
            "https://github.com/Kron4ek/Wine-Builds/releases/download/11.19/wine-11.19-amd64.tar.xz",
            "40068b3825887224d4b95364e3f49342d4bf7efac5486614b8bc4e7173bbef3e",
            "wine-11.19-amd64", "lib/wine/x86_64-unix/wine"),
        WinePin("9.0",
            "https://github.com/Kron4ek/Wine-Builds/releases/download/9.0/wine-9.0-amd64.tar.xz",
            "cf0c09d4346dc10bc92ab674936292cff47eeb71ca7604b8e6303b7bdb97e2f6",
            "wine-9.0-amd64", "bin/wine64"),
        WinePin("10.0",
            "https://github.com/Kron4ek/Wine-Builds/releases/download/10.0/wine-10.0-amd64.tar.xz",
            "a2b6ae3253736a13162c7c505f69b1c6076432c3ba64b54e1b923613ebdab698",
            "wine-10.0-amd64", "bin/wine64"),
        WinePin("11.0",
            "https://github.com/Kron4ek/Wine-Builds/releases/download/11.0/wine-11.0-amd64.tar.xz",
            "632f2c8e9150841c26d277000e76b82c425cd4564bfb050780705b9d37b2567f",
            "wine-11.0-amd64", "lib/wine/x86_64-unix/wine"),
    )
    const val DEFAULT_WINE_PIN = "11.19"

    /** Pure pin selection (unit-tested): the harness `pin:<id>` file, the
     *  WINE_PIN env.txt knob, then the default; unknown ids fall back. */
    fun pickPin(want: String?): WinePin {
        val id = want?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_WINE_PIN
        return WINE_PINS.firstOrNull { it.id == id } ?: run {
            say("WINE_PIN unknown [$id] known=${WINE_PINS.joinToString(",") { it.id }} -> default $DEFAULT_WINE_PIN")
            WINE_PINS.first { it.id == DEFAULT_WINE_PIN }
        }
    }

    /** The active Wine pin: files/phase2/pin.txt (harness action `pin:<id>`)
     *  wins, then the WINE_PIN line of files/phase2/env.txt, then the default. */
    fun winePin(ctx: Context): WinePin {
        val fromFile = try {
            File(ctx.filesDir, "phase2/pin.txt").takeIf { it.isFile }?.readText()?.trim()
        } catch (_: Throwable) { null }
        val fromEnv = try {
            File(ctx.filesDir, "phase2/env.txt").takeIf { it.isFile }?.readLines()
                ?.map { it.trim() }?.firstOrNull { it.startsWith("WINE_PIN=") }
                ?.substringAfter('=')?.trim()
        } catch (_: Throwable) { null }
        return pickPin(fromFile ?: fromEnv)
    }

    const val BUNDLE_URL = "https://github.com/ptitSeb/box64/releases/download/v0.4.4/box64-bundle-x86-libs-v0.4.4.tar.gz"
    const val BUNDLE_SHA256 = "1d4a8787a8a92267c1be673ec194c69e3bb33d14c800575d94131084142fcff0" // verified locally at download
    const val NPP_URL = "https://github.com/notepad-plus-plus/notepad-plus-plus/releases/download/v8.9.8.1/npp.8.9.8.1.portable.x64.zip"
    const val NPP_SHA256 = "beddf5548e75c97930d1575ba1c7d113e6e2b33d0a200310e734ca08e225f414"

    // Our guest bin/sh: see android/win86/guestsh/udsh.c (built by
    // android/win86/build-guestsh.sh). Debian dash dies at its own startup
    // under box64 on this phone (B-016: its INIT runs setvareq("OPTIND=1") ->
    // getoptsreset -> number("1") -> "sh: 0: Illegal number: 1", exit 2), so
    // the shell Xorg's Popen fork+execl("bin/sh") uses for the keymap compile
    // is udsh instead. Shipped as this APK asset (data - never exec'd by
    // Android, only read by box64) and installed over rootfs bin/sh/usr/bin/sh.
    const val UDSH_SHA256 = "35a6fc02421f4cc863caa26396ecac293c7c4dceee79f96706c7e2bb0f3be767"

    private val log = StringBuilder()
    fun say(s: String) { android.util.Log.i(TAG, s); log.append(s).append('\n') }
    fun logText(): String = log.toString()

    fun rootDir(ctx: Context): File = File(ctx.filesDir, "win86")
    fun rootfsDir(ctx: Context): File = File(rootDir(ctx), "rootfs")
    fun downloadsDir(ctx: Context): File = File(rootDir(ctx), "downloads")

    fun ldPaths(ctx: Context): String {
        val r = rootfsDir(ctx)
        return listOf(
            "${r.path}/usr/lib/x86_64-linux-gnu",
            "${r.path}/lib/x86_64-linux-gnu",
            "${r.path}/usr/lib/box64-x86_64-linux-gnu",
            "${r.path}/usr/lib",
            "${r.path}/lib",
        ).joinToString(":")
    }

    private fun sha256(f: File): String = sha256(f.readBytes())

    private fun sha256(b: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256").digest(b)
        return md.joinToString("") { "%02x".format(it) }
    }

    private fun download(ctx: Context, url: String, sha: String, name: String): File {
        val dir = downloadsDir(ctx); dir.mkdirs()
        val out = File(dir, name)
        if (out.exists() && out.length() > 0) {
            val got = sha256(out)
            if (got == sha) { say("DL $name cached sha ok"); return out }
            say("DL $name cached sha MISMATCH -> redownload")
            out.delete()
        }
        val t = System.currentTimeMillis()
        URL(url).openStream().use { src ->
            FileOutputStream(out).use { dst -> src.copyTo(dst, 1 shl 16) }
        }
        val got = sha256(out)
        val ms = System.currentTimeMillis() - t
        say("DL $name bytes=${out.length()} ms=$ms sha=$got")
        check(got == sha) { "sha mismatch for $url: got $got want $sha" }
        return out
    }

    // ---------- minimal tar reader (deb data.tar.{xz,gz} + root-level tarballs) ---

    private object San {
        /** Returns "" for entries to skip (./ root dirs); "" is never written out. */
        fun name(raw: String): String {
            val n = raw.removePrefix("./").trimEnd('/')
            if (n.isEmpty()) return ""
            require(!n.startsWith("/") && !n.contains("..")) { "bad tar name $raw" }
            return n
        }
    }

    private class TarEntry(val name: String, val size: Long, val link: String?, val type: Char)

    private fun readTar(input: InputStream, target: File, label: String) {
        val buf = ByteArray(512)
        var hdrNo = 0
        while (true) {
            var off = 0
            while (off < 512) {
                val n = input.read(buf, off, 512 - off)
                if (n < 0) { if (off == 0) return else return }
                off += n
            }
            if (buf[0].toInt() == 0) { // end-of-archive blank block
                var zeros = true
                for (b in buf) if (b.toInt() != 0) { zeros = false; break }
                if (zeros) return
            }
            hdrNo++
            val rawName = String(buf, 0, 100).trimEnd('\u0000', ' ')
            val prefix = String(buf, 345, 155).trimEnd('\u0000', ' ')
            val fullName = if (prefix.isNotEmpty()) "${stripGnuMagic(prefix)}/${stripGnuMagic(rawName)}" else stripGnuMagic(rawName)
            if (fullName.isEmpty()) continue
            val sizeStr = String(buf, 124, 12).trimEnd('\u0000', ' ')
            val size = try {
                if (sizeStr.isEmpty()) 0L else java.lang.Long.parseLong(sizeStr.trim(), 8)
            } catch (t: Throwable) {
                say("TAR_HDR_FAIL $label hdr#$hdrNo size=$sizeStr type=${typeChar(buf, 156)} hex=${hexOf(buf, 0, 48)} " +
                        "mathex=$t")
                throw IllegalStateException("bad tar size <$sizeStr>", t)
            }
            val type = buf[156].toInt().toChar()
            val link = String(buf, 157, 100).trimEnd('\u0000', ' ')
            val entry = TarEntry(fullName, size, link, type)

            val cleaned = San.name(fullName)
            val f = if (cleaned.isEmpty()) null else File(target, cleaned)
            when (type) {
                '0', '\u0000' -> {
                    f?.parentFile?.mkdirs()
                    if (f == null) copyExactly(input, nullOut, size)
                    else FileOutputStream(f).use { dst -> copyExactly(input, dst as java.io.OutputStream, size) }
                }
                '5' -> f?.mkdirs()
                '1' -> { // hard link
                    if (f == null) return
                    val src = File(target, San.name(link))
                    if (src.isFile) { f.parentFile?.mkdirs(); src.copyTo(f, overwrite = true) }
                    else say("TAR hardlink source missing $fullName -> $link")
                }
                '2' -> { // symlink
                    if (f == null) return
                    f.parentFile?.mkdirs()
                    try {
                        f.delete()
                        Files.createSymbolicLink(f.toPath(), java.nio.file.Paths.get(San.name(link)))
                    } catch (t: Throwable) { say("SYMLINK FAIL $fullName -> $link: $t") }
                }
                else -> { // unknown types: skip
                    say("TAR_SKIP type=$type name=$fullName")
                    copyExactly(input, nullOut, size)
                }
            }
            val pad = ((512 - (size % 512)) % 512).toInt()
            if (pad > 0) copyExactly(input, nullOut, pad.toLong())
        }
    }

    private fun stripGnuMagic(raw: String): String = raw

    private fun hexOf(b: ByteArray, off: Int, len: Int): String =
        b.drop(off).take(len).joinToString("") { "%02x".format(it) }

    private fun typeChar(b: ByteArray, off: Int): Char = b[off].toInt().toChar()

    private fun copyExactly(input: InputStream, dst: java.io.OutputStream, size: Long) {
        var left = size
        val b = ByteArray(65536)
        while (left > 0) {
            val n = input.read(b, 0, minOf(left, b.size.toLong()).toInt())
            if (n <= 0) { say("TAR short read want=$left"); break }
            dst.write(b, 0, n)
            left -= n
        }
    }

    private val nullOut: java.io.OutputStream = object : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    /** Unpack a Debian .deb (ar archive) into rootfs, only data.tar members. */
    fun unpackDeb(deb: File, target: File) {
        FileInputStream(deb).use { ff ->
            // readFully: FileInputStream may legally return short reads
            fun rd(b: ByteArray) {
                var o = 0
                while (o < b.size) {
                    val n = ff.read(b, o, b.size - o)
                    if (n <= 0) return
                    o += n
                }
            }
            val magic = ByteArray(8)
            rd(magic)
            check(magic.contentEquals("!<arch>\n".toByteArray())) { "not an ar: $deb" }
            val hdr = ByteArray(60)
            var member = 0
            while (member < 64) {
                member++
                val got = ff.read(hdr) // a 60-byte read from a local file; EOF -> -1
                if (got != 60) return   // end of archive (data.tar is the last member)
                val name0 = String(hdr, 0, 16).trim()
                val sizeStr = String(hdr, 48, 10).trim()
                val size = try { sizeStr.toLong() } catch (t: Throwable) {
                    say("AR_HDR_FAIL deb=$deb member#$member name0=$name0 hex=${hexOf(hdr, 0, 60)}")
                    throw IllegalStateException("bad ar size <$sizeStr> in $deb", t)
                }
                val entryName = name0.removeSuffix("/")
                say("AR member#$member $entryName size=$size")
                if (entryName.startsWith("data.tar")) {
                    val raw = if (entryName.endsWith(".xz")) XZInputStream(ff)
                        else if (entryName.endsWith(".gz")) GZIPInputStream(ff)
                        else throw IllegalStateException("unsupported data.tar: $entryName in $deb")
                    readTar(raw, target, "$deb:$entryName")
                    return // data.tar is the last member of a .deb
                }
                copyExactly(ff, nullOut, size)
                // GNU ar pads members to even offsets with a '\n' byte.
                if (size % 2 == 1L) ff.read(ByteArray(1))
            }
            say("AR members>64 in $deb")
        }
    }

    // ---------- steps -------------------------------------------------------------

    fun fetchRuntime(ctx: Context) {
        val t0 = System.currentTimeMillis()
        val lock = ctx.assets.open("win86/runtime-libs.lock").bufferedReader().readLines()
            .filter { it.startsWith("https://") }
        say("LOCK entries=${lock.size}")
        val rootfs = rootfsDir(ctx); rootfs.mkdirs()
        var totalBytes = 0L
        var ok = 0
        for (line in lock) {
            val parts = line.split(" ")
            require(parts.size >= 3) { "bad lock line $line" }
            val url = parts[0]; val sha = parts[2]
            val name = url.substringAfterLast('/')
            val deb = download(ctx, url, sha, name)
            totalBytes += deb.length()
            say("UNPACK $name bytes=${deb.length()}")
            unpackDeb(deb, rootfs)
            ok++
        }
        val t1 = System.currentTimeMillis()
        val soCount = rootfs.walkTopDown().filter { it.isFile && it.name.contains(".so") }.count()
        // Debian 12 is usr-merged: the dash deb's bin/sh is a symlink to
        // bin/dash - which is exactly what Xorg's fork+execl("bin/sh") runs for
        // the keymap compile. Debian dash cannot run under box64 here (B-016),
        // so the guest bin/sh (and usr/bin/sh) is our udsh instead (asset
        // win86/udsh, built from android/win86/guestsh/udsh.c by
        // build-guestsh.sh). Real dash stays at bin/dash; no +x is ever set -
        // box64 reads everything as data.
        say("SHELL bin/sh+usr/bin/sh = udsh ${if (ensureGuestSh(ctx)) "ok" else "FAILED"}")
        say("ROOTFS pkgs=$ok debBytes=$totalBytes soFiles=$soCount ms=${t1 - t0}")
        writeLog(ctx)
    }

    /**
     * Install the guest bin/sh (udsh) from the APK assets over rootfs bin/sh
     * and usr/bin/sh, verifying the pinned digest. Idempotent; a symlink (the
     * tar reader's bin/sh -> bin/dash) is deleted before writing so the write
     * can never follow it into dash. Called from fetchRuntime and from
     * Phase2X11.startXvfb, so installs provisioned before udsh existed heal on
     * the next run: nothing depends on a manual staging step (B-019).
     */
    fun ensureGuestSh(ctx: Context): Boolean {
        val rootfs = rootfsDir(ctx)
        return try {
            val bytes = ctx.assets.open("win86/udsh").use { it.readBytes() }
            val got = sha256(bytes)
            if (got != UDSH_SHA256) { say("UDSH_FAIL asset sha=$got want=$UDSH_SHA256"); return false }
            var replaced = 0
            for (rel in listOf("bin/sh", "usr/bin/sh")) {
                val f = File(rootfs, rel)
                val already = java.nio.file.Files.isRegularFile(
                    f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS
                ) && sha256(f) == UDSH_SHA256
                if (!already) {
                    f.parentFile?.mkdirs()
                    if (!f.delete() && f.exists()) { say("UDSH_FAIL cannot replace $rel"); return false }
                    f.writeBytes(bytes)
                    replaced++
                }
            }
            say("UDSH_INSTALL replaced=$replaced sha=${UDSH_SHA256.take(16)}e")
            true
        } catch (t: Throwable) {
            say("UDSH_FAIL $t")
            false
        }
    }

    fun fetchWine(ctx: Context) {
        val t0 = System.currentTimeMillis()
        val pin = winePin(ctx)   // round 3: switchable pin (default stays 11.19)
        val tar = download(ctx, pin.url, pin.sha256, pin.url.substringAfterLast('/'))
        val wineRoot = File(rootDir(ctx), "wine"); wineRoot.mkdirs()
        // every pin unpacks to its own top-level dir, so candidates coexist
        // (unpack output goes to <wineRoot>/<pin.dir>)
        val target = File(wineRoot, pin.dir)
        if (target.isDirectory && File(target, "bin/wineserver").isFile) {
            say("WINE pin=${pin.id} already-unpacked dir=${pin.dir}")
        } else {
            XZInputStream(BufferedInputStream(FileInputStream(tar))).use { readTar(it, wineRoot, "wine.tar") }
        }
        val bin = File(target, pin.loaderRel)
        val t1 = System.currentTimeMillis()
        val size = target.walkBottomUp().fold(0L) { a, f -> a + if (f.isFile) f.length() else 0 }
        say("WINE pin=${pin.id} unpacked ms=${t1 - t0} bin=${bin.exists()} bytes=$size")
        writeLog(ctx)
    }

    fun fetchBundle(ctx: Context) {
        val t0 = System.currentTimeMillis()
        val gztar = download(ctx, BUNDLE_URL, BUNDLE_SHA256, "bundle.tar.gz")
        val rootfs = rootfsDir(ctx); rootfs.mkdirs()
        GZIPInputStream(BufferedInputStream(FileInputStream(gztar))).use { readTar(it, rootfs, "bundle.tar.gz") }
        val d = File(rootfs, "usr/lib/box64-x86_64-linux-gnu")
        val count = if (d.isDirectory) d.walkTopDown().filter { it.isFile }.count() else 0
        val t1 = System.currentTimeMillis()
        say("BUNDLE unpacked ms=${t1 - t0} bundleFiles=$count")
        writeLog(ctx)
    }

    fun fetchNpp(ctx: Context) {
        val t0 = System.currentTimeMillis()
        val zip = download(ctx, NPP_URL, NPP_SHA256, "npp.zip")
        val app = File(rootDir(ctx), "apps/npp"); app.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val f = File(app, San.name(e.name))
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { zin.copyTo(it) }
                }
                zin.closeEntry(); e = zin.nextEntry
            }
        }
        val exe = File(app, "notepad++.exe")
        val t1 = System.currentTimeMillis()
        val size = app.walkBottomUp().fold(0L) { a, f -> a + if (f.isFile) f.length() else 0 }
        say("NPP unpacked ms=${t1 - t0} exe=${exe.exists()} bytes=$size")
        writeLog(ctx)
    }

    fun writeLog(ctx: Context) {
        try {
            val dir = rootDir(ctx); dir.mkdirs()
            FileOutputStream(File(dir, LOG_NAME)).use { out ->
                out.write(logText().toByteArray(Charsets.UTF_8))
            }
        } catch (_: Throwable) { }
    }
}
