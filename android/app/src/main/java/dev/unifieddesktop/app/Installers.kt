package dev.unifieddesktop.app

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase 8 installers: a winget manifest reader (the small YAML subset the manifests use),
 * its decisions, and the Debian package path. Everything that decides *what* may run is
 * pure and unit-tested; only [installWinget] / [installDeb] touch the phone.
 *
 * The rule from D6 holds throughout: the downloaded .exe is data for the shipped Wine
 * loader, and the .deb's data.tar is unpacked into our own rootfs. Nothing from app-writable
 * storage is executed natively.
 */

/** One `Installers:` block of a winget installer manifest. */
data class WingetInstaller(
    val identifier: String,
    val version: String,
    val arch: String,
    val type: String,
    val url: String,
    val sha256: String,
    val silent: String,
)

object Winget {

    /** Architecture we can run: the x86-64 Wine build. */
    const val ARCH = "x64"

    /**
     * Parse the handful of keys we need from an installer manifest, without a YAML library.
     * The shape is fixed by winget's schema: top-level keys, then an `Installers:` list whose
     * items start with `- Architecture:` and hold two-space-indented keys; nested
     * `InstallerSwitches:` holds four-space-indented switches.
     */
    fun parse(text: String): List<WingetInstaller> {
        var identifier = ""
        var version = ""
        var inInstallers = false
        var cur: MutableMap<String, String>? = null
        var section = ""
        val blocks = ArrayList<MutableMap<String, String>>()

        fun flush() {
            cur?.let { blocks.add(it) }
            cur = null
            section = ""
        }

        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val indent = line.length - line.trimStart().length
            if (!inInstallers) {
                if (indent == 0 && t == "Installers:") inInstallers = true
                else if (indent == 0) {
                    when {
                        t.startsWith("PackageIdentifier:") -> identifier = value(t)
                        t.startsWith("PackageVersion:") -> version = value(t)
                    }
                }
                continue
            }
            if (indent == 0) {
                if (!t.startsWith("- ")) break // ManifestType / ManifestVersion
                flush()
                cur = HashMap()
                putKey(cur!!, t.removePrefix("- ").trim(), "", 0)
                continue
            }
            val c = cur ?: continue
            if (t.endsWith(":")) {
                section = t.dropLast(1)
                continue
            }
            if (!t.contains(':')) continue
            val nested = if (indent >= 4 && section.isNotEmpty()) "$section." else ""
            if (indent < 4) section = ""
            putKey(c, t, nested, indent)
        }
        flush()
        return blocks.map { b ->
            WingetInstaller(
                identifier = identifier,
                version = version,
                arch = b["Architecture"].orEmpty(),
                type = b["InstallerType"].orEmpty(),
                url = b["InstallerUrl"].orEmpty(),
                sha256 = b["InstallerSha256"].orEmpty().uppercase(),
                silent = b["InstallerSwitches.Silent"]
                    ?: b["InstallerSwitches.SilentWithProgress"]
                    ?: silentSwitch(b["InstallerType"].orEmpty()),
            )
        }
    }

    private fun value(line: String): String =
        line.substringAfter(':').trim().trim('"')

    private fun putKey(map: MutableMap<String, String>, line: String, prefix: String, indent: Int) {
        if (!line.contains(':')) return
        val k = line.substringBefore(':').trim()
        val v = line.substringAfter(':').trim().trim('"')
        map[prefix + k] = v
    }

    /** x64 entry, preferring the MSI (Wine's msiexec runs it without the SFX/shlwapi crash). */
    fun pickX64(list: List<WingetInstaller>): WingetInstaller? =
        list.filter { it.arch.equals(ARCH, ignoreCase = true) && it.url.isNotEmpty() }
            .minByOrNull { rank(it.type) }

    private fun rank(type: String): Int = when (type.lowercase()) {
        "wix", "msi" -> 0
        "nullsoft" -> 1
        "exe" -> 2
        "inno" -> 3
        "burn" -> 4
        "zip" -> 6
        else -> 5
    }

    /** The silent switch for an installer type; the manifest may override it. */
    fun silentSwitch(type: String): String = when (type.lowercase()) {
        "nullsoft", "exe" -> "/S"
        "inno" -> "/VERYSILENT"
        "wix", "msi" -> "/qn"
        else -> ""
    }

    /**
     * Refuse unless the manifest still matches the catalog pin. Returns the reason, or null.
     * The catalog hash is the trust anchor: a changed upstream manifest must never be run.
     */
    fun verify(m: WingetInstaller, catalogSha: String, catalogVersion: String): String? {
        if (catalogSha.isBlank()) return "catalog has no installer SHA-256"
        if (!m.sha256.equals(catalogSha, ignoreCase = true)) {
            return "SHA-256 mismatch: manifest ${m.sha256.take(16)}… catalog ${catalogSha.take(16)}…"
        }
        if (m.version != catalogVersion) {
            return "version mismatch: manifest ${m.version} catalog $catalogVersion"
        }
        return null
    }

    /** True when [installed] is older than [catalog] (an update is offered). */
    fun needsUpdate(catalog: String, installed: String): Boolean =
        installed.isNotBlank() && catalog.isNotBlank() &&
            Catalog.compareVersions(catalog, installed) > 0

    /** The App Center's install button: "Update" only for an older installed version. */
    fun actionLabel(catalog: String, installed: String): String =
        if (needsUpdate(catalog, installed)) "Update" else "Install"
}

/** One installed-app record: the version, plus the MSI product code when there is one. */
data class InstalledApp(val version: String = "", val productCode: String = "") {
    fun isEmpty(): Boolean = version.isBlank() && productCode.isBlank()
}

/**
 * The installed-app store, file-backed and free of Android types so the JVM tests exercise
 * the real read/write path. The old format (id -> "version") still reads: its values carry
 * no product code, so a winget uninstall of such a record refuses truthfully.
 */
object InstalledStore {
    fun read(f: File): MutableMap<String, InstalledApp> {
        if (!f.isFile) return mutableMapOf()
        return try {
            val o = JSONObject(f.readText())
            val out = mutableMapOf<String, InstalledApp>()
            o.keys().forEach { id ->
                when (val v = o.opt(id)) {
                    is JSONObject -> out[id] = InstalledApp(
                        version = v.optString("version"),
                        productCode = v.optString("productCode"),
                    )
                    is String -> out[id] = InstalledApp(version = v)
                    else -> {}
                }
            }
            out
        } catch (_: Throwable) {
            mutableMapOf()
        }
    }

    fun write(f: File, all: Map<String, InstalledApp>) {
        val o = JSONObject()
        all.forEach { (id, rec) ->
            o.put(id, JSONObject().put("version", rec.version).put("productCode", rec.productCode))
        }
        f.parentFile?.mkdirs()
        f.writeText(o.toString())
    }

    /** Record [version]; a blank [productCode] keeps one already stored for this app. */
    fun record(f: File, id: String, version: String, productCode: String = "") {
        val all = read(f)
        val old = all[id]
        all[id] = InstalledApp(
            version = version.ifBlank { old?.version.orEmpty() },
            productCode = productCode.ifBlank { old?.productCode.orEmpty() },
        )
        write(f, all)
    }

    fun clear(f: File, id: String) {
        val all = read(f)
        all.remove(id)
        write(f, all)
    }
}

/** Installed records recorded by the App Center (one small JSON file). */
object Installed {
    fun file(ctx: Context): File = File(ctx.filesDir, "appcenter/installed.json")

    fun read(ctx: Context): MutableMap<String, InstalledApp> = InstalledStore.read(file(ctx))

    fun version(ctx: Context, id: String): String = read(ctx)[id]?.version.orEmpty()

    fun productCode(ctx: Context, id: String): String = read(ctx)[id]?.productCode.orEmpty()

    fun record(ctx: Context, id: String, version: String, productCode: String = "") {
        InstalledStore.record(file(ctx), id, version, productCode)
        P7Log.say(ctx, "APPCTR installed $id=$version productCode=${productCode.ifEmpty { "-" }}")
    }

    fun clear(ctx: Context, id: String) {
        InstalledStore.clear(file(ctx), id)
        P7Log.say(ctx, "APPCTR installed record cleared $id")
    }
}

/**
 * MSI product codes from `reg query ...\Uninstall /s` output. Only subkeys that are a GUID
 * are products; display names (which may contain spaces) identify which one is ours.
 */
data class MsiProduct(val code: String, val displayName: String, val version: String)

object MsiProductCode {
    /**
     * `{8A25392D-C5D2-4E79-A5DE-5D3A0D6B0C3E}` without a regex: Android's ICU rejects the
     * `\{` escape that the JVM accepts, and a static Regex initializer failing takes the
     * whole object down with ExceptionInInitializerError (seen on the phone 2026-10-20).
     */
    fun isGuid(s: String): Boolean {
        val t = s.trim()
        if (t.length != 38 || t[0] != '{' || t[37] != '}') return false
        for (i in 1..36) {
            val c = t[i]
            if (i == 9 || i == 14 || i == 19 || i == 24) {
                if (c != '-') return false
            } else if (!(c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F')) {
                return false
            }
        }
        return true
    }

    fun parseRegQuery(text: String): List<MsiProduct> {
        val out = ArrayList<MsiProduct>()
        var code: String? = null
        var name = ""
        var version = ""
        fun flush() {
            val c = code ?: return
            out.add(MsiProduct(c, name, version))
            code = null; name = ""; version = ""
        }
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isNotBlank() && !line.first().isWhitespace()) {
                if (line.startsWith("HKEY_", ignoreCase = true)) {
                    flush()
                    val key = line.substringAfterLast('\\')
                    code = if (isGuid(key)) key.uppercase() else null
                }
                continue
            }
            val t = line.trim()
            val field = t.substringBefore(' ')
            if (field != "DisplayName" && field != "DisplayVersion") continue
            // "DisplayName    REG_SZ    Notepad++ (64-bit x64)": skip the value type,
            // keep the rest verbatim (display names contain spaces).
            val value = t.substringAfter(' ', "").trim().substringAfter(' ', "").trim().trim('"')
            when (field) {
                "DisplayName" -> name = value
                "DisplayVersion" -> version = value
            }
        }
        flush()
        return out
    }

    /** Lower-case letters and digits only: "7-Zip 26.04 (x64)" -> "7zip2604x64". */
    fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Pick the product whose DisplayName contains one of [hints] ("Notepad++", the winget
     * identifier). A single GUID product is accepted even when the name differs, because
     * there is nothing else it could be.
     */
    fun pick(products: List<MsiProduct>, hints: List<String>): MsiProduct? {
        val hs = hints.map(::normalize).filter { it.length >= 3 }
        val named = products.firstOrNull { p ->
            val n = normalize(p.displayName)
            n.isNotEmpty() && hs.any { n.contains(it) }
        }
        return named ?: products.singleOrNull()
    }
}

object Installers {

    private fun fetchText(url: String, timeoutMs: Int = 20000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "unified-desktop-dev")
        try {
            val code = c.responseCode
            require(code in 200..299) { "HTTP $code for $url" }
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * winget path: fetch the pinned manifest, pick the x64 installer, refuse a hash/version
     * mismatch, download + verify, then run it under the shipped Wine loader with its silent
     * switch. Returns a one-line result for the App Center status.
     */
    fun installWinget(
        ctx: Context,
        appId: String,
        appName: String,
        v: CatalogVersion,
        post: (String) -> Unit,
    ): String {
        val manifestText = fetchText(v.fetch)
        val all = Winget.parse(manifestText)
        val m = Winget.pickX64(all) ?: return "refused: no x64 installer in the manifest"
        Winget.verify(m, v.sha256, v.version)?.let {
            P7Log.say(ctx, "APPCTR install $appId refused: $it")
            return "refused: $it"
        }
        P7Log.say(
            ctx,
            "APPCTR winget $appId id=${m.identifier} v=${m.version} type=${m.type} " +
                "sha=${m.sha256.take(12)}… url=${m.url}",
        )
        // The manifest SHA-256 is upper-case; the downloader compares lower-case hex.
        val exe = Phase2Data.download(
            Phase2Data.downloadsDir(ctx), m.url, m.sha256.lowercase(), "$appId-${m.version}.${ext(m.type)}",
        )
        P7Log.say(ctx, "APPCTR winget $appId downloaded bytes=${exe.length()}")
        post("running ${m.identifier} ${m.silent} under Wine...")
        val code = Phase2Wine.runInstaller(ctx, exe, m.silent)
        P7Log.say(ctx, "APPCTR winget $appId installer exit=$code file=$exe")
        if (code != 0) return "installer exit $code (see p2.log)"
        if (!isMsi(m.type)) {
            Installed.record(ctx, appId, m.version)
            return "installed ${m.identifier} ${m.version}"
        }
        // B-086: keep the ProductCode while the installer's registration is live. Every
        // boot drops the hives, so a later msiexec /x needs this stored value.
        val text = try {
            Phase2Wine.queryUninstallRegistry(ctx)
        } catch (t: Throwable) {
            P7Log.say(ctx, "APPCTR winget $appId regquery threw ${t.stackTraceToString()}")
            ""
        }
        P7Log.say(ctx, "APPCTR winget $appId regquery bytes=${text.length}")
        val products = try {
            MsiProductCode.parseRegQuery(text)
        } catch (t: Throwable) {
            P7Log.say(ctx, "APPCTR winget $appId parse threw ${t.stackTraceToString()}")
            emptyList()
        }
        val product = MsiProductCode.pick(products, listOf(appName, m.identifier))
        P7Log.say(
            ctx,
            "APPCTR winget $appId msi products=${products.size} picked=${product?.code ?: "-"} " +
                "name=${product?.displayName ?: "-"}",
        )
        if (product != null) {
            Installed.record(ctx, appId, m.version, product.code)
            // B-094: keep what msiexec /x needs across the next cold boot, per app.
            Phase2Wine.saveMsiRegistration(ctx, appId)
            return "installed ${m.identifier} ${m.version} (product ${product.code})"
        }
        Installed.record(ctx, appId, m.version)
        return "installed ${m.identifier} ${m.version} but no MSI ProductCode was found; " +
            "uninstall will refuse (see p2.log)"
    }

    private fun isMsi(type: String): Boolean = type.equals("wix", true) || type.equals("msi", true)

    private fun ext(type: String): String = if (isMsi(type)) "msi" else "exe"

    /** Why this record cannot be uninstalled, or null when it has a product code. */
    fun uninstallRefusal(rec: InstalledApp?): String? = when {
        rec == null || rec.isEmpty() -> "not installed (no record)"
        rec.productCode.isBlank() ->
            "cannot uninstall: no MSI ProductCode was recorded; install it again to record one"
        else -> null
    }

    /** The argued command, kept pure so the exact silent-uninstall shape is tested. */
    fun msiUninstallArgs(code: String): List<String> = listOf("/x", code, "/qn")

    /**
     * B-086: run `msiexec /x {code} /qn` through the shipped Wine loader. The caller must
     * already have a live prefix (the install session keeps one); otherwise the boot that
     * makes Wine usable also drops the registry hives the uninstall needs.
     */
    fun uninstallWinget(ctx: Context, appId: String, post: (String) -> Unit): String {
        val rec = Installed.read(ctx)[appId]
        uninstallRefusal(rec)?.let { return it }
        post("uninstalling $appId (${rec!!.productCode})...")
        val code = Phase2Wine.runMsiUninstall(ctx, appId, rec.productCode)
        P7Log.say(ctx, "APPCTR uninstall $appId code=${rec.productCode} exit=$code")
        if (code != 0) return "uninstall failed: msiexec exit $code (see p2.log)"
        Installed.clear(ctx, appId)
        return "uninstalled $appId (msiexec ${msiUninstallArgs(rec.productCode).joinToString(" ")})"
    }

    /** Debian path: download + verify the .deb, then unpack its data.tar into the rootfs. */
    fun installDeb(ctx: Context, appId: String, v: CatalogVersion, post: (String) -> Unit): String {
        if (v.sha256.isBlank()) return "refused: no pinned SHA-256"
        post("downloading ${v.fetch.substringAfterLast('/')}...")
        val deb = Phase2Data.download(
            Phase2Data.downloadsDir(ctx), v.fetch, v.sha256, v.fetch.substringAfterLast('/'),
        )
        val rootfs = Phase2Data.rootfsDir(ctx)
        P7Log.say(ctx, "APPCTR deb $appId bytes=${deb.length()} -> ${rootfs.path}")
        Phase2Data.unpackDeb(deb, rootfs)
        Installed.record(ctx, appId, v.version)
        return "unpacked ${deb.name} into the rootfs"
    }
}
