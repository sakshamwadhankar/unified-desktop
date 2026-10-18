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
/**
 * Per-member policy for [Phase2Data.readTar]: where a wanted member lands, and why an
 * unwanted one is dropped. Phase 2 passes none (every member is written as-is); Phase 3
 * (item 5, code-server) passes one that maps the deb's Termux prefix away, skips
 * native/ELF members and peeks the first bytes of every file (the ELF guard).
 */
internal interface TarPolicy {
    /** Destination path relative to the target dir, or null = this member is not wanted. */
    fun mapName(name: String): String?
    /** Reason to drop a regular file of [size] bytes, or null to keep it. */
    fun skipReason(name: String, size: Long): String? = null
    /** Reason to drop a symlink, or null to create it as-is. */
    fun skipLink(name: String, target: String): String? = null
    /** First bytes of an otherwise-kept file; false = drop it (reported by the policy). */
    fun keepBytes(name: String, head: ByteArray, size: Long): Boolean = true
}

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

    // The functional no-op libfontconfig.so.1 (B-022 fix; source + recipe in
    // android/win86/fontconfig-stub/). Wine's init_fontconfig() dlsyms 26 Fc*
    // symbols from this lib; the real Debian build cannot be loaded by box64 on
    // this phone (its dt-needed chain fails) and the fontconfig paths then recurse
    // into create_winstation failures that end in the explorer.exe storm of B-022.
    // The stub exports all 26 and answers "no config, no fonts" so wine takes its
    // normal no-fonts paths (docs/PHASE2_RESULT.md round 3b). Shipped as an APK
    // asset (data - only ever read by box64, never executed by Android).
    const val FONTCONFIG_STUB_SHA256 = "0e967100613908e3b5769cf2c0e089f6d7f09c4f4b0907b3ae28415c393d3619"

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

    private fun download(ctx: Context, url: String, sha: String, name: String): File =
        download(downloadsDir(ctx), url, sha, name)

    /**
     * Download [url] into [dir] as [name] with a cache + SHA-256 check. Phase 2 (win86)
     * and Phase 3 (code-server, item 5) share this; [log] keeps each phase's evidence in
     * its own log file (Phase 2 passes nothing, Phase 3 passes Phase3Native::say).
     */
    internal fun download(
        dir: File,
        url: String,
        sha: String,
        name: String,
        log: (String) -> Unit = { say(it) },
    ): File {
        dir.mkdirs()
        val out = File(dir, name)
        if (out.exists() && out.length() > 0) {
            val got = sha256(out)
            if (got == sha) { log("DL $name cached sha ok"); return out }
            log("DL $name cached sha MISMATCH -> redownload")
            out.delete()
        }
        val t = System.currentTimeMillis()
        URL(url).openStream().use { src ->
            FileOutputStream(out).use { dst -> src.copyTo(dst, 1 shl 16) }
        }
        val got = sha256(out)
        val ms = System.currentTimeMillis() - t
        log("DL $name bytes=${out.length()} ms=$ms sha=$got")
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

    /** The payload of a GNU 'L'/'K' member: the path/link, NUL-terminated. */
    private fun readStringPayload(input: InputStream, size: Long): String {
        if (size <= 0L || size > 65_536L) return ""
        val b = ByteArray(size.toInt())
        var got = 0
        while (got < b.size) {
            val n = input.read(b, got, b.size - got)
            if (n <= 0) break
            got += n
        }
        val end = b.indexOfFirst { it.toInt() == 0 }.let { if (it < 0) b.size else it }
        return String(b, 0, end, Charsets.UTF_8)
    }

    /**
     * Minimal tar reader for deb data.tar.{xz,gz} members and root-level tarballs.
     * With [policy] (Phase 3) each member goes through mapName/skipReason/skipLink and,
     * for kept files, a 4-byte head peek (keepBytes); without it every member is written.
     *
     * GNU long names matter here: the pinned code-server deb stores every path over 100
     * bytes as an @LongLink ('L') member followed by a header whose name field is the
     * *truncated* 100-byte prefix of the real path. Skipping 'L' silently writes the
     * truncated names (measured: .../configuration-editing/images/ came out as the file
     * "images" and killed the unpack with EISDIR). 'L'/'K' are honoured below, and a name
     * that fills the whole 100-byte field is logged as TAR_NAME full - the tell-tale of a
     * missed long name (docs/PHASE3_RESULT.md item 5).
     */
    internal fun readTar(input: InputStream, target: File, label: String, policy: TarPolicy? = null) {
        val buf = ByteArray(512)
        var hdrNo = 0
        var nameFull = 0
        var pendingName: String? = null
        var pendingLink: String? = null
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
            val nameField = String(buf, 0, 100)
            val rawName = nameField.trimEnd('\u0000', ' ')
            val prefix = String(buf, 345, 155).trimEnd('\u0000', ' ')
            val sizeStr = String(buf, 124, 12).trimEnd('\u0000', ' ')
            val size = try {
                if (sizeStr.isEmpty()) 0L else java.lang.Long.parseLong(sizeStr.trim(), 8)
            } catch (t: Throwable) {
                say("TAR_HDR_FAIL $label hdr#$hdrNo size=$sizeStr type=${typeChar(buf, 156)} hex=${hexOf(buf, 0, 48)} " +
                        "mathex=$t")
                throw IllegalStateException("bad tar size <$sizeStr>", t)
            }
            val type = buf[156].toInt().toChar()

            // GNU long name / long link: the payload is the real value for the NEXT member.
            if (type == 'L' || type == 'K') {
                val payload = readStringPayload(input, size)
                if (type == 'L') pendingName = payload else pendingLink = payload
                val pad = ((512 - (size % 512)) % 512).toInt()
                if (pad > 0) copyExactly(input, nullOut, pad.toLong())
                continue
            }

            if (pendingName == null && nameField.length == 100 && !nameField.contains('\u0000')) {
                nameFull++
                if (nameFull <= 3) {
                    say("TAR_NAME 100B name=$rawName (exactly 100 bytes, no @LongLink: legal - GNU tar" +
                            " only adds 'L' when the path does not fit; same shape as a missed long name) " +
                            "$label hdr#$hdrNo")
                }
            }
            var fullName = rawName
            if (prefix.isNotEmpty()) fullName = "${stripGnuMagic(prefix)}/${stripGnuMagic(rawName)}"
            pendingName?.let { fullName = it; pendingName = null }
            if (fullName.isEmpty()) continue
            val linkField = String(buf, 157, 100).trimEnd('\u0000', ' ')
            val link = pendingLink ?: linkField
            pendingLink = null
            val cleaned = San.name(fullName)
            // Phase 3 maps the deb's Termux prefix away; null = member not wanted.
            val dest = if (policy == null || cleaned.isEmpty()) cleaned
                       else policy.mapName(cleaned)?.let { San.name(it) }
            val f = if (dest == null || dest.isEmpty()) null else File(target, dest)
            val reason: String? =
                if (policy == null || f == null) null
                else if (type == '0' || type == '\u0000') policy.skipReason(dest!!, size)
                else if (type == '2') policy.skipLink(dest!!, link)
                else null

            when (type) {
                '0', '\u0000' -> {
                    if (f == null || reason != null) {
                        copyExactly(input, nullOut, size)
                    } else {
                        var head = ByteArray(0)
                        if (policy != null && size > 0L) {
                            head = ByteArray(minOf(4L, size).toInt())
                            var got = 0
                            while (got < head.size) {
                                val n = input.read(head, got, head.size - got)
                                if (n <= 0) break
                                got += n
                            }
                            if (got < head.size) head = head.copyOf(got)
                        }
                        if (policy != null && head.isNotEmpty() && !policy.keepBytes(dest!!, head, size)) {
                            copyExactly(input, nullOut, size - head.size)
                        } else {
                            f.parentFile?.mkdirs()
                            FileOutputStream(f).use { dst ->
                                if (head.isNotEmpty()) dst.write(head)
                                copyExactly(input, dst as java.io.OutputStream, size - head.size)
                            }
                        }
                    }
                }
                '5' -> if (f != null && reason == null) f.mkdirs()
                '1' -> { // hard link
                    if (f == null) {
                        if (policy == null) return
                    } else if (reason == null) {
                        val srcRel = if (policy == null) San.name(link)
                                     else policy.mapName(San.name(link))?.let { San.name(it) }
                        val src = if (srcRel == null) null else File(target, srcRel)
                        if (src != null && src.isFile) { f.parentFile?.mkdirs(); src.copyTo(f, overwrite = true) }
                        else say("TAR hardlink source missing $fullName -> $link")
                    }
                }
                '2' -> { // symlink
                    if (f == null) {
                        if (policy == null) return
                    } else if (reason == null) {
                        f.parentFile?.mkdirs()
                        if (!linkTargetInside(target, f, link)) {
                            say("TAR_LINK_ESCAPE $fullName -> $link (resolves outside the unpack root)")
                        } else {
                            try {
                                f.delete()
                                Files.createSymbolicLink(f.toPath(), java.nio.file.Paths.get(link))
                            } catch (t: Throwable) { say("SYMLINK FAIL $fullName -> $link: $t") }
                        }
                    } else {
                        say("TAR_LINK_SKIP $fullName reason=$reason target=$link")
                    }
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

    /**
     * Symlink targets from the archive must stay inside the unpack root. Absolute
     * targets are left to the policy (Phase 3 drops them as abs-link, Phase 2 keeps
     * them); a *relative* target is resolved against the link's own directory and must
     * not climb out of the tree. (Before this check the reader ran the target through
     * the traversal guard meant for member names, which silently dropped the 22
     * legitimate node_modules/.bin links of the code-server deb, all of the shape
     * "../pkg/bin.js".)
     */
    private fun linkTargetInside(root: File, linkFile: File, link: String): Boolean {
        val p = try {
            java.nio.file.Paths.get(link)
        } catch (_: Throwable) {
            return false
        }
        if (p.isAbsolute) return true
        val base = linkFile.parentFile?.toPath() ?: root.toPath()
        return try {
            base.resolve(p).normalize().startsWith(root.toPath().normalize())
        } catch (_: Throwable) {
            false
        }
    }

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
    internal fun unpackDeb(deb: File, target: File, policy: TarPolicy? = null) {
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
                    readTar(raw, target, "$deb:$entryName", policy)
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
        say("FONTCONFIG stub ${if (ensureFontconfigStub(ctx)) "ok" else "FAILED"}")
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

    /**
     * Install the fontconfig stub from the APK assets over
     * rootfs/usr/lib/x86_64-linux-gnu/libfontconfig.so.1, verifying the pinned digest.
     * Idempotent: a staged stub with the right sha is left alone; a symlink or the real
     * lib (which the tar reader created as symlink -> libfontconfig.so.1.12.0) is
     * deleted; a regular non-stub file is copied aside as <path>.udreal first so
     * rollback stays possible. Called from fetchRuntime and from Phase2Wine.boot, so a
     * fresh fetch or a later boot both heal the staging without any manual step
     * (B-019 pattern, item 8 wiring 2026-10-18).
     */
    fun ensureFontconfigStub(ctx: Context): Boolean {
        val rootfs = rootfsDir(ctx)
        val rel = "usr/lib/x86_64-linux-gnu/libfontconfig.so.1"
        return try {
            val bytes = ctx.assets.open("win86/libfontconfig.so.1").use { it.readBytes() }
            val got = sha256(bytes)
            if (got != FONTCONFIG_STUB_SHA256) {
                say("FCSTUB_FAIL asset sha=$got want=$FONTCONFIG_STUB_SHA256"); return false
            }
            val f = File(rootfs, rel)
            val isRegular = java.nio.file.Files.isRegularFile(
                f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS
            )
            val curSha = if (isRegular) try { sha256(f) } catch (_: Throwable) { null } else null
            if (fcStubIsEffective(isRegular, curSha)) { say("FCSTUB already staged sha=${got.take(16)}e"); return true }
            val aside = File(rootfs, "$rel.udreal")
            if (isRegular && !aside.exists()) {
                try { f.copyTo(aside, overwrite = false); say("FCSTUB real lib kept at $rel.udreal") }
                catch (t: Throwable) { say("FCSTUB_FAIL cannot keep real lib: $t"); return false }
            }
            if (!f.delete() && f.exists()) { say("FCSTUB_FAIL cannot replace $rel"); return false }
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            say("FCSTUB_INSTALL $rel wasRegular=$isRegular bytes=${bytes.size} sha=${got.take(16)}e")
            true
        } catch (t: Throwable) {
            say("FCSTUB_FAIL $t")
            false
        }
    }

    /** Pure decision (unit-tested): the staged lib is only the stub when it is a real
     *  file (never a symlink back to the Debian lib) whose digest matches the asset. */
    internal fun fcStubIsEffective(isRegularFile: Boolean, currentSha256: String?): Boolean =
        isRegularFile && currentSha256 == FONTCONFIG_STUB_SHA256

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

    /** The T3 download sequence in one place: used by the activity fetch "all" and by
     *  the harness broadcast `fetchall` (item 9 clean-install proof runs on broadcasts
     *  only), so both paths can never drift apart. */
    fun fetchAll(ctx: Context) {
        fetchRuntime(ctx)
        fetchBundle(ctx)
        fetchWine(ctx)
        fetchNpp(ctx)
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
