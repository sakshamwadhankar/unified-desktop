package dev.unifieddesktop.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision logic of the fontconfig stub staging (B-022 fix, item 8 wiring):
 * ensureFontconfigStub() may only consider the staged lib effective when it is
 * a real file (never the Debian symlink chain) whose digest matches the APK
 * asset; everything else must be re-staged.
 */
class Phase2DataFontconfigStubTest {
    private val stubSha = Phase2Data.FONTCONFIG_STUB_SHA256
    private val realSha = "f".repeat(64) // stands in for the Debian libfontconfig.so.1.12.0

    @Test fun stubRegularFileWithMatchingShaIsEffective() {
        assertTrue(Phase2Data.fcStubIsEffective(true, stubSha))
    }

    @Test fun regularFileWithOtherShaIsNotEffective() {
        assertFalse(Phase2Data.fcStubIsEffective(true, realSha))
        assertFalse(Phase2Data.fcStubIsEffective(true, null))
    }

    @Test fun symlinkIsNeverEffectiveEvenWithStubSha() {
        assertFalse(Phase2Data.fcStubIsEffective(false, stubSha))
        assertFalse(Phase2Data.fcStubIsEffective(false, null))
    }
}
