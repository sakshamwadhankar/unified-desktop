package dev.unifieddesktop.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * Item 5: code-server as DATA (D6/D13/D14).
 *
 * The pinned Termux/TUR `code-server_4.141.0_<abi>.deb` is downloaded by the app,
 * SHA-256-checked and unpacked into `files/phase3/code-server`: **JavaScript and data
 * only**. Everything native is either shipped in the APK as `lib*.so`
 * (Phase3Native.NATIVE_MODULES -> nativeLibraryDir) or dropped.
 *
 * What this file owns:
 *  - the per-ABI pins (URL + SHA-256, sizes measured at pin time),
 *  - the skip filter [skipReason] (pure, unit-tested against real deb paths),
 *  - the deb-prefix mapping [mapDebName] (the deb stores the tree under
 *    `data/data/com.termux/files/usr/lib/code-server/`),
 *  - the ELF guard [isElfHead]: a belt-and-braces rule on top of the filters - no file
 *    whose first bytes are `\x7fELF` is ever written, whatever the name says,
 *  - [TarPolicy] plumbing into Phase2Data.readTar (one tar reader, no copy),
 *  - [linkNativeModules]: the mapped native modules are re-created as **symlinks** to
 *    the APK's `lib*.so` in nativeLibraryDir. Only the *path* exists in files/; the
 *    bytes live in the APK-installed library dir, which is what Android allows to be
 *    dlopen'ed/executed. Node's module loader (node-gyp-build, `bindings`, node-pty's
 *    utils.js) resolves the addon by *looking the file up on disk* first, so the file
 *    must exist; a symlink satisfies that without a byte of native code being written,
 *    downloaded or updated (Play rule; ARCHITECTURE C1),
 *  - [verify]: on-device proof after unpack (file/byte counts, ELF count = 0, links,
 *    entry point present) and a manifest of everything that was skipped.
 */
object Phase3Data {

    /** One pinned code-server deb. Bytes are the deb's own size, measured 2026-10-18. */
    data class CsPin(val abi: String, val url: String, val sha256: String, val file: String, val bytes: Long)

    const val URL_X86_64 = "https://tur.kcubeterm.com/pool/tur/code-server/code-server_4.141.0_x86_64.deb"
    const val SHA_X86_64 = "90cbdcac6f0c45980d6b964a3d3f4ff9079353e4064389d3343ba4afc27b28c0"
    const val URL_AARCH64 = "https://tur.kcubeterm.com/pool/tur/code-server/code-server_4.141.0_aarch64.deb"
    const val SHA_AARCH64 = "eabd4122740486b2d1263f53be8d3770352bb85a6ff95f1aa0baf5303309d0c5"

    val PINS: List<CsPin> = listOf(
        CsPin("x86_64", URL_X86_64, SHA_X86_64, "code-server_4.141.0_x86_64.deb", 117_273_956L),
        CsPin("aarch64", URL_AARCH64, SHA_AARCH64, "code-server_4.141.0_aarch64.deb", 117_176_796L),
    )

    /** Pure ABI -> pin selection (unit-tested). Unknown ABI = null = stop, never guess. */
    fun pinFor(abi: String?): CsPin? = when (abi?.trim()?.lowercase()) {
        "x86_64", "x64", "x86-64" -> PINS[0]
        "arm64-v8a", "arm64", "aarch64" -> PINS[1]
        else -> null
    }

    /** The device's pin: the first supported ABI we have a deb for. */
    fun pinForDevice(): CsPin? {
        for (abi in Build.SUPPORTED_ABIS) pinFor(abi)?.let { return it }
        return null
    }

    /** The deb stores every member under this prefix (measured in the pinned deb). */
    const val DEB_PREFIX = "data/data/com.termux/files/usr/lib/code-server/"

    /** Pure deb-member -> path relative to our code-server root (unit-tested). */
    fun mapDebName(name: String): String? {
        val n = name.removePrefix("./")
        if (!n.startsWith(DEB_PREFIX)) return null
        val rest = n.removePrefix(DEB_PREFIX)
        return rest.ifEmpty { null }
    }

    /**
     * The skip filter, in one place (unit-tested against paths taken from the pinned
     * deb). First match wins, so the named rules keep their attribution in the log even
     * when a generic rule (.node/.so/.o) would also match.
     */
    val SKIP_PREFIXES: List<Pair<String, String>> = listOf(
        // Native/ELF payloads the prompt names: they can never run here (they are x64/
        // arm64 Linux code for Termux's own node, not our APK libs) and shipping *any*
        // downloaded native code is what D6/C1 forbid.
        "lib/vscode/node_modules/@github/copilot-sdk-linux-x64/" to "copilot-sdk",
        "lib/vscode/node_modules/@microsoft/mxc-sdk/" to "mxc-sdk",
        "lib/vscode/node_modules/@koromix/koffi-linux-x64/" to "koffi",
        "lib/vscode/node_modules/@parcel/watcher-linux-x64-glibc/" to "parcel-watcher-bin",
        "lib/vscode/node_modules/@parcel/watcher-linux-x64-musl/" to "parcel-watcher-bin",
        "lib/vscode/node_modules/@vscode/sandbox-runtime/vendor/seccomp/" to "seccomp",
        "lib/vscode/node_modules/@vscode/sandbox-runtime/vendor/seccomp-src/" to "seccomp",
        // MSAL native runtime: the extension's own JS (dist/extension.js) is kept.
        "lib/vscode/extensions/microsoft-authentication/dist/libmsalruntime.so" to "msal",
        "lib/vscode/extensions/microsoft-authentication/dist/msal-node-runtime.node" to "msal",
        // argon2 keeps its source, licence and JS; only the prebuilt binaries (every
        // platform) go. build/Release/argon2.node is a *symlink* to the APK lib.
        "node_modules/argon2/prebuilds/" to "argon2-prebuild",
    )

    /** Pure skip decision (unit-tested): reason tag, or null = keep the member. */
    fun skipReason(relPath: String): String? {
        val p = relPath.replace('\\', '/').removePrefix("./")
        for ((prefix, tag) in SKIP_PREFIXES) if (p.startsWith(prefix)) return tag
        // node-gyp build intermediates first: their *.node names are outputs of a build,
        // not modules anyone loads (obj.target/.deps are the gyp work directories).
        if (p.contains("/build/Release/obj.target/") || p.contains("/build/Release/.deps/")) return "build-obj"
        // Every native addon comes from the APK (Phase3Native.NATIVE_MODULES); the
        // deb's copies are Linux/Windows binaries for other people's node builds.
        if (p.endsWith(".node")) return "native-module"
        if (p.endsWith(".so")) return "elf-lib"
        if (p.endsWith(".o") || p.endsWith(".a") || p.endsWith(".ar-file-list") || p.endsWith(".stamp")) return "obj"
        return null
    }

    /** The ELF guard: no member whose bytes start like this is ever written. */
    fun isElfHead(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()

    /**
     * Value of a **top-level** string key in a JSON file, by scanning the text (pure,
     * unit-tested). VS Code's product.json is JSON5-ish and repeats "version" in nested
     * objects ("1.1.3" appears before the real one), so a plain regex would lie.
     */
    fun topLevelString(text: String, key: String): String? {
        var depth = 0
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> {
                    val end = endOfString(text, i)
                    if (depth == 1 && text.substring(i + 1, end - 1) == key) {
                        var j = end
                        while (j < text.length && text[j].isWhitespace()) j++
                        if (j < text.length && text[j] == ':') {
                            j++
                            while (j < text.length && text[j].isWhitespace()) j++
                            if (j < text.length && text[j] == '"') {
                                val ve = endOfString(text, j)
                                return text.substring(j + 1, ve - 1)
                            }
                        }
                    }
                    i = end
                }
                '{', '[' -> { depth++; i++ }
                '}', ']' -> { depth--; i++ }
                else -> i++
            }
        }
        return null
    }

    /** Index just past the closing quote of the string that starts at [start]. */
    private fun endOfString(s: String, start: Int): Int {
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            if (c == '"') return i + 1
            i++
        }
        return s.length
    }

    /** A symlink whose target is absolute points outside our sandbox (Termux paths). */
    fun skipLink(absTarget: Boolean): String? = if (absTarget) "abs-link" else null

    /** code-server tree inside files/ (the tar lands here; the marker file guards re-runs). */
    fun root(ctx: Context): File = File(Phase3Native.dir(ctx), "code-server")
    fun downloads(ctx: Context): File = File(Phase3Native.dir(ctx), "downloads")
    fun marker(ctx: Context): File = File(root(ctx), ".ud-unpack.txt")
    fun manifest(ctx: Context): File = File(Phase3Native.dir(ctx), "code-server.skipped.txt")

    private fun say(s: String) { Phase3Native.say(s) }

    /**
     * The unpack policy: maps the deb prefix away, applies [skipReason], drops absolute
     * symlinks, counts everything it drops and applies [isElfHead] to every kept file.
     */
    internal class CsPolicy : TarPolicy {
        val tally = LinkedHashMap<String, LongArray>()          // reason -> [files, bytes]
        val manifest = StringBuilder()
        var linksCreated = 0
            private set
        var linksSkipped = 0
            private set
        var elfGuardDropped = 0
            private set
        var elfGuardBytes = 0L
            private set

        private fun bump(reason: String, bytes: Long) {
            val a = tally.getOrPut(reason) { LongArray(2) }
            a[0]++; a[1] += bytes
        }

        override fun mapName(name: String): String? = mapDebName(name)

        override fun skipReason(name: String, size: Long): String? {
            val r = Phase3Data.skipReason(name)
            if (r != null) {
                bump(r, size)
                manifest.append(r).append('\t').append(name).append('\t').append(size).append('\n')
            }
            return r
        }

        override fun skipLink(name: String, target: String): String? {
            val r = Phase3Data.skipLink(target.startsWith("/"))
            if (r != null) {
                linksSkipped++
                bump(r, 0)
                manifest.append(r).append('\t').append(name).append("\t-> ").append(target).append('\n')
            }
            return r
        }

        override fun keepBytes(name: String, head: ByteArray, size: Long): Boolean {
            if (!isElfHead(head)) return true
            elfGuardDropped++; elfGuardBytes += size
            bump("elf-guard", size)
            manifest.append("elf-guard\t").append(name).append('\t').append(size).append('\n')
            return false
        }

        fun summary(): String = tally.entries.joinToString(" ") { (r, a) -> "$r=${a[0]}f/${a[1]}b" }
    }

    // -------------------------------------------------------------- the native links --

    /**
     * The deb paths of the native modules we load from the APK, in the order the tables
     * in Phase3Native define them: code-server's lib/vscode tree first, then code-server's
     * own node_modules (argon2). Derived, never duplicated (unit-tested).
     */
    fun nativeLinks(): Map<String, String> {
        val links = LinkedHashMap<String, String>()
        for ((rel, lib) in Phase3Native.NATIVE_MODULES) links["lib/vscode/$rel"] = lib
        for ((rel, lib) in Phase3Native.NATIVE_MODULES_CODER) links[rel] = lib
        return links
    }

    /**
     * Make every mapped path a symlink to `<nativeLibraryDir>/<lib>` (creating parents).
     * Idempotent: an already-correct link is left alone; a wrong one (stale
     * nativeLibraryDir after a reinstall) is replaced; a real file there is replaced
     * only after being kept as `<path>.udkeep` for inspection. Nothing is copied.
     */
    fun linkNativeModules(ctx: Context): Pair<Int, Int> {
        val libDir = ctx.applicationInfo.nativeLibraryDir
        var created = 0; var kept = 0
        for ((rel, lib) in nativeLinks()) {
            val apkLib = File(libDir, lib)
            val f = File(root(ctx), rel)
            if (!apkLib.isFile) { say("CS_LINK FAIL apk lib missing ${apkLib.absolutePath}"); continue }
            val isLink = Files.isSymbolicLink(f.toPath())
            val cur = try { if (isLink) Files.readSymbolicLink(f.toPath()).toString() else null } catch (_: Throwable) { null }
            if (cur == apkLib.absolutePath) { kept++; continue }
            try {
                if (Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    f.copyTo(File(f.absolutePath + ".udkeep"), overwrite = true)
                    say("CS_LINK kept real file at $rel as .udkeep")
                }
                f.delete()
                f.parentFile?.mkdirs()
                Files.createSymbolicLink(f.toPath(), Paths.get(apkLib.absolutePath))
                created++
            } catch (t: Throwable) {
                say("CS_LINK FAIL $rel -> ${apkLib.absolutePath}: $t")
            }
        }
        say("CS_LINK mapped=${nativeLinks().size} created=$created already=$kept libDir=$libDir")
        return created to kept
    }

    // ------------------------------------------------------------------- unpack/verify --

    private fun deleteTree(f: File): Int {
        if (!f.exists()) return 0
        var n = 0
        val kids = f.listFiles()
        if (kids != null) for (k in kids) n += deleteTree(k)
        if (f.delete()) n++
        return n
    }

    /**
     * Download + unpack (once per pin) + link + verify. The item-5 entry point.
     *
     * Serialized: two harness broadcasts (or a broadcast plus the activity) must never
     * race on the same deb/cache file - the first run of this code did, and the loser's
     * stray write to a deleted file produced an unreadable cache (see docs/PHASE3_RESULT.md
     * item 5).
     */
    @Synchronized
    fun fetch(ctx: Context, force: Boolean = false) {
        val t0 = System.currentTimeMillis()
        val pin = pinForDevice()
        if (pin == null) {
            say("CS FAIL no deb pin for abilist=${Build.SUPPORTED_ABIS.joinToString(",")}")
            return Phase3Native.writeLog(ctx)
        }
        say("CS_PIN abi=${pin.abi} deb=${pin.file} bytes=${pin.bytes} sha=${pin.sha256} " +
                "abilist=${Build.SUPPORTED_ABIS.joinToString(",")}")
        val deb = try {
            Phase2Data.download(downloads(ctx), pin.url, pin.sha256, pin.file) { say("CSDL $it") }
        } catch (t: Throwable) {
            say("CS FAIL download ${pin.url}: $t")
            return Phase3Native.writeLog(ctx)
        }
        say("CSDL ok bytes=${deb.length()} path=${deb.absolutePath}")

        val root = root(ctx)
        val marker = marker(ctx)
        val already = !force && marker.isFile && marker.readText().trim() == pin.sha256
        if (already) {
            say("CS_UNPACK already-unpacked marker=${marker.absolutePath}")
        } else {
            if (root.exists()) say("CS_UNPACK wiping ${deleteTree(root)} entries under ${root.absolutePath}")
            root.mkdirs()
            val t1 = System.currentTimeMillis()
            val policy = CsPolicy()
            try {
                // A .deb is an ar archive: the tar reader must get the data.tar.xz
                // *member*, not the archive itself (Phase2Data.unpackDeb walks the ar).
                Phase2Data.unpackDeb(deb, root, policy)
                say("CS_UNPACK ms=${System.currentTimeMillis() - t1} skipped: ${policy.summary()}")
                say("CS_UNPACK links_skipped=${policy.linksSkipped} elf_guard_dropped=${policy.elfGuardDropped} " +
                        "elf_guard_bytes=${policy.elfGuardBytes}")
                try {
                    FileOutputStream(manifest(ctx)).use { it.write(policy.manifest.toString().toByteArray()) }
                } catch (t: Throwable) { say("CS_UNPACK manifest write failed: $t") }
            } catch (t: Throwable) {
                say("CS_FAIL unpack $t")
                return Phase3Native.writeLog(ctx)
            }
            try { marker.writeText(pin.sha256 + "\n") } catch (t: Throwable) { say("CS marker write failed: $t") }
        }
        linkNativeModules(ctx)
        verify(ctx)
        say("CS done ms=${System.currentTimeMillis() - t0}")
        Phase3Native.writeLog(ctx)
    }

    /** Verify the unpacked tree on the device: counts, ELF = 0, links, entry point. */
    fun verify(ctx: Context) {
        val root = root(ctx)
        var files = 0; var bytes = 0L; var elf = 0; var links = 0; var broken = 0
        val elfPaths = StringBuilder()
        root.walkTopDown().forEach { f ->
            val isLink = Files.isSymbolicLink(f.toPath())
            if (isLink) {
                links++
                if (!f.exists()) broken++
                return@forEach
            }
            if (!f.isFile) return@forEach
            files++; bytes += f.length()
            val head = try {
                FileInputStream(f).use { ins -> ByteArray(4).also { ins.read(it) } }
            } catch (_: Throwable) { ByteArray(0) }
            if (isElfHead(head)) { elf++; if (elfPaths.length < 400) elfPaths.append(f.absolutePath).append(' ') }
        }
        say("CS_VERIFY root=${root.absolutePath} files=$files bytes=$bytes elf=$elf symlinks=$links broken=$broken")
        if (elf > 0) say("CS_VERIFY ELF_FOUND $elfPaths")
        var missing = 0
        for ((rel, lib) in nativeLinks()) {
            val f = File(root, rel)
            val isLink = Files.isSymbolicLink(f.toPath())
            val target = try { if (isLink) Files.readSymbolicLink(f.toPath()).toString() else null } catch (_: Throwable) { null }
            val ok = target != null && target.endsWith("/$lib") && f.exists()
            if (!ok) { missing++; say("CS_VERIFY LINK_BAD $rel -> $target (want $lib)") }
        }
        say("CS_VERIFY links_ok=${nativeLinks().size - missing}/${nativeLinks().size}")
        val entry = File(root, "out/node/entry.js")
        say("CS_VERIFY entry=${entry.relativeTo(root).path} present=${entry.isFile} bytes=${if (entry.isFile) entry.length() else -1}")
        val pkg = File(root, "package.json")
        val product = File(root, "lib/vscode/product.json")
        say("CS_VERIFY codeServerVersion=${if (pkg.isFile) topLevelString(pkg.readText(), "version") else null} (from package.json)")
        say("CS_VERIFY vscodeVersion=${if (product.isFile) topLevelString(product.readText(), "version") else null} (from lib/vscode/product.json)")
        val wb = File(root, "lib/vscode/out/vs/code/browser/workbench/workbench.js")
        say("CS_VERIFY workbench=${wb.isFile} bytes=${if (wb.isFile) wb.length() else -1} " +
                "vscodeModules=${File(root, "lib/vscode/node_modules").isDirectory}")
        val marker = marker(ctx)
        say("CS_VERIFY marker=${marker.isFile} sha=${if (marker.isFile) marker.readText().trim() else "-"} " +
                "debBytes=${File(downloads(ctx), pinForDevice()?.file ?: "").length()}")
    }
}
