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

        private val running = AtomicBoolean(false)
        private val lastState = AtomicReference("idle")
        private var serverThread: Thread? = null
        private var serverSocket: SSLServerSocket? = null
        private var nsdManager: NsdManager? = null
        private var nsdListener: NsdManager.RegistrationListener? = null
        private var pendingPairing: Pairing? = null
        private var sessions = 0

        /** Latest state line, for the harness STATUS action. */
        @Synchronized fun stateLine(): String = lastState.get()

        @Synchronized fun isRunning(): Boolean = running.get()
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
            ACTION_STATUS -> say("status running=${running.get()} sessions=$sessions last=$lastState pair=${pendingPairing != null}")
            ACTION_PAIR_YES -> pendingPairing?.resolve(true)
            ACTION_PAIR_NO -> pendingPairing?.resolve(false)
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
        unregisterNsd()
        pendingPairing?.resolve(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    /** Pure function: given pairing state and channel, decide if the frame may be processed. */
    private fun mayProcess(channel: Byte, paired: Boolean, decisionPending: Pairing?): Boolean {
        return if (paired) {
            true // all channels allowed after pairing
        } else if (decisionPending != null) {
            // During pairing: only control channel allowed (for pair-ok/no echo if needed)
            channel == Phase4Protocol.CH_CONTROL
        } else {
            // No pairing pending and not paired (should not happen, but allow control)
            channel == Phase4Protocol.CH_CONTROL
        }
    }

    private fun sessionThread(sock: SSLSocket) {
        sock.startHandshake()
        sock.soTimeout = 15_000 // link-dead timeout (docs/PHASE4_PROTOCOL.md)
        val session = sock.session
        val client = session.peerCertificates.filterIsInstance<X509Certificate>().firstOrNull()
            ?: throw CertificateException("PC presented no client cert")
        val clientFpr = Phase4Crypto.hex(Phase4Crypto.sha256(client.encoded))
        val pinned = Phase4Crypto.trustedList(applicationContext)
        say(
            "TLS_UP role=server peer-fpr=${clientFpr.take(16)} pins=${pinned.size}" +
                " proto=${session.protocol} cipher=${session.cipherSuite.take(20)}"
        )
        val paired = pinned.any { it.first == clientFpr }
        
        // Check for pairing race: if another unpaired session is already in pairing, reject this one
        var decisionPending: Pairing? = null
        if (!paired) {
            // Only allow one pairing at a time
            if (pendingPairing != null) {
                say("PAIRING busy - rejecting concurrent unpaired connection from $clientFpr")
                try { sock.close() } catch (_: Throwable) {}
                return
            }
            val code = Phase4Crypto.pairingCode(identityCert().encoded, client.encoded)
            val p = Pairing(code, clientFpr)
            pendingPairing = p
            showPairingNotification(code)
            decisionPending = p
            sock.soTimeout = 1_500 // during pairing we take a tick per second and a half
            say("PAIRING prompt code=$code client-fpr=${clientFpr.take(16)}")
        }
        // ---- pump
        val input = sock.inputStream
        val output = sock.outputStream
        val dec = Phase4Protocol.Decoder()
        val buf = ByteArray(16384)
        var repliedPair = false
        try {
            while (running.get()) {
                val n: Int
                try {
                    n = input.read(buf)
                    if (n < 0) break
                } catch (st: SocketTimeoutException) {
                    // tick: apply a pending pairing decision even if the PC sends nothing
                    val p = decisionPending
                    if (p != null && p.decision.get() != null && !repliedPair) {
                        if (p.decision.get() == true) {
                            Phase4Crypto.trustedRemember(applicationContext, clientFpr, "PC")
                            output.write(Phase4Protocol.encodeFrame(Phase4Protocol.CH_CONTROL, 0, "pair-ok".toByteArray()))
                            output.flush()
                            say(
                                "PAIR_YES stored fpr=${clientFpr.take(16)} pins=" +
                                    Phase4Crypto.trustedList(applicationContext).size
                            )
                            repliedPair = true
                            pendingPairing = null
                            decisionPending = null
                            sock.soTimeout = 15_000
                        } else {
                            output.write(Phase4Protocol.encodeFrame(Phase4Protocol.CH_CONTROL, 0, "pair-no".toByteArray()))
                            output.flush()
                            say("PAIR_NO cut")
                            break
                        }
                    } else if (decisionPending == null && !paired) {
                        // no pairing pending and not paired: silence past the timeout closed the link
                        say("SESSION dead-timeout (no inbound in time)")
                        break
                    }
                    continue
                }
                val msgs = dec.feed(buf, n)
                for (m in msgs) {
                    if (!mayProcess(m.channel, paired, decisionPending)) {
                        say("DROP ${Phase4Protocol.channelName(m.channel)} (not paired, pending=${decisionPending != null})")
                        continue
                    }
                    when {
                        m.channel == Phase4Protocol.CH_BENCH && m.payload.isNotEmpty() -> {
                            // bench echo: verbatim, same channel, no flags
                            output.write(Phase4Protocol.encodeFrame(Phase4Protocol.CH_BENCH, 0, m.payload))
                        }
                        m.channel == Phase4Protocol.CH_CONTROL && m.payload.isEmpty() -> {
                            // keep-alive echo request -> echo with the flag
                            output.write(
                                Phase4Protocol.encodeFrame(
                                    Phase4Protocol.CH_CONTROL, Phase4Protocol.FLAG_KEEPALIVE_ECHO, m.payload
                                )
                            )
                        }
                        else -> say("SKIP ${Phase4Protocol.channelName(m.channel)} len=${m.payload.size}")
                    }
                }
                output.flush()
            }
        } catch (t: Throwable) {
            say("SESSION io ${t.javaClass.simpleName}: ${t.message?.take(60)}")
        } finally {
            say("SESSION_END peer=${clientFpr.take(16)}")
            if (decisionPending != null) pendingPairing = null
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
