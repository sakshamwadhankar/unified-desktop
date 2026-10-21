package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Phase 8 installers: the winget manifest reader and its refusals, the update check, and the
 * .deb ar/tar path on a fixture built here. The two YAML fixtures are the real pinned
 * manifests for Notepad++ 8.9.8.1 and 7-Zip 26.04 (winget-pkgs commit bbae0d1), so a parser
 * that drifts from the schema fails here instead of on the phone.
 */
class InstallersTest {

    private fun fixture(name: String): String =
        javaClass.getResource("/winget/$name")!!.readText()

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "ud-installers-$name-${System.nanoTime()}")
            .apply { mkdirs() }

    // ------------------------------------------------------------------ winget -----

    @Test
    fun notepadPlusPlusManifestPicksTheX64MsiInstaller() {
        val m = Winget.pickX64(Winget.parse(fixture("Notepad++.Notepad++.installer.yaml")))
        assertEquals("Notepad++.Notepad++", m!!.identifier)
        assertEquals("8.9.8.1", m.version)
        assertEquals("x64", m.arch)
        assertEquals("wix", m.type)
        assertEquals(
            "https://github.com/notepad-plus-plus/notepad-plus-plus/releases/download/v8.9.8.1/" +
                "npp.8.9.8.1.Installer.x64.msi",
            m.url,
        )
        assertEquals("0C4F4FE82BABD7688F5C1431B1357392491563AF6FFEDF4E37F5DE0BD71BABB4", m.sha256)
        assertEquals("/qn", m.silent)
    }

    @Test
    fun sevenZipManifestPicksTheX64MsiInstaller() {
        val all = Winget.parse(fixture("7zip.7zip.installer.yaml"))
        val m = Winget.pickX64(all)!!
        assertEquals("7zip.7zip", m.identifier)
        assertEquals("26.04", m.version)
        assertEquals("wix", m.type)
        assertEquals("https://www.7-zip.org/a/7z2604-x64.msi", m.url)
        assertEquals("0B01334A418654293513449F61D6BFC99E5196CA371E3C3DC961EDA57CD535C6", m.sha256)
        assertEquals("/qn", m.silent)
        // The manifest also lists the x64 exe; the MSI wins because Wine's msiexec runs it.
        assertTrue(all.any { it.arch == "x64" && it.type == "exe" })
    }

    @Test
    fun catalogHashMismatchIsRefused() {
        val m = Winget.pickX64(Winget.parse(fixture("7zip.7zip.installer.yaml")))!!
        val wrong = "beddf5548e75c97930d1575ba1c7d113e6e2b33d0a200310e734ca08e225f414"
        val why = Winget.verify(m, wrong, "26.04")
        assertTrue("a different hash must be refused: $why", why != null && why.contains("SHA-256"))
        assertNull(Winget.verify(m, m.sha256.lowercase(), "26.04"))
    }

    @Test
    fun aManifestWithADifferentVersionIsRefused() {
        val m = Winget.pickX64(Winget.parse(fixture("7zip.7zip.installer.yaml")))!!
        val why = Winget.verify(m, m.sha256, "24.09")
        assertTrue("the catalog version must match the manifest: $why", why != null && why.contains("version"))
    }

    @Test
    fun updateIsOfferedOnlyForAnOlderInstalledVersion() {
        assertTrue(Winget.needsUpdate("26.04", "24.09"))
        assertTrue(Winget.needsUpdate("8.9.8.1", "8.9.8"))
        assertTrue(!Winget.needsUpdate("26.04", "26.04"))
        assertTrue(!Winget.needsUpdate("24.09", "26.04"))
        assertTrue(!Winget.needsUpdate("26.04", ""))
    }

    // --------------------------------------------------- installed store (B-083/B-086)

    private val regSample = """
        HKEY_LOCAL_MACHINE\Software\Microsoft\Windows\CurrentVersion\Uninstall\{8A25392D-C5D2-4E79-A5DE-5D3A0D6B0C3E}
            DisplayName    REG_SZ    Notepad++ (64-bit x64)
            DisplayVersion    REG_SZ    8.9.8.1
            UninstallString    REG_SZ    MsiExec.exe /X{8A25392D-C5D2-4E79-A5DE-5D3A0D6B0C3E}

        HKEY_LOCAL_MACHINE\Software\Microsoft\Windows\CurrentVersion\Uninstall\{23170F69-40C1-2702-2604-000001000000}
            DisplayName    REG_SZ    7-Zip 26.04 (x64)
            DisplayVersion    REG_SZ    26.04

        HKEY_LOCAL_MACHINE\Software\Microsoft\Windows\CurrentVersion\Uninstall\Notepad++ (32-bit x86)
            DisplayName    REG_SZ    Notepad++ (32-bit x86)
    """.trimIndent()

    @Test
    fun onlyRealGuidSubkeysAreProducts() {
        assertTrue(MsiProductCode.isGuid("{8A25392D-C5D2-4E79-A5DE-5D3A0D6B0C3E}"))
        assertTrue(!MsiProductCode.isGuid("Notepad++ (32-bit x86)"))
        assertTrue(!MsiProductCode.isGuid("{8A25392D-C5D2-4E79-A5DE-5D3A0D6B0C3"))
        assertTrue(!MsiProductCode.isGuid("8A25392D-C5D2-4E79-A5DE-5D3A0D6B0C3E"))
    }

    @Test
    fun uninstallRegistryIsParsedIntoGuidProductsOnly() {
        val products = MsiProductCode.parseRegQuery(regSample)
        assertEquals(2, products.size)
        assertEquals("{8A25392D-C5D2-4E79-A5DE-5D3A0D6B0C3E}", products[0].code)
        assertEquals("Notepad++ (64-bit x64)", products[0].displayName)
        assertEquals("8.9.8.1", products[0].version)
        assertEquals("7-Zip 26.04 (x64)", products[1].displayName)
    }

    @Test
    fun productCodeIsPickedByDisplayNameOrTheOnlyGuid() {
        val products = MsiProductCode.parseRegQuery(regSample)
        assertEquals(
            "{8A25392D-C5D2-4E79-A5DE-5D3A0D6B0C3E}",
            MsiProductCode.pick(products, listOf("Notepad++", "Notepad++.Notepad++"))!!.code,
        )
        assertEquals(
            "{23170F69-40C1-2702-2604-000001000000}",
            MsiProductCode.pick(products, listOf("7-Zip", "7zip.7zip"))!!.code,
        )
        val only = products.take(1)
        assertEquals(products[0].code, MsiProductCode.pick(only, listOf("something else"))!!.code)
        assertNull(MsiProductCode.pick(emptyList(), listOf("Notepad++")))
    }

    @Test
    fun installedStoreReadsOldStringsAndKeepsProductCodeAcrossVersionUpdates() {
        val dir = tempDir("store")
        val f = File(dir, "installed.json")
        f.writeText("""{"sevenzip":"26.04"}""")
        val old = InstalledStore.read(f)
        assertEquals("26.04", old["sevenzip"]!!.version)
        assertEquals("", old["sevenzip"]!!.productCode)

        InstalledStore.record(f, "notepadpp", "8.9.8.1", "{8A25392D-C5D2-4E79-A5DE-5D3A0D6B0C3E}")
        InstalledStore.record(f, "notepadpp", "8.9.8.1")
        assertEquals(
            "{8A25392D-C5D2-4E79-A5DE-5D3A0D6B0C3E}",
            InstalledStore.read(f)["notepadpp"]!!.productCode,
        )
        InstalledStore.record(f, "notepadpp", "9.0")
        val updated = InstalledStore.read(f)["notepadpp"]!!
        assertEquals("9.0", updated.version)
        assertEquals("{8A25392D-C5D2-4E79-A5DE-5D3A0D6B0C3E}", updated.productCode)

        InstalledStore.clear(f, "notepadpp")
        assertNull(InstalledStore.read(f)["notepadpp"])
        assertEquals("26.04", InstalledStore.read(f)["sevenzip"]!!.version)
    }

    @Test
    fun missingProductCodeRefusesUninstallTruthfully() {
        assertTrue(Installers.uninstallRefusal(null)!!.contains("not installed"))
        assertTrue(Installers.uninstallRefusal(InstalledApp("8.9.8.1", ""))!!.contains("ProductCode"))
        assertNull(Installers.uninstallRefusal(InstalledApp("8.9.8.1", "{GUID}")))
    }

    @Test
    fun uninstallCommandIsSilentMsiexecWithTheStoredCode() {
        assertEquals(
            listOf("/x", "{8A25392D-C5D2-4E79-A5DE-5D3A0D6B0C3E}", "/qn"),
            Installers.msiUninstallArgs("{8A25392D-C5D2-4E79-A5DE-5D3A0D6B0C3E}"),
        )
    }

    @Test
    fun theButtonFallsBackToInstallAfterUninstall() {
        assertEquals("Update", Winget.actionLabel("26.04", "24.09"))
        assertEquals("Install", Winget.actionLabel("26.04", "26.04"))
        // After uninstall the record is gone: no "Update" may be offered.
        assertEquals("Install", Winget.actionLabel("26.04", ""))
        assertTrue(!Winget.needsUpdate("26.04", ""))
    }

    // -------------------------------------------------------------------- .deb -----

    private fun octal(v: Long, width: Int): ByteArray =
        (v.toString(8).padStart(width - 1, '0') + "\u0000").toByteArray()

    private fun tarHeader(name: String, size: Long): ByteArray {
        val b = ByteArray(512)
        fun put(s: ByteArray, off: Int) = System.arraycopy(s, 0, b, off, minOf(s.size, 512 - off))
        put(name.toByteArray(), 0)
        put(octal(420L, 8), 100)
        put(octal(0L, 8), 108)
        put(octal(0L, 8), 116)
        put(octal(size, 12), 124)
        put(octal(1_767_000_000L, 12), 136)
        b[156] = '0'.code.toByte()
        put("ustar\u000000".toByteArray(), 257)
        return b
    }

    private fun padded(b: ByteArray) = b + ByteArray((512 - b.size % 512) % 512)

    private fun arMember(name: String, bytes: ByteArray): ByteArray {
        val h = buildString {
            append(name.padEnd(16))
            append("0".padEnd(12))
            append("0".padEnd(6))
            append("0".padEnd(6))
            append("100644".padEnd(8))
            append(bytes.size.toString().padEnd(10))
            append("`\n")
        }.toByteArray()
        assertEquals(60, h.size)
        return h + bytes + (if (bytes.size % 2 == 1) byteArrayOf(10) else byteArrayOf())
    }

    @Test
    fun debArAndTarReaderUnpacksTheDataMember() {
        val body = "hello xmessage\n".toByteArray()
        val tar = tarHeader("usr/bin/xmessage", body.size.toLong()) + padded(body) + ByteArray(1024)
        val gz = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(tar) }
        }.toByteArray()
        val dir = tempDir("deb")
        val deb = File(dir, "tiny.deb").apply {
            writeBytes("!<arch>\n".toByteArray() + arMember("data.tar.gz", gz))
        }
        val root = File(dir, "root").apply { mkdirs() }

        Phase2Data.unpackDeb(deb, root)

        val f = File(root, "usr/bin/xmessage")
        assertTrue("data.tar member must be unpacked: $f", f.isFile)
        assertEquals("hello xmessage\n", f.readText())
    }

    @Test
    fun aFileThatIsNotAnArArchiveIsRefused() {
        val dir = tempDir("notar")
        val deb = File(dir, "bad.deb").apply { writeText("not a deb") }
        val root = File(dir, "root").apply { mkdirs() }
        var threw = false
        try {
            Phase2Data.unpackDeb(deb, root)
        } catch (_: Throwable) {
            threw = true
        }
        assertTrue("a non-ar file must fail, not unpack", threw)
    }

    @Test
    fun msiRegistrationRestoreUsesOnlySavedKeysInPlanOrder() {
        val plan = Phase2Wine.msiStatePlan()
        assertEquals(4, plan.size)
        val names = plan.map { it.second }
        assertEquals(
            listOf("hklm-installer.reg", "hkcu-installer.reg"),
            Phase2Wine.msiRestoreNames(setOf(names[3], names[1])),
        )
        assertTrue(plan.any { it.first == "HKLM\\Software\\Classes\\Installer" })
        assertTrue(plan.any { it.first == "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall" })
    }

    @Test
    fun msiRegistrationIsSavedPerAppAndStaysInsideTheStateRoot() {
        val root = tempDir("msistate")
        val npp = Phase2Wine.msiStateDirFor(root, "notepadpp")
        val seven = Phase2Wine.msiStateDirFor(root, "sevenzip")

        assertEquals(File(root, "wine-msi-state/notepadpp"), npp)
        assertEquals(File(root, "wine-msi-state/sevenzip"), seven)
        assertNotEquals("each app needs its own saved registration", npp, seven)

        // A catalog id is data, not a path: separators and traversal cannot leave the root.
        val odd = Phase2Wine.msiStateDirFor(root, "../evil:dir")
        assertEquals(File(root, "wine-msi-state/evil_dir"), odd)
        assertFalse(odd.path.contains(".."))
        assertTrue(odd.parentFile == File(root, "wine-msi-state"))
    }

    @Test
    fun msiStateFilesArePassedToWineAsZDrivePaths() {
        assertEquals(
            "Z:\\data\\user\\0\\dev.unifieddesktop.app\\files\\win86\\wine-msi-state\\notepadpp\\hklm-uninstall.reg",
            Phase2Wine.winePath(
                "/data/user/0/dev.unifieddesktop.app/files/win86/wine-msi-state/notepadpp/hklm-uninstall.reg",
            ),
        )
        assertEquals("C:\\already\\windows", Phase2Wine.winePath("C:\\already\\windows"))
    }
}
