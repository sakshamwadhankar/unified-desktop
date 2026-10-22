package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item-6 unit tests. Only the pure parts of the server are testable off-device: the code
 * server config text, the password generator and the login-cookie parser. Binding,
 * /healthz, the login POST and the process work are verified on the device and land in
 * files/phase3/p3.log.
 */
class Phase3ServerTest {

    private val pw = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun configBindsLoopbackOnlyWithPasswordAuth() {
        val y = Phase3Server.configYaml(34567, pw)
        val lines = y.lines().filter { it.isNotBlank() }
        assertEquals("bind-addr: 127.0.0.1:34567", lines.first { it.startsWith("bind-addr") })
        // B-108: no unauthenticated code-server on shared loopback, ever.
        assertTrue(y.contains("auth: password"))
        assertTrue("auth none must never come back", !y.contains("auth: none"))
        assertEquals("password: $pw", lines.first { it.startsWith("password:") })
        assertTrue(y.contains("cert: false"))
        assertTrue(y.contains("disable-telemetry: true"))
        assertTrue(y.contains("disable-update-check: true"))
        assertTrue(y.contains("disable-workspace-trust: true"))
        assertTrue("must not bind all interfaces", !y.contains("0.0.0.0"))
        assertTrue("must not enable https (needs certs we do not have)", !y.contains("cert: true"))
        for (l in lines) assertTrue("no other host may appear: $l", !l.contains("::") || l.startsWith("bind-addr: 127.0.0.1:"))
    }

    @Test
    fun passwordsAreFreshRandomHexEveryStart() {
        val a = Phase3Server.newPassword()
        val b = Phase3Server.newPassword()
        // B-108: 32 random bytes (256 bits) per start, never a short token.
        assertEquals(64, a.length)
        assertTrue("hex only: $a", Regex("^[0-9a-f]{64}$").matches(a))
        assertNotEquals("two starts must not share a password", a, b)
        // Note: a *seeded* SecureRandom is still not deterministic on the JVM's default
        // provider (its seed is supplemental, system entropy is mixed in), so the only
        // guarantee unit-testable here is the shape and the freshness above.
    }

    @Test
    fun loginCookieIsParsedFromTheSetCookieHeader() {
        // The exact shape code-server 4.141 sends (B-106 round-4 curl capture).
        val one = listOf("code-server-session=abc123; Path=/; HttpOnly; SameSite=Lax")
        assertEquals("abc123", Phase3Server.sessionCookie(one))
    }

    @Test
    fun loginCookieParserSkipsOtherCookiesAndAttributes() {
        val many = listOf(
            "other=1; Path=/",
            "theme=dark; Path=/; Max-Age=60, code-server-session=xyz.789; Path=/; HttpOnly"
        )
        assertEquals("xyz.789", Phase3Server.sessionCookie(many))
        assertNull(Phase3Server.sessionCookie(listOf("other=1; Path=/")))
        assertNull(Phase3Server.sessionCookie(emptyList()))
    }
}
