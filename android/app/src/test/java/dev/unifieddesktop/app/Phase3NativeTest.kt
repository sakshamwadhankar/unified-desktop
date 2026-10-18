package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 unit tests for the native-module mapping table and the environment
 * Node.js is started with. Pure code only - no Android runtime (the project sets
 * unitTests.isReturnDefaultValues).
 */
class Phase3NativeTest {

    @Test
    fun everyMappedLibIsShippedAndMatchesTheAndroidLibRule() {
        val libs = Phase3Native.NATIVE_MODULES.values + Phase3Native.NATIVE_MODULES_CODER.values
        for (lib in libs) {
            assertTrue("lib name must match lib*.so: $lib", Regex("^lib[a-zA-Z0-9_+.-]+\\.so$").matches(lib))
            assertTrue("$lib must also be in SHIPPED_LIBS (fetch-native.sh writes it)",
                lib in Phase3Native.SHIPPED_LIBS)
        }
    }

    @Test
    fun shippedLibsIncludeTheNodeRuntimeAndItsDependencies() {
        val need = listOf(
            Phase3Native.LIB_NODE, Phase3Native.LIB_CXX, Phase3Native.LIB_SSL,
            Phase3Native.LIB_CRYPTO, Phase3Native.LIB_CARES,
        )
        for (n in need) assertTrue("missing $n", n in Phase3Native.SHIPPED_LIBS)
        // The five .node modules code-server loads on Android, plus argon2.
        assertEquals(6, Phase3Native.NATIVE_MODULES.size)
        assertEquals(1, Phase3Native.NATIVE_MODULES_CODER.size)
        assertEquals("libargon2.so", Phase3Native.moduleLibFor("node_modules/argon2/build/Release/argon2.node", coderTree = true))
    }

    @Test
    fun moduleLibForNormalizesPathsAndRejectsUnknownOnes() {
        assertEquals("libpty.so", Phase3Native.moduleLibFor("node_modules/node-pty/build/Release/pty.node"))
        assertEquals("libpty.so", Phase3Native.moduleLibFor("./node_modules/node-pty/build/Release/pty.node"))
        assertEquals("libpty.so", Phase3Native.moduleLibFor("node_modules\\node-pty\\build\\Release\\pty.node"))
        assertEquals("libsqlite3node.so", Phase3Native.moduleLibFor("node_modules/@vscode/sqlite3/build/Release/vscode-sqlite3.node"))
        // The glibc/musl prebuilds that must never be loaded are NOT mapped.
        assertNull(Phase3Native.moduleLibFor("node_modules/@parcel/watcher-linux-x64-glibc/watcher.node"))
        assertNull(Phase3Native.moduleLibFor("node_modules/@github/copilot-sdk-linux-x64/runtime.node"))
        assertNull(Phase3Native.moduleLibFor("node_modules/argon2/build/Release/argon2.node")) // coder tree only
        assertNull(Phase3Native.moduleLibFor("out/node/entry.js"))
    }

    @Test
    fun envPutsTheApkLibraryDirFirstAndUsesSystemPaths() {
        val e = Phase3Native.envFor("/data/app/x/lib/x86_64", "/files/phase3/home", "/files/phase3/tmp")
        assertEquals("/data/app/x/lib/x86_64", e["LD_LIBRARY_PATH"])
        assertEquals("/system/etc/security/cacerts", e["SSL_CERT_DIR"])
        assertEquals("/system/bin/sh", e["SHELL"])
        assertEquals("/files/phase3/home", e["HOME"])
        assertEquals("/files/phase3/tmp", e["TMPDIR"])
        // No Termux paths may leak into the environment.
        for ((_, v) in e) assertTrue("Termux path leaked: $v", !v.contains("com.termux"))
    }
}
