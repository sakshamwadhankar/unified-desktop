package dev.unifieddesktop.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * One Phase-5 session side of the link: video out on CH_VIDEO, input in on CH_INPUT,
 * control messages on CH_CONTROL. Created by Phase4LinkService the moment a session is
 * paired (the channel gate) and torn down with it.
 *
 * Rules honoured here (ALGORITHMS 4, LESSONS 3):
 *  - the sender never blocks: if more than [MAX_QUEUED_VIDEO] frames already wait, the
 *    frame is dropped, the encoder is asked for a keyframe, and frames keep being dropped
 *    until that keyframe arrives;
 *  - key and button press/release events are never dropped, only pointer moves coalesce;
 *  - every key/button the PC holds is remembered so release-all (and the session end)
 *    leaves nothing down.
 */
class Phase5Session(
    private val service: Phase4LinkService,
    private val out: OutputStream,
    private val say: (String) -> Unit,
) {
    companion object {
        const val TAG = "Phase5"
        const val MAX_QUEUED_VIDEO = 2
        const val KEYFRAME_REQUEST_MIN_MS = 400L
        const val STATS_INTERVAL_MS = 1000L
    }

    val writer = Phase4Protocol.PriorityWriter(out)
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val draining = AtomicBoolean(false)
    private var drainThread: Thread? = null
    private var statsThread: Thread? = null
    private var encoder: Phase5Encoder? = null
    private var desktop: Phase5Desktop? = null

    private val dropped = AtomicInteger(0)
    private val sentFrames = AtomicInteger(0)
    private val sentBytes = AtomicLong(0)
    private val keyframeRequests = AtomicInteger(0)
    private val inputApplied = AtomicInteger(0)
    private val inputDropped = AtomicInteger(0)

    @Volatile private var dropping = false
    @Volatile private var lastKeyframeRequestMs = 0L
    private val videoGate = AtomicBoolean(false)

    /** Latency probe for the current key-down (all µs on the PHONE clock). */
    private class Probe(val tPcUs: Long, val tRecvUs: Long) {
        @Volatile var tDispatchUs = 0L
        @Volatile var tDrawUs = 0L
        @Volatile var done = false
    }
    private val probe = AtomicReference<Probe?>(null)

    // ---------------------------------------------------------------- lifecycle

    fun start(enableVideo: Boolean): Boolean {
        if (!running.compareAndSet(false, true)) return false
        draining.set(true)
        drainThread = Thread({ drainLoop() }, "p5-drain").also { it.start() }
        statsThread = Thread({ statsLoop() }, "p5-stats").also { it.start() }
        say("P5 start video=$enableVideo")
        if (!enableVideo) return true
        return enableVideo("start")
    }

    /**
     * Bring up the desktop + encoder (B-047). Called the moment the PC opens the gate with
     * `video-on`, or immediately for the app's own self-test. Idempotent and safe from any
     * thread; returns false when video is refused or fails to come up.
     */
    fun enableVideo(why: String): Boolean {
        if (!running.get()) { say("P5 video-on refused (session down) $why"); return false }
        if (!videoGate.compareAndSet(false, true)) {
            say("P5 video-on already on ($why)")
            return true
        }
        val a: Activity? = Phase5Host.activity()
        if (a == null) {
            say("P5 video DISABLED: no resumed Activity to host the Presentation")
            videoGate.set(false)
            return false
        }
        val enc = Phase5Encoder(Phase5Desktop.W, Phase5Desktop.H, object : Phase5Encoder.Listener {
            override fun onAccessUnit(flags: Byte, seq: Int, ptsUs: Long, encodeDoneNs: Long, annexB: ByteArray) =
                onVideo(flags, seq, ptsUs, encodeDoneNs, annexB)
            override fun onQueuePressure() = requestKeyframe("queue pressure")
        })
        encoder = enc
        if (!enc.prepare()) {
            say("P5 encoder prepare FAILED")
            enc.stop()
            encoder = null
            videoGate.set(false)
            return false
        }
        val st = enc.surfaceTexture
        if (st == null) {
            say("P5 encoder has no input surface")
            videoGate.set(false)
            return false
        }
        val d = Phase5Desktop(a)
        val started = AtomicBoolean(false)
        if (!d.createDisplay(android.view.Surface(st))) {
            say("P5 virtual display FAILED to come up")
            enc.stop(); encoder = null
            videoGate.set(false)
            return false
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            try {
                started.set(d.showPresentation())
            } catch (t: Throwable) {
                say("P5 desktop start threw ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        if (!started.get()) {
            say("P5 desktop FAILED to start")
            enc.stop(); encoder = null
            videoGate.set(false)
            return false
        }
        desktop = d
        // latency probe stage 4: the moment the flash is really drawn (not just dispatched)
        main.post {
            d.setFlashListener { _, ns ->
                val p = probe.get()
                if (p != null && p.tDrawUs == 0L) p.tDrawUs = ns / 1000
            }
        }
        say("P5 desktop up ${Phase5Desktop.W}x${Phase5Desktop.H} encoder=${enc.codecName} ($why)")
        Phase5Dispatch.bind(this)
        // a first keyframe so the PC can start decoding at once
        main.postDelayed({ enc.redrawNow(); enc.requestKeyframe() }, 300)
        return true
    }

    fun stop(why: String) {
        if (!running.compareAndSet(true, false)) return
        say("P5 stop ($why) ${statsLine()}")
        videoGate.set(false)
        draining.set(false)
        try { drainThread?.join(500) } catch (_: Throwable) {}
        drainThread = null
        // release everything the PC still holds before we tear the surface down
        val counts = AtomicReference<Pair<Int, Int>>(Pair(0, 0))
        val l = java.util.concurrent.CountDownLatch(1)
        main.post {
            try { counts.set(desktop?.releaseAll() ?: Pair(0, 0)) } finally { l.countDown() }
        }
        l.await(1, java.util.concurrent.TimeUnit.SECONDS)
        if (counts.get().first != 0 || counts.get().second != 0) {
            say("P5 release-all on stop keys=${counts.get().first} buttons=${counts.get().second}")
        }
        val d = desktop
        desktop = null
        main.post { d?.stop() }
        encoder?.stop()
        encoder = null
        Phase5Dispatch.bind(null)
        try { out.flush() } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------- video out

    private var videoEnabledAt = 0L
    private var lastVstat = 0L

    private fun onVideo(flags: Byte, seq: Int, ptsUs: Long, encodeDoneNs: Long, annexB: ByteArray) {
        if (!running.get()) return
        val isKey = (flags.toInt() and Phase5Protocol.VFLAG_KEYFRAME.toInt()) != 0
        if (dropping) {
            if (isKey) {
                dropping = false
                say("P5 drop-until-keyframe ends at seq=$seq droppedSoFar=${dropped.get()}")
            } else {
                dropped.incrementAndGet()
                return
            }
        }
        if (writer.pendingCount(Phase4Protocol.CH_VIDEO) >= MAX_QUEUED_VIDEO) {
            dropping = true
            dropped.incrementAndGet()
            requestKeyframe("video queue full")
            return
        }
        val msg = Phase5Protocol.packVideoFrame(flags, seq, ptsUs, encodeDoneNs, annexB)
        writer.push(Phase4Protocol.Message.create(Phase4Protocol.CH_VIDEO, msg))
        sentFrames.incrementAndGet()
        sentBytes.addAndGet(annexB.size.toLong())
        // latency probe stage 5: the first frame composed after the flash was drawn. Both
        // times are phone-clock microseconds (pts comes from System.nanoTime on the GL
        // thread); the match can be one frame early if that frame's buffer was queued just
        // before the flash draw.
        val p = probe.get()
        if (p != null && !p.done && p.tDrawUs > 0 && ptsUs >= p.tDrawUs) {
            p.done = true
            sendLatency(p, encodeDoneNs / 1000, seq)
        }
        if (videoEnabledAt == 0L) videoEnabledAt = SystemClock.elapsedRealtime()
    }

    private fun sendLatency(p: Probe, tEncUs: Long, seq: Int) {
        val line = "lat ${p.tPcUs} ${p.tRecvUs} ${p.tDispatchUs} ${p.tDrawUs} $tEncUs $seq"
        writer.push(Phase4Protocol.Message.create(Phase4Protocol.CH_CONTROL, line.toByteArray()))
        say("P5 LAT $line")
    }

    private fun requestKeyframe(why: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyframeRequestMs < KEYFRAME_REQUEST_MIN_MS) return
        lastKeyframeRequestMs = now
        keyframeRequests.incrementAndGet()
        say("P5 keyframe request ($why)")
        encoder?.requestKeyframe()
    }

    // ---------------------------------------------------------------- input in

    fun onInputMessage(payload: ByteArray) {
        val tRecvUs = System.nanoTime() / 1000
        val events = try {
            Phase5Protocol.parseInputEvents(payload)
        } catch (t: Throwable) {
            inputDropped.incrementAndGet()
            say("P5 input parse failed ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        for (e in events) {
            when (e) {
                is Phase5Protocol.InputEvent.Move -> {
                    inputApplied.incrementAndGet()
                    main.post { desktop?.setCursor(e.x, e.y) ?: Unit }
                    // No forced redraw here: the view has not re-rendered yet, so a redraw would only
                    // encode a duplicate of the old picture and fill the encoder queue (measured lag).
                }
                is Phase5Protocol.InputEvent.Button -> {
                    inputApplied.incrementAndGet()
                    main.post { desktop?.pointerButton(e.button, e.down, e.x, e.y) ?: Unit }
                }
                is Phase5Protocol.InputEvent.Wheel -> {
                    inputApplied.incrementAndGet()
                    main.post { desktop?.wheel(e.delta, e.x, e.y) ?: Unit }
                }
                is Phase5Protocol.InputEvent.Key -> {
                    inputApplied.incrementAndGet()
                    if (e.down) {
                        val pr = Probe(e.tPcUs, tRecvUs)
                        probe.set(pr)
                        main.post {
                            pr.tDispatchUs = System.nanoTime() / 1000
                            val handled = desktop?.key(true, Phase5Protocol.vkToKeycode(e.vk), e.scan, Phase5Protocol.modsToMetaState(e.mods)) ?: false
                            if (!handled && Phase5Protocol.vkToKeycode(e.vk) != 0) {
                                say("P5 key vk=${e.vk} code=${Phase5Protocol.vkToKeycode(e.vk)} NOT handled by the view")
                            }
                        }
                    } else {
                        main.post {
                            desktop?.key(false, Phase5Protocol.vkToKeycode(e.vk), e.scan, Phase5Protocol.modsToMetaState(e.mods))
                            val txt = desktop?.inputText() ?: ""
                            say("P5 keyup vk=${e.vk} text=\"${txt.replace("\n", "\\n").take(80)}\"")
                        }
                    }
                }
                Phase5Protocol.InputEvent.ReleaseAll -> {
                    inputApplied.incrementAndGet()
                    requestReleaseAll("PC release-all")
                }
            }
        }
    }

    private fun requestReleaseAll(why: String) {
        say("P5 release-all requested ($why)")
        val counts = AtomicReference<Pair<Int, Int>>(Pair(0, 0))
        val l = java.util.concurrent.CountDownLatch(1)
        main.post {
            try { counts.set(desktop?.releaseAll() ?: Pair(0, 0)) } finally { l.countDown() }
        }
        try { l.await(1, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) {}
        say("P5 release-all done keys=${counts.get().first} buttons=${counts.get().second}")
        encoder?.redrawNow()
    }

    // ---------------------------------------------------------------- control in

    fun onControlMessage(text: String, gateOpen: Boolean = true) {
        when (val c = Phase5Protocol.parseControl(text)) {
            Phase5Protocol.Control.NeedKeyframe -> {
                say("P5 PC asked for a keyframe (link loss)")
                encoder?.requestKeyframe()
            }
            Phase5Protocol.Control.ReleaseAll -> requestReleaseAll("PC release-all message")
            Phase5Protocol.Control.VideoOn -> {
                // B-047: only a PC past the channel gate may start video.
                if (!gateOpen) {
                    say("P5 video-on refused (gate not open)")
                } else {
                    say("P5 video-on received")
                    enableVideo("video-on")
                }
            }
            is Phase5Protocol.Control.ClockSyncRequest -> {
                val tPhone = System.nanoTime() / 1000
                writer.push(
                    Phase4Protocol.Message.create(
                        Phase4Protocol.CH_CONTROL, "csr ${c.pcStamp} $tPhone".toByteArray()
                    )
                )
            }
            is Phase5Protocol.Control.PcVideoStats -> say("P5 PCVID ${c.text}")
            is Phase5Protocol.Control.Unknown -> say("P5 control unknown \"${c.text.take(40)}\"")
        }
    }

    // ---------------------------------------------------------------- threads

    private fun drainLoop() {
        var wrote = false
        while (draining.get()) {
            val n = try { writer.emitOne() } catch (t: Throwable) {
                say("P5 drain write failed ${t.javaClass.simpleName}")
                break
            }
            if (n == null) {
                if (wrote) {
                    try { out.flush() } catch (_: Throwable) {}
                    wrote = false
                }
                try { Thread.sleep(1) } catch (_: InterruptedException) { break }
            } else wrote = true
        }
    }

    private fun statsLoop() {
        while (running.get()) {
            try { Thread.sleep(STATS_INTERVAL_MS) } catch (_: InterruptedException) { break }
            if (!running.get()) break
            val now = SystemClock.elapsedRealtime()
            if (now - lastVstat >= STATS_INTERVAL_MS) {
                lastVstat = now
                say("P5 VSTAT ${statsLine()}")
            }
        }
    }

    fun statsLine(): String {
        val d = desktop
        val stats = d?.stats() ?: "shell=down"
        return "sent=${sentFrames.get()} bytes=${sentBytes.get()} dropped=${dropped.get()} " +
            "keyreq=${keyframeRequests.get()} keyreqSent=${encoder?.stats() ?: ""} " +
            "inApplied=${inputApplied.get()} inDropped=${inputDropped.get()} $stats"
    }

    /** The live shell, for the Phase-6 harness bridge. */
    fun shell(): ShellRoot? = desktop?.shell()

    /** Harness readback (`p5text`): the EditText's real content plus the counters. */
    fun debugText(): String {
        val ref = java.util.concurrent.atomic.AtomicReference("(desktop down)")
        val l = java.util.concurrent.CountDownLatch(1)
        main.post {
            try {
                val d = desktop
                ref.set("text=\"${(d?.inputText() ?: "").replace("\n", "\\n").take(200)}\" ${d?.stats() ?: ""}")
            } finally { l.countDown() }
        }
        try { l.await(1, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) {}
        return "P5 ${ref.get()} sent=${sentFrames.get()} dropped=${dropped.get()} inApplied=${inputApplied.get()}"
    }
}
