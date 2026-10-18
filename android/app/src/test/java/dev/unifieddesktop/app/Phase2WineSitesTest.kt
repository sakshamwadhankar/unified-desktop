package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wine 11.19 byte-site table (round 5). The final sha256 of the patched file
 * is pinned in Phase2Wine and verified on the phone; these tests guard the table
 * itself: every site must be length-preserving (the file is rewritten in place)
 * and the B-026 F1 SUD site must flip exactly the store's immediate byte 0 -> 1
 * without moving the destination (syscall_dispatch_enabled's address).
 */
class Phase2WineSitesTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun siteCountAndLengthsFor1119() {
        val (client, server) = Phase2Wine.wineSitesFor("11.19")
        assertEquals(2, client.size) // F1 SUD site removed in round 5 (B-026)
        assertEquals(1, server.size)
        (client + server).forEach { s ->
            assertEquals("site '${s.note}' must be length-preserving", s.old.size, s.next.size)
            assertNotEquals(0, s.old.size)
        }
    }

    @Test fun sudSiteFlipsExactlyTheStoreImmediate() {
        val s = Phase2Wine.sudKeepEnabledSite()
        assertEquals("c70554cf250000000000", hex(s.old))
        assertEquals("c70554cf250001000000", hex(s.next))
        // c7 05 [disp32 = 54 cf 25 00] [imm32 = 00 00 00 00]: only imm byte 0 changes 0 -> 1
        val diffs = s.old.indices.filter { s.old[it] != s.next[it] }
        assertEquals(listOf(6), diffs)
    }

    @Test fun existingSitesUnchanged() {
        val (client, _) = Phase2Wine.wineSitesFor("11.19")
        assertTrue(client.any { hex(it.old) == "f7c6f80f00000f85" && hex(it.next) == "f7c6000000000f85" })
    }
}
