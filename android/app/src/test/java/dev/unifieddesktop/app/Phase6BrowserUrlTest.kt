package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** B-060: the address bar only adds a scheme on Go, and only when the text has none. */
class Phase6BrowserUrlTest {

    @Test
    fun aBareHostGetsHttpsOnlyOnGo() {
        assertEquals("https://example.com", BrowserUrl.normalize("example.com"))
        assertEquals("https://example.com/path", BrowserUrl.normalize("  example.com/path  "))
    }

    @Test
    fun anExistingSchemeIsKept() {
        assertEquals("http://127.0.0.1:44093/?folder=/a", BrowserUrl.normalize("http://127.0.0.1:44093/?folder=/a"))
        assertEquals("https://example.com/", BrowserUrl.normalize("https://example.com/"))
        assertEquals("about:blank", BrowserUrl.normalize("about:blank"))
    }

    @Test
    fun emptyTextStaysEmpty() {
        assertEquals("", BrowserUrl.normalize(""))
        assertEquals("", BrowserUrl.normalize("   "))
    }

    @Test
    fun plainWordsBecomeAGoogleSearch() {
        assertEquals(
            "https://www.google.com/search?q=unified+desktop",
            BrowserUrl.resolve("unified desktop")
        )
        assertEquals("https://www.google.com/search?q=cats", BrowserUrl.resolve("cats"))
    }

    @Test
    fun hostsAndSchemesStillLoadDirectly() {
        assertEquals("https://example.com/a", BrowserUrl.resolve("example.com/a"))
        assertEquals("http://127.0.0.1:44093/", BrowserUrl.resolve("http://127.0.0.1:44093/"))
        assertEquals("about:blank", BrowserUrl.resolve("about:blank"))
        assertEquals("ud:camtest", BrowserUrl.resolve("ud:camtest"))
    }

    @Test
    fun downloadNameUsesTheServerNameOrTheUrl() {
        assertEquals("report.pdf", BrowserDownloads.fileName("attachment; filename=\"report.pdf\"", "https://x/y"))
        assertEquals("fallback.txt", BrowserDownloads.fileName(null, "https://x/fallback.txt?q=1"))
        assertEquals("download.bin", BrowserDownloads.fileName(null, "https://x/"))
    }
}
