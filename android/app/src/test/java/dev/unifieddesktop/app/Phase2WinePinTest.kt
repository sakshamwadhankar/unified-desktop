package dev.unifieddesktop.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 3: guards the switchable Wine pin and its byte sites.
 *
 * The site bytes were located in the pinned tarballs on the laptop
 * (2026-10-17) and re-checked by unique-occurrence guards at patch time on the
 * phone; these tests freeze them so a silent edit of the strings cannot ship.
 */
class Phase2WinePinTest {

    @Test fun pinsCoverTheRoundThreeCandidates() {
        assertEquals(listOf("11.19", "9.0", "10.0", "11.0"), Phase2Data.WINE_PINS.map { it.id })
        assertEquals("11.19", Phase2Data.DEFAULT_WINE_PIN)
        for (p in Phase2Data.WINE_PINS) {
            assertEquals(64, p.sha256.length)
            assertTrue(p.url.startsWith("https://github.com/Kron4ek/Wine-Builds/releases/download/"))
            assertTrue(p.url.endsWith(".tar.xz"))
            assertTrue("dir of ${p.id} must match the tarball payload", p.url.contains("/${p.dir}.tar.xz"))
            assertTrue(p.loaderRel.isNotEmpty())
        }
    }

    @Test fun unknownOrMissingPinFallsBackToTheDefault() {
        assertEquals("11.19", Phase2Data.pickPin(null).id)
        assertEquals("11.19", Phase2Data.pickPin("").id)
        assertEquals("11.19", Phase2Data.pickPin("  ").id)
        assertEquals("11.19", Phase2Data.pickPin("bogus").id)
        assertEquals("9.0", Phase2Data.pickPin("9.0").id)
        assertEquals("10.0", Phase2Data.pickPin("10.0").id)
        assertEquals("11.0", Phase2Data.pickPin("11.0").id)
    }

    @Test fun nineSitesRewriteTmpPathAndDropTheAssertSites() {
        val (client, server) = Phase2Wine.wineSitesFor("9.0")
        assertEquals(1, client.size)
        assertEquals(1, server.size)
        validate(client[0], "/tmp/.wine-%u/server-%s")
        validate(server[0], "/tmp/.wine-%u")
    }

    @Test fun tenSitesUseTheDevInoClientString() {
        val (client, server) = Phase2Wine.wineSitesFor("10.0")
        assertEquals(1, client.size)
        assertEquals(1, server.size)
        validate(client[0], "/tmp/.wine-%u/server-%llx-%llx")
        validate(server[0], "/tmp/.wine-%u")
    }

    @Test fun elevenSitesAddBoth39BitAssertGuards() {
        val (client, server) = Phase2Wine.wineSitesFor("11.0")
        assertEquals(3, client.size)
        assertEquals(1, server.size)
        validate(client[client.size - 1], "/tmp/.wine-%u/server-%llx-%llx")
        validate(server[0], "/tmp/.wine-%u")
        val vi = client.first { it.note.contains("virtual_init") }
        assertArrayEquals(Phase2WineTestUtil.hex("f7c6ff0f00000f8589000000"), vi.old)
        assertArrayEquals(Phase2WineTestUtil.hex("f7c6000000000f8589000000"), vi.next)
        val am = client.first { it.note.contains("anon_mmap_alloc") }
        assertArrayEquals(Phase2WineTestUtil.hex("f7c7ff0f0000751a89f2"), am.old)
        assertArrayEquals(Phase2WineTestUtil.hex("f7c700000000751a89f2"), am.next)
    }

    @Test fun elevenNineteenKeepsTheRoundTwoSites() {
        val (client, server) = Phase2Wine.wineSitesFor("11.19")
        assertEquals(2, client.size)          // round 2/3 sites; F1 SUD site removed (B-026)
        assertEquals(1, server.size)
        val assertSite = client.first { it.note.contains("0xff8") }
        assertArrayEquals(Phase2WineTestUtil.hex("f7c6f80f00000f85"), assertSite.old)
        assertArrayEquals(Phase2WineTestUtil.hex("f7c6000000000f85"), assertSite.next)
        validate(client.first { it.note.contains("prefix-relative") }, "/tmp/.wine-%u/server-%llx-%llx")
        validate(server[0], "/tmp/.wine-%u")
        assertTrue(client.none { it.note.contains("SUD: keep") })
    }

    /** old = the full literal, next = "/tmp/" dropped, tail NUL-padded, equal length. */
    private fun validate(site: Phase2Wine.Site, oldStr: String) {
        assertArrayEquals(oldStr.toByteArray(Charsets.UTF_8), site.old)
        assertEquals(site.old.size, site.next.size)
        val expect = oldStr.removePrefix("/tmp/")
        assertEquals(expect, String(site.next, 0, expect.length, Charsets.UTF_8))
        for (i in expect.length until site.next.size) assertEquals(0, site.next[i].toInt())
        assertEquals(5, site.next.size - expect.length)
    }
}

object Phase2WineTestUtil {
    fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] =
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        return out
    }
}
