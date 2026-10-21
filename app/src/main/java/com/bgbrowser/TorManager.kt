package com.bgbrowser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

/**
 * Runs Guardian Project tor-android (service lives in the :tor process) and routes this process's
 * WebViews through it. The Tor library is driven by reflection so only the dependency has to resolve.
 *
 * Kill switch: the process-wide proxy is a dead port until Tor is bootstrapped AND verified,
 * and again whenever Tor drops. Nothing ever falls back to a direct connection.
 */
object TorManager {
    enum class S { OFF, STARTING, READY, FAILED }

    @Volatile var state = S.OFF
    @Volatile var verified = false
    @Volatile var progress = 0
    @Volatile var detail = ""
    @Volatile var listener: (() -> Unit)? = null

    /** Pages may only load when Tor is up and the exit was verified. */
    val allowed: Boolean get() = state == S.READY && verified

    private const val DEAD = "http://127.0.0.1:1"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var gen = 0
    @Volatile private var realRule: String? = null
    private var app: Context? = null
    private var connection: ServiceConnection? = null
    @Volatile private var ctl: Any? = null

    private fun alive(g: Int) = running && g == gen

    private fun set(s: S, d: String) {
        state = s
        detail = d
        listener?.invoke()
    }

    fun fail(msg: String, block: Boolean = true) {
        verified = false
        set(S.FAILED, msg)
        if (block) blockAll()
    }

    fun setVerified(ok: Boolean, msg: String) {
        if (ok) {
            verified = true
            set(S.READY, "Tor verified")
        } else {
            fail(msg)
        }
    }

    fun begin(ctx: Context) {
        app = ctx.applicationContext
        val g = ++gen
        running = true
        verified = false
        progress = 0
        realRule = null
        blockAll()
        set(S.STARTING, "Starting Tor…")
        val c = app ?: return
        try {
            val cls = Class.forName("org.torproject.jni.TorService")
            val conn = object : ServiceConnection {
                override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
                    val svc: Any? = try {
                        val m = b!!.javaClass.getMethod("getService")
                        m.isAccessible = true
                        m.invoke(b)
                    } catch (e: Exception) { null }
                    if (svc == null) { fail("Tor service API not recognised"); return }
                    Thread { work(svc, g) }.apply { isDaemon = true; start() }
                }
                override fun onServiceDisconnected(n: ComponentName?) {
                    if (alive(g)) fail("Tor service stopped")
                }
            }
            connection = conn
            if (!c.bindService(Intent(c, cls), conn, Context.BIND_AUTO_CREATE)) fail("Tor service not found")
        } catch (e: Throwable) {
            fail("Tor failed to start: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    fun stop() {
        running = false
        gen++
        try { connection?.let { app?.unbindService(it) } } catch (_: Exception) {}
        connection = null
        ctl = null
    }

    fun restart(ctx: Context) {
        stop()
        begin(ctx)
    }

    private fun work(svc: Any, g: Int) {
        try {
            val getConn = svc.javaClass.getMethod("getTorControlConnection")
            var conn: Any? = null
            var tries = 0
            while (alive(g) && conn == null && tries++ < 240) {
                conn = try { getConn.invoke(svc) } catch (e: Exception) { null }
                if (conn == null) Thread.sleep(500)
            }
            if (!alive(g)) return
            if (conn == null) { fail("Tor control connection never came up"); return }
            ctl = conn

            var pct = 0
            var t = 0
            while (alive(g) && pct < 100 && t++ < 360) {
                val s = info("status/bootstrap-phase") ?: ""
                pct = Regex("PROGRESS=(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: pct
                progress = pct
                set(S.STARTING, "Connecting to the Tor network… $pct%")
                if (pct < 100) Thread.sleep(1000)
            }
            if (!alive(g)) return
            if (pct < 100) { fail("Tor did not finish connecting. Check your network and retry."); return }

            fun findHttp(): String? {
                for (i in 0 until 10) {
                    val p = port(info("net/listeners/httptunnel"))
                    if (p != null) return "http://127.0.0.1:$p"
                    Thread.sleep(300)
                }
                return null
            }
            // Own circuit per destination, so exit relays cannot link the different sites you visit.
            if (Prefs.bool("torIsolate", true)) setConf("HTTPTunnelPort", "auto IsolateDestAddr")
            else setConf("HTTPTunnelPort", "auto")
            var rule: String? = findHttp()
            if (rule == null) { setConf("HTTPTunnelPort", "auto"); rule = findHttp() }
            if (rule == null) {
                val p = port(info("net/listeners/socks"))
                if (p != null) rule = "socks5://127.0.0.1:$p"
            }
            if (rule == null) { fail("Could not find a local Tor proxy port"); return }
            realRule = rule
            applyReal()
            monitor(g)
        } catch (e: Throwable) {
            if (alive(g)) fail("Tor error: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    private fun monitor(g: Int) {
        var down = 0
        while (alive(g)) {
            try { Thread.sleep(5000) } catch (_: InterruptedException) { return }
            val ok = info("status/circuit-established") == "1"
            if (ok) down = 0 else down++
            if (!ok && state == S.READY && down >= 2) {
                verified = false
                set(S.STARTING, "Tor lost its circuit. Reconnecting…")
                blockAll()
            } else if (ok && state == S.STARTING) {
                applyReal()
            }
        }
    }

    private fun applyReal() {
        val r = realRule ?: return
        proxy(r) { set(S.READY, "Tor connected. Verifying…") }
    }

    fun blockAll() { proxy(DEAD, null) }

    /** Asks Tor for fresh circuits (NEWNYM). Callers wipe cookies and site data themselves. */
    fun newIdentity() {
        Thread {
            try {
                val c = ctl ?: return@Thread
                c.javaClass.getMethod("signal", String::class.java).invoke(c, "NEWNYM")
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    private fun proxy(rule: String, then: (() -> Unit)?) {
        main.post {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                    fail("This WebView has no proxy support. Update Android System WebView.", false)
                    return@post
                }
                val b = ProxyConfig.Builder().addProxyRule(rule)
                // Chromium skips the proxy for localhost by default. Remove that so nothing is reached directly.
                try { b.javaClass.getMethod("removeImplicitRules").invoke(b) } catch (_: Exception) {}
                val cfg = b.build()
                ProxyController.getInstance().setProxyOverride(cfg, Executor { it.run() }, Runnable { then?.invoke() })
            } catch (e: Throwable) {
                fail("Proxy setup failed: ${e.message ?: e.javaClass.simpleName}", false)
            }
        }
    }

    private fun info(key: String): String? {
        return try {
            val c = ctl ?: return null
            c.javaClass.getMethod("getInfo", String::class.java).invoke(c, key) as? String
        } catch (e: Exception) { null }
    }

    private fun setConf(k: String, v: String) {
        try {
            val c = ctl ?: return
            c.javaClass.getMethod("setConf", String::class.java, String::class.java).invoke(c, k, v)
        } catch (_: Exception) {}
    }

    private fun port(s: String?): Int? =
        if (s == null) null else Regex(":(\\d{2,5})").find(s)?.groupValues?.get(1)?.toIntOrNull()
}
