package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-074 / B-075: the two retry gates. The X11 window must wait 60 s for Xvfb (500 ms steps)
 * and reconnect after a later loss; the VS Code window must start code-server when its session
 * is missing/unhealthy and load the page as soon as the port answers.
 */
class Phase7RetryPolicyTest {

    @Test
    fun x11RetriesForSixtySecondsInHalfSecondSteps() {
        assertEquals(120, X11ConnectPolicy.maxAttempts())
        assertTrue(X11ConnectPolicy.shouldRetry(startedMs = 0, nowMs = 0))
        assertTrue(X11ConnectPolicy.shouldRetry(startedMs = 0, nowMs = 59_999))
        assertFalse(X11ConnectPolicy.shouldRetry(startedMs = 0, nowMs = 60_000))
        assertFalse(X11ConnectPolicy.shouldRetry(startedMs = 0, nowMs = 60_001))
    }

    @Test
    fun x11RetryWindowIsMeasuredFromTheLatestConnectionAttempt() {
        // A reconnect after a failure starts a fresh 60 s window, not a shared deadline.
        assertTrue(X11ConnectPolicy.shouldRetry(startedMs = 10_000, nowMs = 65_000))
        assertFalse(X11ConnectPolicy.shouldRetry(startedMs = 10_000, nowMs = 70_000))
    }

    @Test
    fun codeServerStartsWhenThereIsNoUsableSession() {
        assertTrue(CodeServerGate.needsStart(sessionUsable = false, healthCode = 0))
        assertTrue(CodeServerGate.needsStart(sessionUsable = false, healthCode = 200))
        assertTrue(CodeServerGate.needsStart(sessionUsable = true, healthCode = 0))
        assertTrue(CodeServerGate.needsStart(sessionUsable = true, healthCode = 500))
        assertFalse(CodeServerGate.needsStart(sessionUsable = true, healthCode = 200))
    }

    @Test
    fun codeServerWaitIsNinetySeconds() {
        assertEquals(90_000L, CodeServerGate.MAX_MS)
        assertEquals(500L, CodeServerGate.POLL_MS)
        assertFalse(CodeServerGate.expired(startedMs = 0, nowMs = 89_999))
        assertTrue(CodeServerGate.expired(startedMs = 0, nowMs = 90_000))
    }
}
