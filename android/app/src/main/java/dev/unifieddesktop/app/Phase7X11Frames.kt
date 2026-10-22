package dev.unifieddesktop.app

/**
 * Pure halves of the X11 frame-loop change measured on 2026-10-23 (Phase 12, round 2).
 *
 * The X11 loop used to push the Xvfb root into the shell view every 120 ms even when the
 * picture was identical. Each push became a video frame, so a key flash from a Wine app
 * queued behind one of those frames: Notepad++ typing measured 105-109 ms with
 * `encoded>pc_rx` 35 ms, while the same build typed into a window with no X11 frame at
 * 5 ms. Only a picture that changed can show new input, so unchanged grabs are skipped and
 * the loop polls fast for a short window after a key so the typed echo is not waiting for
 * the next idle tick.
 */

/** True when [now] is byte-identical to [prev]; a null [prev] means "no previous picture". */
internal fun x11ImageUnchanged(prev: ByteArray?, now: ByteArray): Boolean =
    prev != null && prev.contentEquals(now)

/** Grab period: 8 ms for 300 ms after a key (echo), 120 ms when idle. */
internal fun x11PollDelayMs(sinceKeyMs: Long): Int = if (sinceKeyMs < 300) 8 else 120
