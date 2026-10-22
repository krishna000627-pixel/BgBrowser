package com.bgbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock

/**
 * Sleep timer. When it ends it cancels page speech and media, closes the browser, stops the
 * background service and ends this process, so nothing keeps talking. One per process.
 */
object SleepTimer {
    @Volatile var endAt = 0L
        private set
    @Volatile var onChange: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null

    private val fire = Runnable { stopAll() }
    private val tick = object : Runnable {
        override fun run() {
            if (endAt > 0L) {
                onChange?.invoke()
                Bridge.service?.refreshNotification()
                main.postDelayed(this, 30000)
            }
        }
    }

    fun active(): Boolean = endAt != 0L

    fun remainingMs(): Long = if (endAt == 0L) 0L else maxOf(0L, endAt - SystemClock.elapsedRealtime())

    fun remainingMinutes(): Int = ((remainingMs() + 59_999L) / 60_000L).toInt()

    fun start(ctx: Context, minutes: Int) {
        cancel()
        if (minutes <= 0) return
        val ms = minutes * 60_000L
        endAt = SystemClock.elapsedRealtime() + ms
        // Keep the CPU awake until the timer fires, so it is on time with the screen off.
        try {
            val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bgbrowser:sleep").apply { acquire(ms + 10_000L) }
        } catch (_: Exception) {}
        main.postDelayed(fire, ms)
        main.postDelayed(tick, 30000)
        onChange?.invoke()
        Bridge.service?.refreshNotification()
    }

    fun cancel() {
        main.removeCallbacks(fire)
        main.removeCallbacks(tick)
        endAt = 0L
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wake = null
        onChange?.invoke()
        Bridge.service?.refreshNotification()
    }

    private fun stopAll() {
        endAt = 0L
        onChange?.invoke()
        Bridge.onCommand?.invoke("sleep")
        main.postDelayed({
            try { Bridge.service?.stopSelf() } catch (_: Exception) {}
            try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
            Process.killProcess(Process.myPid())
        }, 1500)
    }
}
