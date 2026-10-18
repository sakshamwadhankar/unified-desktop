package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * The pinned code-server .deb stores every path over 100 bytes as a GNU long-name
 * ("@LongLink", type 'L') member followed by a header whose 100-byte name field holds
 * only the truncated prefix of the real path. Ignoring 'L' silently wrote truncated
 * file names and then died with EISDIR on the deb's "images" directory - measured on
 * the emulator 2026-10-18, see docs/PHASE3_RESULT.md item 5. These tests build the
 * archive bytes by hand so they fail if the reader ever drops 'L'/'K' again.
 */
class TarReaderTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "ud-tar-test-$name-${System.nanoTime()}")
            .apply { mkdirs() }

    // ------------------------------------------------------------------ tar bytes -----

    private fun octal(v: Long, width: Int): ByteArray =
        (v.toString(8).padStart(width - 1, '0') + "\u0000").toByteArray()

    private fun header(name: String, size: Long, type: Char, link: String = ""): ByteArray {
        val b = ByteArray(512)
        fun put(s: ByteArray, off: Int, len: Int) {
            System.arraycopy(s, 0, b, off, minOf(len, s.size))
        }
        put(name.toByteArray(), 0, 100)
        put(octal(420L, 8), 100, 8)   // mode 0644
        put(octal(0L, 8), 108, 8)     // uid
        put(octal(0L, 8), 116, 8)     // gid
        put(octal(size, 12), 124, 12)
        put(octal(1_767_000_000L, 12), 136, 12)
        b[156] = type.code.toByte()
        put(link.toByteArray(), 157, 100)
        put("ustar\u000000".toByteArray(), 257, 8)
        return b
    }

    private fun padded(bytes: ByteArray): ByteArray =
        bytes + ByteArray(((512 - (bytes.size % 512)) % 512))

    /** A GNU long-name ('L') or long-link ('K') member carrying [value]. */
    private fun longMember(type: Char, value: String): ByteArray {
        val payload = (value + "\u0000").toByteArray()
        return header("././@LongLink", payload.size.toLong(), type) + padded(payload)
    }

    private fun endBlocks() = ByteArray(1024)

    private fun archive(vararg parts: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }

    // ---------------------------------------------------------------------- tests -----

    @Test
    fun gnuLongNameIsHonouredAndTheTruncatedNameIsNotWritten() {
        val payload = "./data/data/com.termux/files/usr/lib/code-server/lib/vscode/extensions/" +
            "configuration-editing/images/icon.png"
        assertTrue("fixture must exceed the 100-byte ustar name field", payload.length > 100)
        val truncated = payload.take(100)
        val body = "PNGDATA".toByteArray()
        val dir = tempDir("longname")
        val tar = archive(
            longMember('L', payload),
            header(truncated, body.size.toLong(), '0'),
            padded(body),
            endBlocks(),
        )

        Phase2Data.readTar(ByteArrayInputStream(tar), dir, "test:longname", Phase3Data.CsPolicy())

        val want = File(dir, "lib/vscode/extensions/configuration-editing/images/icon.png")
        assertTrue("the full name must be written: $want", want.isFile)
        assertEquals("PNGDATA", want.readText())
        val files = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toList()
        // The truncated 100-byte name ends in a partial segment (".../images/ic"); with the
        // bug it was written as a *file* there, so the file list must contain only the full path.
        assertEquals(listOf("lib/vscode/extensions/configuration-editing/images/icon.png"), files)
    }

    @Test
    fun gnuLongLinkPayloadBecomesTheSymlinkTarget() {
        val dir = tempDir("longlink")
        val probe = File(dir, "symlink-probe")
        val canLink = try {
            Files.createSymbolicLink(probe.toPath(), java.nio.file.Paths.get("nowhere"))
            true
        } catch (t: Throwable) {
            false
        }
        assumeTrue("platform cannot create symlinks: skipping", canLink)

        val target = "lib/vscode/extensions/averyveryverylongextensionsdirectoryname/" +
            "target-file-with-a-long-name-and-more.js"
        assertTrue("fixture must exceed the 100-byte link field", target.length > 100)
        val linkPath = "data/data/x/link.js"
        val tar = archive(
            longMember('K', target),
            header(linkPath, 0L, '2'),
            endBlocks(),
        )

        Phase2Data.readTar(ByteArrayInputStream(tar), dir, "test:longlink", null)

        val f = File(dir, linkPath)
        assertTrue("symlink must exist: $f", Files.isSymbolicLink(f.toPath()))
        assertEquals(target, Files.readSymbolicLink(f.toPath()).toString())
    }

    @Test
    fun relativeLinkInsideTheTreeIsKeptWithItsVerbatimTarget() {
        val dir = tempDir("rel-link")
        val probe = File(dir, "symlink-probe")
        val canLink = try {
            Files.createSymbolicLink(probe.toPath(), java.nio.file.Paths.get("nowhere"))
            true
        } catch (t: Throwable) {
            false
        }
        assumeTrue("platform cannot create symlinks: skipping", canLink)

        // Exactly the shape of the deb's node_modules/.bin/* links.
        val link = "./data/data/com.termux/files/usr/lib/code-server/lib/vscode/node_modules/.bin/semver"
        val target = "../semver/bin/semver.js"
        val real = File(dir, "lib/vscode/node_modules/semver/bin/semver.js").apply {
            parentFile.mkdirs(); writeText("// real")
        }
        val tar = archive(
            header(link, 0L, '2', target),
            endBlocks(),
        )

        Phase2Data.readTar(ByteArrayInputStream(tar), dir, "test:rel-link", Phase3Data.CsPolicy())

        val f = File(dir, "lib/vscode/node_modules/.bin/semver")
        assertTrue("relative in-tree link must be created: $f", Files.isSymbolicLink(f.toPath()))
        assertEquals(target, Files.readSymbolicLink(f.toPath()).toString())
        assertTrue("and it must resolve to the real file", f.canonicalPath == real.canonicalPath)
    }

    @Test
    fun relativeLinkThatEscapesTheUnpackRootIsDropped() {
        val dir = tempDir("escape")
        val probe = File(dir, "symlink-probe")
        val canLink = try {
            Files.createSymbolicLink(probe.toPath(), java.nio.file.Paths.get("nowhere"))
            true
        } catch (t: Throwable) {
            false
        }
        assumeTrue("platform cannot create symlinks: skipping", canLink)

        val link = "./data/data/com.termux/files/usr/lib/code-server/lib/vscode/escape"
        val tar = archive(
            header(link, 0L, '2', "../../../../../../etc/passwd"),
            endBlocks(),
        )

        Phase2Data.readTar(ByteArrayInputStream(tar), dir, "test:escape", Phase3Data.CsPolicy())

        assertTrue(
            "a link climbing out of the root must not be created",
            !Files.exists(java.nio.file.Paths.get(dir.path, "lib/vscode/escape"), java.nio.file.LinkOption.NOFOLLOW_LINKS),
        )
    }

    @Test
    fun plainShortNamesStillUnpack() {
        val dir = tempDir("short")
        val body = "hello\n".toByteArray()
        val tar = archive(
            header("./data/data/com.termux/files/usr/lib/code-server/lib/vscode/out/main.js", body.size.toLong(), '0'),
            padded(body),
            endBlocks(),
        )

        Phase2Data.readTar(ByteArrayInputStream(tar), dir, "test:short", Phase3Data.CsPolicy())

        assertEquals("hello\n", File(dir, "lib/vscode/out/main.js").readText())
    }

    @Test
    fun keptAndDroppedMembersBothConsumeTheirBytesSoTheNextHeaderStaysAligned() {
        // A dropped ELF-ish member between two kept ones: if the reader mis-consumed it,
        // the third file would come out corrupt or the archive would desync.
        val dir = tempDir("align")
        val a = "first".toByteArray()
        val elf = ByteArray(1536) { 0x7f }             // > 1 block so padding matters
        elf[0] = 0x7f; elf[1] = 'E'.code.toByte(); elf[2] = 'L'.code.toByte(); elf[3] = 'F'.code.toByte()
        val c = "third".toByteArray()
        val tar = archive(
            header("./data/data/com.termux/files/usr/lib/code-server/lib/vscode/a.txt", a.size.toLong(), '0'), padded(a),
            header("./data/data/com.termux/files/usr/lib/code-server/lib/vscode/b/x.node", elf.size.toLong(), '0'), padded(elf),
            header("./data/data/com.termux/files/usr/lib/code-server/lib/vscode/c.txt", c.size.toLong(), '0'), padded(c),
            endBlocks(),
        )

        Phase2Data.readTar(ByteArrayInputStream(tar), dir, "test:align", Phase3Data.CsPolicy())

        assertEquals("first", File(dir, "lib/vscode/a.txt").readText())
        assertEquals("third", File(dir, "lib/vscode/c.txt").readText())
        assertEquals(2, dir.walkTopDown().count { it.isFile })
    }
}
