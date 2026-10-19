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
}
