package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item-5 unit tests: the code-server pin table, the skip filter and the ELF guard.
 *
 * Every path in the "real deb" tests was taken from the pinned
 * `code-server_4.141.0_x86_64.deb` (unpacked on the laptop 2026-10-18), so a failure
 * here means the app would write (or drop) something different from the plan.
 */
class Phase3DataTest {

    // ------------------------------------------------------------------ pin table ---

    @Test
    fun pinForSelectsTheDeviceAbiAndRefusesUnknownOnes() {
        assertEquals("x86_64", Phase3Data.pinFor("x86_64")?.abi)
        assertEquals("x86_64", Phase3Data.pinFor("X86_64")?.abi)
        assertEquals("aarch64", Phase3Data.pinFor("arm64-v8a")?.abi)
        assertEquals("aarch64", Phase3Data.pinFor("arm64")?.abi)
        assertEquals("aarch64", Phase3Data.pinFor("aarch64")?.abi)
        // Never guess a deb for an ABI we did not pin.
        assertNull(Phase3Data.pinFor("armeabi-v7a"))
        assertNull(Phase3Data.pinFor("x86"))
        assertNull(Phase3Data.pinFor(""))
        assertNull(Phase3Data.pinFor(null))
    }

    @Test
    fun bothPinsAreSha256AndFollowTheTurdLayout() {
        assertEquals(2, Phase3Data.PINS.size)
        for (p in Phase3Data.PINS) {
            assertTrue("sha256: ${p.sha256}", Regex("^[0-9a-f]{64}$").matches(p.sha256))
            assertTrue("url: ${p.url}", p.url.startsWith("https://tur.kcubeterm.com/pool/tur/code-server/"))
            assertTrue("file: ${p.file}", p.url.endsWith("/${p.file}"))
            assertTrue("bytes: ${p.bytes}", p.bytes > 100_000_000L)
        }
        assertEquals(Phase3Data.PINS.map { it.abi }.toSet(), setOf("x86_64", "aarch64"))
    }

    // ------------------------------------------------------------- deb member names ---

    @Test
    fun mapDebNameStripsTheTermuxPrefixAndRejectsForeignMembers() {
        assertEquals("out/node/entry.js",
            Phase3Data.mapDebName("./data/data/com.termux/files/usr/lib/code-server/out/node/entry.js"))
        assertEquals("lib/vscode/product.json",
            Phase3Data.mapDebName("data/data/com.termux/files/usr/lib/code-server/lib/vscode/product.json"))
        // A directory member that is exactly the prefix maps to nothing.
        assertNull(Phase3Data.mapDebName("data/data/com.termux/files/usr/lib/code-server/"))
        // Anything outside the code-server tree is not ours to write.
        assertNull(Phase3Data.mapDebName("data/data/com.termux/files/usr/bin/node"))
        assertNull(Phase3Data.mapDebName("usr/share/doc/code-server/copyright"))
    }

    // ------------------------------------------------------------- skip filter -------

    @Test
    fun skipFilterDropsEveryNativePayloadThePromptNames() {
        val cases = mapOf(
            "lib/vscode/node_modules/@github/copilot-sdk-linux-x64/prebuilds/linux-x64/runtime.node" to "copilot-sdk",
            "lib/vscode/node_modules/@github/copilot-sdk-linux-x64/ripgrep/bin/linux-x64/rg" to "copilot-sdk",
            "lib/vscode/node_modules/@github/copilot-sdk-linux-x64/tgrep/bin/linux-x64/tgrep" to "copilot-sdk",
            "lib/vscode/node_modules/@microsoft/mxc-sdk/bin/x64/libmxc_ffi.so" to "mxc-sdk",
            "lib/vscode/node_modules/@microsoft/mxc-sdk/bin/x64/lxc-exec" to "mxc-sdk",
            "lib/vscode/node_modules/@microsoft/mxc-sdk/bin/arm64/unix-test-proxy" to "mxc-sdk",
            "lib/vscode/node_modules/@koromix/koffi-linux-x64/linux_x64/koffi.node" to "koffi",
            "lib/vscode/node_modules/@koromix/koffi-linux-x64/musl_x64/koffi.node" to "koffi",
            "lib/vscode/node_modules/@parcel/watcher-linux-x64-glibc/watcher.node" to "parcel-watcher-bin",
            "lib/vscode/node_modules/@parcel/watcher-linux-x64-musl/watcher.node" to "parcel-watcher-bin",
            "lib/vscode/node_modules/@vscode/sandbox-runtime/vendor/seccomp/x64/apply-seccomp" to "seccomp",
            "lib/vscode/node_modules/@vscode/sandbox-runtime/vendor/seccomp-src/apply-seccomp.c" to "seccomp",
            "lib/vscode/extensions/microsoft-authentication/dist/libmsalruntime.so" to "msal",
            "lib/vscode/extensions/microsoft-authentication/dist/msal-node-runtime.node" to "msal",
            "node_modules/argon2/prebuilds/linux-x64/argon2.glibc.node" to "argon2-prebuild",
            "node_modules/argon2/prebuilds/win32-x64/argon2.glibc.node" to "argon2-prebuild",
        )
        for ((path, want) in cases) assertEquals(path, want, Phase3Data.skipReason(path))
    }

    @Test
    fun skipFilterDropsEveryNativeModuleAndEveryBuildIntermediate() {
        // The mapped modules: their APK copies are linked in, the deb's bytes are not written.
        for (rel in Phase3Data.nativeLinks().keys) {
            assertNotNull("mapped path must be skipped from disk: $rel", Phase3Data.skipReason(rel))
        }
        // Windows-only and prebuilt-other-platform modules of the deb.
        assertEquals("native-module", Phase3Data.skipReason("lib/vscode/extensions/ms-vscode.js-debug/src/win32-app-container-tokens.win32-x64-msvc-VCQE7GJP.node"))
        assertEquals("native-module", Phase3Data.skipReason("lib/vscode/node_modules/@vscode/deviceid/build/Release/windows.node"))
        assertEquals("native-module", Phase3Data.skipReason("lib/vscode/node_modules/@vscode/windows-registry/build/Release/winregistry.node"))
        // Inside a gyp work dir the attribution is the build intermediate, not the module.
        assertEquals("build-obj", Phase3Data.skipReason("lib/vscode/node_modules/@vscode/native-watchdog/build/Release/obj.target/watchdog.node"))
        // Object files / static libs / dependency files / stamps.
        assertEquals("build-obj", Phase3Data.skipReason("lib/vscode/node_modules/kerberos/build/Release/obj.target/kerberos/src/kerberos.o"))
        assertEquals("build-obj", Phase3Data.skipReason("lib/vscode/node_modules/@vscode/sqlite3/build/Release/.deps/Release/obj.target/vscode-sqlite3.node.d"))
        assertEquals("obj", Phase3Data.skipReason("node_modules/argon2/build/Release/argon2.a"))
        assertEquals("build-obj", Phase3Data.skipReason("node_modules/argon2/build/Release/obj.target/argon2.a.ar-file-list"))
        assertEquals("build-obj", Phase3Data.skipReason("lib/vscode/node_modules/@vscode/sqlite3/build/Release/obj.target/deps/action_before_build.stamp"))
    }

    @Test
    fun skipFilterKeepsTheJavaScriptAndDataOfTheWorkbench() {
        val keep = listOf(
            "out/node/entry.js",
            "out/node/app.js",
            "package.json",
            "ThirdPartyNotices.txt",
            "lib/vscode/out/vs/code/browser/workbench/workbench.js",
            "lib/vscode/package.json",
            "lib/vscode/product.json",
            "lib/vscode/extensions/copilot/dist/extension.js",
            "lib/vscode/extensions/copilot/dist/tree-sitter-python.wasm",
            // The msal *extension* keeps its JS; only its two native files go.
            "lib/vscode/extensions/microsoft-authentication/dist/extension.js",
            // @vscode/sandbox-runtime keeps everything but vendor/.
            "lib/vscode/node_modules/@vscode/sandbox-runtime/dist/cli.js",
            "lib/vscode/node_modules/@vscode/sandbox-runtime/dist/sandbox/generate-seccomp-filter.js",
            // argon2 keeps its JS, the phc-format licence and the C sources.
            "node_modules/argon2/argon2.cjs",
            "node_modules/argon2/argon2/LICENSE",
            "node_modules/argon2/argon2/src/argon2.c",
            // Build scaffolding that is text is harmless and stays.
            "node_modules/argon2/build/Makefile",
            "lib/vscode/node_modules/@vscode/sqlite3/lib/sqlite3-binding.js",
        )
        for (p in keep) assertNull("must be kept: $p", Phase3Data.skipReason(p))
    }

    @Test
    fun noSkipRuleFiresOnALegitimateFileName() {
        // Guard against a rule so broad it eats real files (e.g. "*.d" would eat this).
        assertNull(Phase3Data.skipReason("lib/vscode/node_modules/@types/node/index.d.ts"))
        assertNull(Phase3Data.skipReason("lib/vscode/extensions/cpp/package.nls.json"))
        assertNull(Phase3Data.skipReason("node_modules/semver/index.js.map"))
    }

    @Test
    fun absoluteSymlinksAreDroppedAndRelativeOnesAreKept() {
        assertEquals("abs-link", Phase3Data.skipLink(absTarget = true))
        assertNull(Phase3Data.skipLink(absTarget = false))
    }

    // ------------------------------------------------------------------- ELF guard ---

    @Test
    fun elfGuardRecognizesElfHeadsOnly() {
        assertTrue(Phase3Data.isElfHead(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())))
        assertFalse(Phase3Data.isElfHead("{\n  \"a\": 1".toByteArray()))
        assertFalse(Phase3Data.isElfHead("#!/bin/sh\n".toByteArray()))
        assertFalse(Phase3Data.isElfHead(byteArrayOf(0x7f, 'E'.code.toByte())))
        assertFalse(Phase3Data.isElfHead(ByteArray(0)))
    }

    // -------------------------------------------------------------------- policy -----

    @Test
    fun policyCountsWhatItDropsAndSaysWhy() {
        val p = Phase3Data.CsPolicy()
        assertEquals("out/node/entry.js",
            p.mapName("./data/data/com.termux/files/usr/lib/code-server/out/node/entry.js"))
        assertNull(p.mapName("data/data/com.termux/files/usr/opt/node/node"))
        assertEquals("native-module", p.skipReason("lib/vscode/node_modules/node-pty/build/Release/pty.node", 72_208))
        assertEquals("abs-link", p.skipLink("lib/node", "/data/data/com.termux/files/usr/opt/nodejs-24/bin/node"))
        // An unexpected ELF (a name no rule knows) is still not written.
        assertFalse(p.keepBytes("lib/vscode/whatever/rg", byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()), 5_727_920))
        assertTrue(p.keepBytes("lib/vscode/out/vs/code/browser/workbench/workbench.js", "/*!*/".toByteArray(), 4))
        assertEquals(1, p.elfGuardDropped)
        assertEquals(5_727_920L, p.elfGuardBytes)
        assertTrue(p.summary().contains("native-module=1f/72208b"))
        assertTrue(p.summary().contains("elf-guard=1f/5727920b"))
        assertTrue(p.manifest.contains("native-module"))
    }

    @Test
    fun nativeLinksCoverTheSevenApkModulesAtTheirDebPaths() {
        val links = Phase3Data.nativeLinks()
        assertEquals(7, links.size)
        assertEquals(Phase3Native.LIB_PTY, links["lib/vscode/node_modules/node-pty/build/Release/pty.node"])
        assertEquals(Phase3Native.LIB_WATCHER, links["lib/vscode/node_modules/@parcel/watcher/build/Release/watcher.node"])
        assertEquals(Phase3Native.LIB_SPDLOG, links["lib/vscode/node_modules/@vscode/spdlog/build/Release/spdlog.node"])
        assertEquals(Phase3Native.LIB_SQLITE, links["lib/vscode/node_modules/@vscode/sqlite3/build/Release/vscode-sqlite3.node"])
        assertEquals(Phase3Native.LIB_WATCHDOG, links["lib/vscode/node_modules/@vscode/native-watchdog/build/Release/watchdog.node"])
        assertEquals(Phase3Native.LIB_KERBEROS, links["lib/vscode/node_modules/kerberos/build/Release/kerberos.node"])
        assertEquals(Phase3Native.LIB_ARGON2, links["node_modules/argon2/build/Release/argon2.node"])
        for (lib in links.values) assertTrue("$lib must be an APK lib", lib in Phase3Native.SHIPPED_LIBS)
        // The loader resolves by path suffix, so no two keys may share one.
        for (a in links.keys) for (b in links.keys) {
            if (a != b) assertFalse("suffix clash: $a / $b", a.endsWith(b) || b.endsWith(a))
        }
    }

    // --------------------------------------------------------- top-level JSON string --

    @Test
    fun topLevelStringIgnoresNestedVersions() {
        // Shape of VS Code's product.json: a nested "version" appears before the real one.
        val product = """
            {
              "nameShort": "code-server",
              "extensionKeywords": { "version": "1.1.3" },
              "version": "1.141.0",
              "quality": "stable"
            }
        """.trimIndent()
        assertEquals("1.141.0", Phase3Data.topLevelString(product, "version"))
        assertEquals("code-server", Phase3Data.topLevelString(product, "nameShort"))
        assertNull(Phase3Data.topLevelString(product, "commit"))
        assertEquals("4.141.0", Phase3Data.topLevelString("{\n  \"version\": \"4.141.0\"\n}", "version"))
        // Escapes only keep the scanner from ending the string early; the value is raw.
        assertEquals("a\\\"b", Phase3Data.topLevelString("{\"version\": \"a\\\"b\"}", "version"))
    }
}
