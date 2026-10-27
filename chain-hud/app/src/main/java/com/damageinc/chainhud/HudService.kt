package com.damageinc.chainhud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICXRSessionCbk
import com.rokid.cxr.link.callbacks.ICustomViewCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A user-started connected-device foreground service. No key is passed from the UI or stored in code. */
class HudService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val fetching = AtomicBoolean(false)
    private var enabled = false
    private var faction = 20747
    private var compatibilityMode = false
    private var link: CXRLink? = null
    private var opened = false
    private var opening = false
    private var lastOpenAttempt = 0L
    private var lastDisplayed: HudText? = null
    private var current: ChainSnapshot? = null
    private var lastPollMs = 0L
    private var lastNoticeMs = 0L
    private var lastError: String? = null
    private var pausedForKey = false
    private var lastKeyToken: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!enabled) return
            val now = SystemClock.elapsedRealtime()
            val text = ChainClock.render(current, now, lastError)
            updateDisplay(text, now)
            val remaining = current?.remainingSeconds(now) ?: 600
            val cadence = if (remaining <= 75) 10_000L else 20_000L
            if (!pausedForKey && !fetching.get() && now - lastPollMs >= cadence) {
                fetchTorn(now)
            }
            if (now - lastNoticeMs >= 10_000L) {
                lastNoticeMs = now
                updateNotification("${text.chain}    ${text.timer}")
            }
            main.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Torn chain HUD",
            NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            enabled = false
            main.removeCallbacks(ticker)
            link?.let { try { it.customViewClose(); it.disconnect() } catch (_: Exception) {} }
            link = null
            status("HUD stopped")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (token.isNullOrBlank()) {
            status("Rokid token missing. Reopen the app and start again.")
            stopSelf()
            return START_NOT_STICKY
        }
        faction = intent.getIntExtra(EXTRA_FACTION, 20747).takeIf { it > 0 } ?: 20747
        compatibilityMode = intent.getBooleanExtra(EXTRA_COMPAT, false)
        lastKeyToken = token
        startForeground(NOTIFICATION, buildNotification("Connecting to Rokid..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        enabled = true
        pausedForKey = false
        current = null
        lastPollMs = 0
        lastError = null
        main.removeCallbacks(ticker)
        connect(token)
        main.post(ticker)
        return START_NOT_STICKY
    }

    private fun connect(token: String) {
        opened = false
        opening = false
        lastDisplayed = null
        link?.let { try { it.disconnect() } catch (_: Exception) {} }
        val client = CXRLink(applicationContext)
        link = client
        client.setCXRLinkCbk(object : ICXRLinkCbk {
            override fun onCXRLConnected(isConnected: Boolean) {
                main.post {
                    if (link !== client) return@post
                    if (isConnected) {
                        status("Rokid service connected; waiting for glasses")
                        ensureOpen()
                    } else {
                        opened = false
                        opening = false
                        status("Rokid service disconnected; tap START to reconnect")
                    }
                }
            }
            override fun onGlassBtConnected(isConnected: Boolean) {
                main.post {
                    if (link !== client) return@post
                    if (isConnected) {
                        status("Rokid Bluetooth connected")
                        ensureOpen()
                    } else {
                        opened = false
                        opening = false
                        status("Glasses disconnected; waiting to reconnect")
                    }
                }
            }
            override fun onGlassDeviceInfo(deviceInfo: GlassInfo) = Unit
            override fun onGlassWearingStatus(wearing: Boolean) = Unit
            override fun onGlassAiAssistStart() = Unit
            override fun onGlassAiAssistStop() = Unit
            override fun onGlassAiInterrupt(interruptWake: Boolean) = Unit
        })
        client.setCXRCustomViewCbk(object : ICustomViewCbk {
            override fun onCustomViewOpened() {
                main.post {
                    if (link !== client) return@post
                    opened = true
                    opening = false
                    status("HUD open on glasses")
                }
            }
            override fun onCustomViewUpdated() = Unit
            override fun onCustomViewClosed() {
                main.post {
                    if (link !== client) return@post
                    opened = false
                    opening = false
                    lastDisplayed = null
                }
            }
            override fun onCustomViewIconsSent() = Unit
            override fun onCustomViewError(errorCode: Int, message: String?) {
                main.post {
                    if (link !== client) return@post
                    opened = false
                    opening = false
                    lastDisplayed = null
                    status("HUD render error $errorCode")
                }
            }
        })
        val sessionOK = client.configCXRSession(
            CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMVIEW),
            object : ICXRSessionCbk {
                override fun onSessionAvailable(reason: CxrDefs.CXRSessionReason) {
                    main.post { ensureOpen() }
                }
                override fun onSessionStart(reason: CxrDefs.CXRSessionReason) {
                    main.post { ensureOpen() }
                }
                override fun onSessionPause(reason: CxrDefs.CXRSessionReason) {
                    main.post { opened = false; opening = false; status("Rokid scene paused") }
                }
                override fun onSessionUnavailable(reason: CxrDefs.CXRSessionReason) {
                    main.post { opened = false; opening = false; status("Rokid scene unavailable") }
                }
            }
        )
        if (!sessionOK) {
            status("Rokid session configuration failed")
            return
        }
        try {
            val sent = client.connect(token)
            status(if (sent) "Rokid connection requested..." else "Rokid connect() rejected; retry START")
        } catch (_: Exception) {
            status("Rokid connection exception; retry START")
        }
    }

    private fun ensureOpen() {
        if (!enabled) return
        val client = link ?: return
        if (!client.isGlassBtConnected()) return
        val now = SystemClock.elapsedRealtime()
        if (opened) return
        if (opening && now - lastOpenAttempt < 10_000L) return
        lastOpenAttempt = now
        opening = true
        try {
            val t = ChainClock.render(current, now, lastError)
            if (!client.customViewOpen(HudLayout.open(t))) {
                opening = false
                status("Rokid refused HUD open")
            } else {
                lastDisplayed = t
            }
        } catch (_: Exception) {
            opening = false
            status("HUD opening failed")
        }
    }

    private fun updateDisplay(t: HudText, now: Long) {
        val client = link ?: return
        if (!client.isGlassBtConnected()) return
        if (!opened) {
            ensureOpen()
            return
        }
        if (t == lastDisplayed) return
        try {
            if (compatibilityMode) {
                // Some client-l/firmware combinations acknowledge updates without rendering.
                // This mode is intentionally opt-in because a close/open may visibly flicker.
                client.customViewClose()
                opened = false
                opening = false
                lastDisplayed = null
                ensureOpen()
            } else if (client.customViewUpdate(HudLayout.update(t))) {
                lastDisplayed = t
            } else {
                status("HUD update rejected; try compatibility checkbox")
            }
        } catch (_: Exception) {
            status("HUD update failed; try compatibility checkbox")
        }
    }

    private fun fetchTorn(now: Long) {
        if (!fetching.compareAndSet(false, true)) return
        lastPollMs = now
        val id = faction
        worker.execute {
            try {
                val key = SecureKey.read(this)
                    ?: throw IllegalStateException("Missing API key (open app)")
                // New timestamp on each request avoids identical service-cache requests.
                val timestamp = System.currentTimeMillis() / 1000
                val url = URL("https://api.torn.com/v2/faction/$id/chain?timestamp=$timestamp&comment=RokidChain")
                val c = url.openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 5000
                    c.readTimeout = 5000
                    c.requestMethod = "GET"
                    c.setRequestProperty("Authorization", "ApiKey $key")
                    c.setRequestProperty("Accept", "application/json")
                    if (c.responseCode != 200) {
                        if (c.responseCode == 401 || c.responseCode == 403) pausedForKey = true
                        throw IllegalStateException("Torn HTTP ${c.responseCode}")
                    }
                    val body = c.inputStream.bufferedReader().use { it.readText() }
                    val j = JSONObject(body)
                    if (j.has("error")) {
                        val err = j.getJSONObject("error")
                        val code = err.optInt("code", 0)
                        if (code == 2 || code == 10 || code == 16) pausedForKey = true
                        throw IllegalStateException("Torn API error $code")
                    }
                    val chain = j.getJSONObject("chain")
                    val count = chain.getInt("current")
                    val timeout = chain.getInt("timeout")
                    // Observed request START is conservative against network latency.
                    val data = ChainSnapshot(count.coerceAtLeast(0),
                        timeout.coerceAtLeast(0), now)
                    main.post {
                        if (!enabled || id != faction) return@post
                        current = data
                        lastError = null
                        status("Live faction $id; chain $count; last sync OK")
                    }
                } finally { c.disconnect() }
            } catch (e: Exception) {
                val message = e.message?.take(100) ?: "network error"
                main.post {
                    lastError = message
                    status("Torn refresh failed: $message")
                }
            } finally {
                fetching.set(false)
            }
        }
    }

    private fun status(message: String) {
        getSharedPreferences("chainhud", MODE_PRIVATE).edit().putString("status", message).apply()
    }
    private fun buildNotification(text: String): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Torn chain HUD running")
        .setContentText(text)
        .setOngoing(true)
        .build()
    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION, buildNotification(text))
    }
    override fun onDestroy() {
        enabled = false
        main.removeCallbacks(ticker)
        try { link?.customViewClose(); link?.disconnect() } catch (_: Exception) {}
        link = null
        worker.shutdownNow()
        super.onDestroy()
    }
    companion object {
        const val ACTION_START = "com.damageinc.chainhud.START"
        const val ACTION_STOP = "com.damageinc.chainhud.STOP"
        const val EXTRA_TOKEN = "rokid_token"
        const val EXTRA_FACTION = "faction_id"
        const val EXTRA_COMPAT = "compatibility"
        private const val CHANNEL = "torn_chain_hud"
        private const val NOTIFICATION = 7001
    }
}
