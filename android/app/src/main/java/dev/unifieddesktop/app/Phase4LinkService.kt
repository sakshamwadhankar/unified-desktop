package dev.unifieddesktop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Phase-4 link foreground service (B-027/B-033/B-035 answer): one session side of the
 * PC link. Owns the Wi-Fi TCP server (port 47600), the TLS 1.3 session with the PC,
 * the 6-digit pairing prompt (notification Yes/No - a real tap on the phone UI), the
 * trusted-PC store, NSD advertising, and the bench echo engine. The AOA accessory mode
 * reuses the same session engine over the accessory streams (item 8).
 */
class Phase4LinkService : Service() {
    companion object {
        const val TAG = "Phase4"
        const val PORT = 47600
        const val NSD_TYPE = "_udlink._tcp."
        const val NSD_NAME = "unified-desktop"

        const val ACTION_START = "dev.unifieddesktop.app.p4.START"
        const val ACTION_STOP = "dev.unifieddesktop.app.p4.STOP"
        const val ACTION_STATUS = "dev.unifieddesktop.app.p4.STATUS"
        const val ACTION_PAIR_YES = "dev.unifieddesktop.app.p4.PAIR_YES"
        const val ACTION_PAIR_NO = "dev.unifieddesktop.app.p4.PAIR_NO"
        const val ACTION_FORGET = "dev.unifieddesktop.app.p4.FORGET"
        const val ACTION_ACCESSORY = "dev.unifieddesktop.app.p4.ACCESSORY"

        /**
         * Phase-5 dev probe: run the desktop+encoder pipeline with a discarding sink, so
         * the video chain can be verified on the phone without a PC attached.
         */
        const val ACTION_P5TEST = "dev.unifieddesktop.app.p5.TEST"

        private val running = AtomicBoolean(false)
        private val lastState = AtomicReference("idle")
        private var serverThread: Thread? = null
        private var serverSocket: SSLServerSocket? = null
        private var nsdManager: NsdManager? = null
        private var nsdListener: NsdManager.RegistrationListener? = null
        private val pendingPairing = AtomicReference<Pairing?>(null)
        private val liveSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<LiveSession>()

        /** Open AOA accessory FD handed over by the USB attach path (manager 21:55). */
        @Volatile private var accessoryPfd: ParcelFileDescriptor? = null

        /** Supervisor thread that force-closes dead session sockets (manager review). */
        private var watchdogThread: Thread? = null

        /** Latest state line, for the harness STATUS action. */
        @Synchronized fun stateLine(): String = lastState.get()

        @Synchronized fun isRunning(): Boolean = running.get()

        /** Live session sockets; the harness `status` action and the stop proof use it. */
        fun liveCount(): Int = liveSessions.size

        /** True while an AOA accessory FD is held (USB attach user path). */
        fun accessoryOpen(): Boolean = accessoryPfd != null
    }

    /**
     * One accepted session socket as seen from outside its thread: the watchdog and
     * stopAll can force-close it so a thread blocked in read()/write() on a dead or
     * stalled link wakes up and logs SESSION_END.
     */
    class LiveSession(val sock: SSLSocket, val peerHex: String) {
        @Volatile var lastInboundMs: Long = SystemClock.elapsedRealtime()

        /** The dead-link rule applies only after the session is paired (item 10 failover). */
        @Volatile var armed = false
        val closed = AtomicBoolean(false)
    }

    class Pairing(val codeShown: String, val clientFprHex: String) {
        val gate = java.util.concurrent.CountDownLatch(1)
        val decision = AtomicReference<Boolean?>(null)
        fun resolve(yes: Boolean) {
            decision.compareAndSet(null, yes)
            gate.countDown()
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAll()
            ACTION_STOP -> stopAll()
            ACTION_STATUS -> say("status running=${running.get()} live=${liveSessions.size} last=$lastState pair=${pendingPairing.get() != null} acc=${accessoryPfd != null}")
            ACTION_PAIR_YES -> {
                // B-044: make the delivery path visible in p4.log
                say("PAIR_YES action received (pending=${pendingPairing.get() != null})")
                pendingPairing.get()?.resolve(true)
            }
            ACTION_PAIR_NO -> {
                say("PAIR_NO action received (pending=${pendingPairing.get() != null})")
                pendingPairing.get()?.resolve(false)
            }
            ACTION_FORGET -> {
                val fpr = intent.getStringExtra("fpr") ?: ""
                val n = if (fpr.isEmpty()) {
                    val rows = Phase4Crypto.trustedList(this)
                    var c = 0
                    for (r in rows) if (Phase4Crypto.trustedForget(this, r.first.take(3))) c++
                    c
                } else if (Phase4Crypto.trustedForget(this, fpr)) 1 else 0
                say("FORGET count=$n prefixOrEmpty=${fpr.take(6)} pins=${Phase4Crypto.trustedList(this).size}")
            }
            ACTION_ACCESSORY -> adoptAccessory(intent)
            ACTION_P5TEST -> Thread({ p5SelfTest(intent.getIntExtra("seconds", 10)) }, "p5-selftest").start()
            else -> say("onStartCommand unknown action=${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    private fun say(line: String) {
        Log.i(TAG, line)
        val f = File("${filesDir}/p4")
        f.mkdirs()
        File(f, "p4.log").appendText("${System.currentTimeMillis()} $line\n")
        lastState.set(line)
    }

    /** AOA hand-off: the attach path (activity or permission receiver) sends the open FD. */
    private fun adoptAccessory(intent: Intent) {
        val fd = intent.getIntExtra("fd", -1)
        if (fd < 0) {
            say("ACC adopt: no fd in intent")
            return
        }
        try { accessoryPfd?.close() } catch (_: Throwable) {}
        accessoryPfd = ParcelFileDescriptor.adoptFd(fd)
        say("ACC adopted fd=$fd m=${intent.getStringExtra("m")} model=${intent.getStringExtra("model")}")
    }

    /**
     * Phase-5 dev probe (ACTION_P5TEST): runs the whole desktop+compositor+encoder path
     * against a discarding sink and reports the counters, so the video chain is verifiable
     * on the real phone without a PC. It never touches the network.
     */
    private fun p5SelfTest(seconds: Int) {
        try {
            say("P5TEST begin seconds=$seconds")
            val sink = object : java.io.OutputStream() {
                override fun write(b: Int) {}
                override fun write(b: ByteArray, off: Int, len: Int) {}
            }
            val s = Phase5Session(this, sink, { line -> say(line) })
            val ok = s.start(true)
            say("P5TEST start ok=$ok")
            Thread.sleep((seconds.coerceIn(1, 120) * 1000).toLong())
            say("P5TEST ${s.statsLine()}")
            s.stop("self-test done")
            say("P5TEST end")
        } catch (t: Throwable) {
            say("P5TEST fail ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ud_p4_pairing", "PC pairing", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel("ud_p4_session", "PC session", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun startAll() {
        ensureIdentity()
        if (!running.compareAndSet(false, true)) {
            say("already running")
            return
        }
        channels()
        serverThread = Thread({ serverLoop() }, "p4-tcp-server").also { it.start() }
        watchdogThread = Thread({ watchdogLoop() }, "p4-watchdog").also { it.start() }
        registerNsd()
        val n = buildNotification(
            "Desktop link running",
            "unified-desktop link service serving on port $PORT",
            false
        )
        startForegroundCompat(n)
        say("START ok port=$PORT")
    }

    private fun stopAll() {
        say("STOP begin")
        running.set(false)
        try { serverSocket?.close() } catch (_: Throwable) {}
        val live = liveSessions.toList()
        for (s in live) forceClose(s, "service stop")
        if (live.isNotEmpty()) say("STOP force-closed ${live.size} live session(s)")
        try { watchdogThread?.interrupt() } catch (_: Throwable) {}
        unregisterNsd()
        pendingPairing.get()?.resolve(false)
        cancelPairingNotification()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------ dead-link watchdog

    /**
     * Force-close one session socket from the outside (watchdog or stopAll). SO_LINGER(0)
     * + shutdown + close so a thread blocked in write() on a stalled link gets an error
     * instead of hanging forever; the session thread then logs SESSION_END.
     */
    private fun forceClose(s: LiveSession, why: String) {
        if (!s.closed.compareAndSet(false, true)) return
        say("SESSION force-close ($why) peer=${s.peerHex}")
        try { s.sock.setSoLinger(true, 0) } catch (_: Throwable) {}
        try { s.sock.shutdownInput() } catch (_: Throwable) {}
        try { s.sock.shutdownOutput() } catch (_: Throwable) {}
        try { s.sock.close() } catch (_: Throwable) {}
    }

    /** One supervisor for all live sessions: nothing inbound for 15 s -> dead link. */
    private fun watchdogLoop() {
        while (running.get()) {
            try {
                val now = SystemClock.elapsedRealtime()
                for (s in liveSessions) {
                    if (s.armed && Phase4Protocol.watchdogExpired(s.lastInboundMs, now)) {
                        forceClose(s, "no inbound for ${Phase4Protocol.DEAD_TIMEOUT_MS / 1000} s")
                    }
                }
            } catch (_: Throwable) {}
            try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
        }
    }

    private fun startForegroundCompat(n: Notification) {
        try {
            startForeground(47600, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            say("FGS type connectedDevice")
        } catch (t: Throwable) {
            // Some phones refuse connectedDevice without an attached device. The session
            // itself is the Phase-4 requirement (B-027); dataSync keeps it alive on those.
            Log.w(TAG, "FGS connectedDevice failed: $t")
            try {
                startForeground(47600, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                say("FGS fallback dataSync (${t.javaClass.simpleName})")
            } catch (t2: Throwable) {
                say("FGS FAILED both: ${t2.javaClass.simpleName}")
                throw t2
            }
        }
    }

    // ------------------------------------------------------------ identity

    private fun ensureIdentity() {
        val dir = File("${filesDir}/p4").apply { mkdirs() }
        val cert = File(dir, "phone-cert.der")
        val key = File(dir, "phone-key.pkcs8.b64")
        if (cert.exists() && key.exists()) return
        val id = Phase4Crypto.generatePhoneIdentity()
        cert.writeBytes(id.certDer())
        key.writeText(Base64.getEncoder().encodeToString(id.keyPair.private.encoded))
        say("IDENTITY generated fpr=${Phase4Crypto.sha256(id.certDer()).joinToString("") { "%02X".format(it) }.take(16)}")
    }

    private fun loadIdentity(): Pair<X509Certificate, java.security.PrivateKey> {
        val dir = File("${filesDir}/p4")
        val cert = File(dir, "phone-cert.der")
        val key = File(dir, "phone-key.pkcs8.b64")
        if (!cert.exists() || !key.exists()) ensureIdentity()
        val kf = CertificateFactory.getInstance("X.509")
            .generateCertificate(cert.inputStream()) as X509Certificate
        val keyDer = Base64.getDecoder().decode(key.readText())
        val spec = java.security.spec.PKCS8EncodedKeySpec(keyDer)
        return Pair(kf, java.security.KeyFactory.getInstance("EC").generatePrivate(spec))
    }

    private fun identityCert(): X509Certificate {
        val dir = File("${filesDir}/p4")
        val cert = File(dir, "phone-cert.der")
        if (!cert.exists()) ensureIdentity()
        return CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream()) as X509Certificate
    }

    // ------------------------------------------------------------ TLS server

    private fun serverLoop() {
        try {
            val (cert, key) = loadIdentity()
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null)
            ks.setKeyEntry("phone", key, "unified".toCharArray(), arrayOf(cert))
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, "unified".toCharArray())
            // Relaxed TrustManager: accept any valid self-signed client cert.
            // The pairing gate (fingerprint check + user Yes/No) enforces the policy.
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (chain.isEmpty()) throw CertificateException("no client cert")
                    // Accept any cert that the platform can verify (self-signed is OK since we have no CA).
                    // The pairing logic at application layer decides trust.
                }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    throw CertificateException("we are never a TLS client this phase")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(kmf.keyManagers, arrayOf<TrustManager>(tm), SecureRandom())
            val factory = ctx.serverSocketFactory
            val server = factory.createServerSocket(PORT) as SSLServerSocket
            serverSocket = server
            say("SERVER listening port=$PORT factory=$factory")
            while (running.get()) {
                try {
                    say("SERVER waiting for accept...")
                    val sock = server.accept() as SSLSocket
                    say("SERVER accepted connection from ${sock.inetAddress}")
                    sock.wantClientAuth = true
                    sock.useClientMode = false
                    sock.soTimeout = 30_000
                    Thread({
                        run {
                            try {
                                sessionThread(sock)
                            } catch (t: Throwable) {
                                say("SESSION fail ${t.javaClass.simpleName} ${t.message}")
                                try { sock.close() } catch (_: Throwable) {}
                            }
                        }
                    }, "p4-session").start()
                } catch (t: Throwable) {
                    if (running.get()) say("ACCEPT fail ${t.javaClass.simpleName}: ${t.message}")
                    break
                }
            }
            say("SERVER exit")
        } catch (t: Throwable) {
            say("SERVER LOOP FAIL ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace()
        }
    }

    private fun sessionThread(sock: SSLSocket) {
        sock.startHandshake()
        sock.soTimeout = Phase4Protocol.DEAD_TIMEOUT_MS.toInt() // link-dead timeout (docs/PHASE4_PROTOCOL.md)
        val session = sock.session
        val client = session.peerCertificates.filterIsInstance<X509Certificate>().firstOrNull()
            ?: throw CertificateException("PC presented no client cert")
        val clientFpr = Phase4Crypto.hex(Phase4Crypto.sha256(client.encoded))
        val pinned = Phase4Crypto.trustedList(applicationContext)
        say(
            "TLS_UP role=server peer-fpr=${clientFpr.take(16)} pins=${pinned.size}" +
                " proto=${session.protocol} cipher=${session.cipherSuite.take(20)}"
        )
        // Mutable: a user Yes in THIS session flips it, so the same session can then
        // pass data channels through the gate (manager review 2026-10-18).
        var paired = pinned.any { it.first == clientFpr }

        // One pairing at a time: claim the slot atomically (compareAndSet); a second
        // unpaired connection is refused so the user's Yes is always bound to one PC.
        var decisionPending: Pairing? = null
        if (!paired) {
            val code = Phase4Crypto.pairingCode(identityCert().encoded, client.encoded)
            val p = Pairing(code, clientFpr)
            if (!pendingPairing.compareAndSet(null, p)) {
                say("PAIRING busy - rejecting concurrent unpaired connection from ${clientFpr.take(16)}")
                try { sock.close() } catch (_: Throwable) {}
                return
            }
            showPairingNotification(code)
            decisionPending = p
            sock.soTimeout = 1_500 // during pairing we take a tick per second and a half
            say("PAIRING prompt code=$code client-fpr=${clientFpr.take(16)}")
        }
        // ---- pump
        // Register the socket for the watchdog/stopAll BEFORE the pump: a session that
        // stalls in write() must still be closable from the outside.
        val live = LiveSession(sock, clientFpr.take(16))
        live.armed = paired
        liveSessions.add(live)
        val input = sock.inputStream
        val output = sock.outputStream
        val dec = Phase4Protocol.Decoder()
        val buf = ByteArray(16384)
        // Phase 5: video + input ride the same session, over the same priority writer.
        val p5 = Phase5Session(this, output, { line -> say(line) })
        // B-047: a paired session gets NO video until the PC asks with "video-on".
        p5.start(false)
        try {
            while (running.get()) {
                val n: Int
                try {
                    n = input.read(buf)
                    live.lastInboundMs = SystemClock.elapsedRealtime()
                    if (n < 0) break
                } catch (st: SocketTimeoutException) {
                    // tick: apply a pending pairing decision even if the PC sends nothing
                    val p = decisionPending
                    if (p != null) {
                        when (p.decision.get()) {
                            true -> {
                                Phase4Crypto.trustedRemember(applicationContext, clientFpr, "PC")
                                p5.writer.push(
                                    Phase4Protocol.Message.create(
                                        Phase4Protocol.CH_CONTROL, "pair-ok".toByteArray()
                                    )
                                )
                                say(
                                    "PAIR_YES stored fpr=${clientFpr.take(16)} pins=" +
                                        Phase4Crypto.trustedList(applicationContext).size
                                )
                                paired = true // same session: data channels now pass the gate
                                live.armed = true // dead-link watchdog applies from now on
                                decisionPending = null
                                pendingPairing.compareAndSet(p, null)
                                cancelPairingNotification()
                                sock.soTimeout = Phase4Protocol.DEAD_TIMEOUT_MS.toInt()
                                // B-047: video still waits for the PC's "video-on"
                                say("P5 gate open, waiting for video-on")
                            }
                            false -> {
                                p5.writer.push(
                                    Phase4Protocol.Message.create(
                                        Phase4Protocol.CH_CONTROL, "pair-no".toByteArray()
                                    )
                                )
                                say("PAIR_NO cut")
                                pendingPairing.compareAndSet(p, null)
                                cancelPairingNotification()
                                break
                            }
                            null -> { /* user has not tapped yet: keep ticking */ }
                        }
                    } else {
                        // no pairing pending: silence past the dead timeout closes the link
                        say("SESSION dead-timeout (no inbound in time)")
                        break
                    }
                    continue
                }
                val msgs = dec.feed(buf, n)
                for (m in msgs) {
                    val gate = when {
                        paired -> Phase4Protocol.Gate.PAIRED
                        decisionPending != null -> Phase4Protocol.Gate.PAIRING
                        else -> Phase4Protocol.Gate.UNPAIRED
                    }
                    if (!Phase4Protocol.mayProcessChannel(m.channel, gate)) {
                        say("DROP ${Phase4Protocol.channelName(m.channel)} (gate=$gate)")
                        continue
                    }
                    when {
                        m.channel == Phase4Protocol.CH_BENCH && m.payload.isNotEmpty() -> {
                            // bench echo: verbatim, same channel, no flags
                            p5.writer.push(Phase4Protocol.Message.create(Phase4Protocol.CH_BENCH, m.payload))
                        }
                        m.channel == Phase4Protocol.CH_CONTROL && m.payload.isEmpty() -> {
                            // keep-alive echo request -> echo with the flag
                            p5.writer.push(
                                Phase4Protocol.Message.create(
                                    Phase4Protocol.CH_CONTROL, m.payload, Phase4Protocol.FLAG_KEEPALIVE_ECHO
                                )
                            )
                        }
                        m.channel == Phase4Protocol.CH_INPUT && m.payload.isNotEmpty() -> {
                            // Phase 5: mouse + keyboard from the PC
                            p5.onInputMessage(m.payload)
                        }
                        m.channel == Phase4Protocol.CH_CONTROL && m.payload.isNotEmpty() -> {
                            // Phase 5: need-keyframe / clock sync / release-all
                            p5.onControlMessage(String(m.payload, Charsets.UTF_8))
                        }
                        else -> say("SKIP ${Phase4Protocol.channelName(m.channel)} len=${m.payload.size}")
                    }
                }
            }
        } catch (t: Throwable) {
            say("SESSION io ${t.javaClass.simpleName}: ${t.message?.take(60)}")
        } finally {
            p5.stop("session end")
            liveSessions.remove(live)
            say("SESSION_END peer=${clientFpr.take(16)}")
            val p = decisionPending
            if (p != null) {
                // session died before the user decided: clear the slot and the prompt
                pendingPairing.compareAndSet(p, null)
                cancelPairingNotification()
            }
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------ notifications

    private fun buildNotification(title: String, text: String, withActions: Boolean): Notification {
        val b = Notification.Builder(
            this,
            if (withActions) "ud_p4_pairing" else "ud_p4_session"
        )
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle(title)
            .setContentText(text)
        if (withActions) {
            val yes = PendingIntent.getService(
                this, 1,
                Intent(this, Phase4LinkService::class.java).setAction(ACTION_PAIR_YES),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val no = PendingIntent.getService(
                this, 2,
                Intent(this, Phase4LinkService::class.java).setAction(ACTION_PAIR_NO),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(Notification.Action.Builder(android.R.drawable.ic_input_add, "Yes", yes).build())
            b.addAction(Notification.Action.Builder(android.R.drawable.ic_delete, "No", no).build())
        }
        return b.build()
    }

    private fun showPairingNotification(code: String) {
        val n = buildNotification(
            "Pair this PC?",
            "PC code: $code  --  matches the PC screen?",
            true
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(47611, n)
    }

    private fun cancelPairingNotification() {
        try { getSystemService(NotificationManager::class.java).cancel(47611) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------ NSD

    private fun registerNsd() {
        try {
            val nm = getSystemService(NsdManager::class.java) as NsdManager
            nsdManager = nm
            val info = NsdServiceInfo()
            info.serviceName = NSD_NAME
            info.serviceType = NSD_TYPE
            info.port = PORT
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    say("NSD registered ${serviceInfo.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    say("NSD register FAILED errorCode=$errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    say("NSD unregistered")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    say("NSD unregister FAILED errorCode=$errorCode")
                }
            }
            nm.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdListener = listener
        } catch (t: Throwable) {
            say("NSD unavailable ${t.javaClass.simpleName} (non-fatal)")
        }
    }

    private fun unregisterNsd() {
        val l = nsdListener ?: return
        try {
            nsdManager?.unregisterService(l)
        } catch (t: Throwable) {
            say("NSD unregister failed ${t.javaClass.simpleName}")
        }
        nsdListener = null
    }
}
