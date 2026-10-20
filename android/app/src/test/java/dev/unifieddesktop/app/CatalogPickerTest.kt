package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The D7 picker tests. [section7Table] is the real gate: the 11 names from section 7 of
 * docs/PHASE8_EXPECTED.md, read from the shipped assets/app_catalog.json with the same
 * CatalogLoader the app uses, checked for kind and rating. The synthetic table then covers
 * the ordering rules themselves. Ratings describe how the app runs on the phone, not whether
 * an installer exists.
 */
class CatalogPickerTest {

    private fun v(version: String, kind: String, rating: String) =
        CatalogVersion(version = version, kind = kind, rating = rating)

    private fun app(vararg versions: CatalogVersion) =
        CatalogApp(id = "x", name = "X", versions = versions.toList())

    private fun catalogFile(): File {
        val candidates = listOf(
            File("src/main/assets/app_catalog.json"),
            File("app/src/main/assets/app_catalog.json"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("app_catalog.json not found from ${File(".").absolutePath}")
    }

    @Test
    fun section7Table() {
        val apps = CatalogLoader.parse(catalogFile().readText())
        val expected = listOf(
            "VS Code" to "builtin",
            "Google Tasks" to "android",
            "Zoom" to "android",
            "Excalidraw" to "web",
            "Photopea" to "web",
            "xeyes" to "linux",
            "xcalc" to "linux",
            "Notepad++" to "windows",
            "7-Zip" to "windows",
            "Minesweeper" to "windows",
            "Paint.NET" to "Not yet",
        )
        var passed = 0
        for ((name, want) in expected) {
            val app = apps.firstOrNull { it.name.equals(name, ignoreCase = true) }
            assertNotNull("section 7 app missing from the catalog: $name", app)
            val pick = Catalog.pick(app!!)
            assertNotNull("$name has no version", pick)
            if (want == "Not yet") {
                assertEquals("$name rating", Catalog.NOT_YET, pick!!.rating)
                assertTrue("$name needs a real reason", pick.note.isNotBlank())
                assertNull("$name must not be installable", Catalog.pickUsable(app))
            } else {
                assertEquals("$name kind", want, pick!!.kind)
                assertEquals("$name rating", Catalog.WORKS, pick.rating)
                assertNotNull("$name must be offered", Catalog.pickUsable(app))
            }
            println("SECTION7 ${passed + 1}/11 $name -> ${pick.kind} ${pick.rating}")
            passed++
        }
        assertEquals("section 7 rows passed", 11, passed)
        println("SECTION7 $passed/11 PASS")
    }

    private data class Row(val label: String, val expected: String?, val versions: List<CatalogVersion>)

    @Test
    fun syntheticPickerRows() {
        val rows = listOf(
            Row("1 builtin Works beats android Works", "builtin:1.0",
                listOf(v("1.0", "android", "Works"), v("1.0", "builtin", "Works"))),
            Row("2 android Works beats web Works", "android:1.0",
                listOf(v("1.0", "web", "Works"), v("1.0", "android", "Works"))),
            Row("3 web Works beats linux Works", "web:1.0",
                listOf(v("1.0", "linux", "Works"), v("1.0", "web", "Works"))),
            Row("4 linux Works beats windows Works", "linux:1.0",
                listOf(v("1.0", "windows", "Works"), v("1.0", "linux", "Works"))),
            Row("5 a better rating wins over a better kind", "windows:8.9.8.1",
                listOf(v("1.0", "builtin", "Works, slow"), v("8.9.8.1", "windows", "Works"))),
            Row("6 Not yet never beats Works across kinds", "android:2026.10",
                listOf(v("1.0", "builtin", "Not yet"), v("2026.10", "android", "Works"))),
            Row("7 all Not yet: best kind, but not usable", "builtin:1.0",
                listOf(v("1.0", "windows", "Not yet"), v("1.0", "builtin", "Not yet"))),
            Row("8 Works beats Works, slow in the same kind", "web:4.95",
                listOf(v("4.95", "web", "Works, slow"), v("4.95", "web", "Works"))),
            Row("9 same kind and rating: 8.10 beats 8.9.8.1", "windows:8.10",
                listOf(v("8.9.8.1", "windows", "Works"), v("8.10", "windows", "Works"))),
            Row("10 one version: picked", "linux:1.0", listOf(v("1.0", "linux", "Works"))),
            Row("11 empty catalog entry has no version", null, emptyList()),
        )
        for (row in rows) {
            val picked = Catalog.pick(app(*row.versions.toTypedArray()))
            if (row.expected == null) {
                assertNull(row.label, picked)
            } else {
                assertEquals(row.label, row.expected, "${picked?.kind}:${picked?.version}")
            }
            if (row.versions.isNotEmpty() && row.versions.all { it.rating == "Not yet" }) {
                assertNull(row.label + " (not installable)", Catalog.pickUsable(app(*row.versions.toTypedArray())))
            }
        }
    }

    @Test
    fun searchMatchesNamesAndAliasesCaseInsensitively() {
        val apps = listOf(
            CatalogApp("notepadpp", "Notepad++", listOf("npp", "notepad")),
            CatalogApp("tasks", "Google Tasks", listOf("todo")),
            CatalogApp("files", "Files"),
        )
        assertEquals(listOf("Notepad++"), Catalog.search(apps, "NPP").map { it.name })
        assertEquals(listOf("Google Tasks", "Notepad++"), Catalog.search(apps, "o").map { it.name }.sorted())
        assertEquals(listOf("Notepad++", "Google Tasks", "Files"), Catalog.search(apps, "  ").map { it.name })
        assertTrue(Catalog.search(apps, "zz").isEmpty())
    }

    @Test
    fun versionCompareIsNumeric() {
        assertTrue(Catalog.compareVersions("8.10", "8.9.8.1") > 0)
        assertTrue(Catalog.compareVersions("4.95a", "4.95") > 0)
        assertEquals(0, Catalog.compareVersions("1.0", "1.0"))
    }
}
