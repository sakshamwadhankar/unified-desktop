package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item-6 unit tests. Only the pure parts of the server are testable off-device: the code
 * server config text and the password generator. Binding, /healthz and the process work
 * are verified on the device and land in files/phase3/p3.log.
 */
class Phase3ServerTest {

    @Test
    fun configBindsLoopbackOnlyWithPasswordAuth() {
        val y = Phase3Server.configYaml(34567, "0123456789abcdef0123456789abcdef")
        val lines = y.lines().filter { it.isNotBlank() }
        assertEquals("bind-addr: 127.0.0.1:34567", lines.first { it.startsWith("bind-addr") })
        assertTrue(y.contains("auth: password"))
        assertTrue(y.contains("password: 0123456789abcdef0123456789abcdef"))
        assertTrue(y.contains("cert: false"))
        assertTrue(y.contains("disable-telemetry: true"))
        assertTrue(y.contains("disable-update-check: true"))
        assertTrue(y.contains("disable-workspace-trust: true"))
        // Nothing may open the server to the network: no 0.0.0.0, no wildcard, no "auth: none".
        assertTrue("must not bind all interfaces", !y.contains("0.0.0.0"))
        assertTrue("must not disable auth", !y.contains("auth: none"))
        assertTrue("must not enable https (needs certs we do not have)", !y.contains("cert: true"))
        for (l in lines) assertTrue("no other host may appear: $l", !l.contains("::") || l.startsWith("bind-addr: 127.0.0.1:"))
    }

    @Test
    fun passwordsAreFreshRandomHexEveryStart() {
        val a = Phase3Server.newPassword()
        val b = Phase3Server.newPassword()
        assertEquals(32, a.length)
        assertTrue("hex only: $a", Regex("^[0-9a-f]{32}$").matches(a))
        assertNotEquals("two starts must not share a password", a, b)
        // Note: a *seeded* SecureRandom is still not deterministic on the JVM's default
        // provider (its seed is supplemental, system entropy is mixed in), so the only
        // guarantee unit-testable here is the shape and the freshness above.
    }
}
