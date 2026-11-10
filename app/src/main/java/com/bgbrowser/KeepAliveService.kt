package com.bgbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat

/** One Bridge per process (main, :incognito, :tor each have their own). */
object Bridge {
    @Volatile var service: KeepAliveService? = null
    @Volatile var onCommand: ((String) -> Unit)? = null
    /** Menu "Background" On/Off. Off = service stopped, speech cancelled. Resets to On at launch. */
    @Volatile var power = true
}

open class KeepAliveService : Service() {
    companion object {
        const val CH = "bg_browser"
        const val ACTION_TOGGLE = "com.bgbrowser.TOGGLE"
    }

    protected open val notifId: Int get() = 42
    protected open val sessionName: String get() = "BgBrowser"
    protected open val privateMode: Boolean get() = false
    protected open val kindId: Int get() = K_NORMAL
    protected open val activityClass: Class<*> get() = MainActivity::class.java

    private val main = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSessionCompat
    private var track: AudioTrack? = null
    @Volatile private var running = false
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var playing = true
    private var speaking = false
    private var title = "Background Browser"
    @Volatile private var suspended = false

    // Media link (menu tile, default off): external pause/play controls this app. Per browser mode.
    private fun linkOn() = Prefs.bool("mediaLink_$kindId", false)

    // Headphones or Bluetooth disconnected: stop or pause so the phone speaker does not start talking.
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (Prefs.str("onDisconnect", "pause")) {
                "pause" -> { setPlaying(false); Bridge.onCommand?.invoke("pause") }
                "stop" -> { setPlaying(false); Bridge.onCommand?.invoke("stop") }
            }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    fun refreshNotification() {
        main.post { try { notifyNow() } catch (_: Exception) {} }
    }

    fun isPlaying(): Boolean = playing

    override fun onCreate() {
        super.onCreate()
        Bridge.service = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Background browsing", NotificationManager.IMPORTANCE_LOW))

        session = MediaSessionCompat(this, sessionName).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { if (linkOn()) linkedResume(false) }
                override fun onPause() { if (linkOn()) linkedPause() }
                override fun onStop() { if (linkOn()) linkedPause() }
            })
            isActive = linkOn()
        }
        pushState()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(notifId, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(notifId, buildNotification())
        }

        acquireLocks()
        startSilence()
        try { registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) toggle()
        return START_STICKY
    }

    /** Play/pause from the notification or the floating pill. */
    fun toggle() {
        if (suspended) { linkedResume(true); return }
        val p = !playing
        setPlaying(p)
        Bridge.onCommand?.invoke(if (p) "resume" else "pause")
    }

    /** Called when the Media link tile changes. The session only takes media buttons while the link is on. */
    fun applyLink() {
        main.post {
            val on = linkOn()
            try { session.isActive = on } catch (_: Exception) {}
            if (!on && suspended) linkedResume(true)
        }
    }

    // Playback streams that are not ours (usage media/game/unknown). Our own silent stream counts as 1.
    private fun mediaStreams(): Int = try {
        getSystemService(AudioManager::class.java).activePlaybackConfigurations.count {
            val u = it.audioAttributes.usage
            u == AudioAttributes.USAGE_MEDIA || u == AudioAttributes.USAGE_GAME || u == AudioAttributes.USAGE_UNKNOWN
        }
    } catch (_: Exception) { 0 }

    /** External pause: stop the app (page speech paused, silent stream and locks released). */
    private fun linkedPause() {
        if (suspended) return
        // Another app's media is active and we are not reading aloud: stay out of the way.
        if (!speaking && mediaStreams() > 1) return
        suspended = true
        stopSilence()
        releaseLocks()
        setPlaying(false)
        Bridge.onCommand?.invoke("pause")
    }

    /** External play: run the app again, unless some other app is playing media. */
    private fun linkedResume(force: Boolean) {
        if (!suspended) { setPlaying(true); Bridge.onCommand?.invoke("resume"); return }
        main.postDelayed({
            if (!suspended) return@postDelayed
            // We are stopped, so any active media stream belongs to another app.
            if (!force && mediaStreams() > 0) return@postDelayed
            suspended = false
            acquireLocks()
            startSilence()
            setPlaying(true)
            Bridge.onCommand?.invoke("resume")
        }, 300)
    }

    fun updateMeta(t: String?) {
        if (privateMode || t.isNullOrBlank()) return
        main.post {
            title = t
            pushState()
            notifyNow()
        }
    }

    /** Page speech state reported by the injected script: keeps the session in sync with speechSynthesis. */
    fun onSpeech(isSpeaking: Boolean, isPaused: Boolean) {
        main.post {
            speaking = isSpeaking
            if (isSpeaking) playing = !isPaused
            pushState()
            notifyNow()
        }
    }

    private fun setPlaying(p: Boolean) {
        playing = p
        pushState()
        notifyNow()
    }

    private fun notifyNow() {
        getSystemService(NotificationManager::class.java).notify(notifId, buildNotification())
    }

    private fun pushState() {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, if (privateMode) "Private session" else title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "BG Browser")
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    0L, if (playing) 1f else 0f
                )
                .build()
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, activityClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, javaClass).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        var text = if (speaking) "Reading aloud · running in background" else "Running in background"
        if (SleepTimer.active()) text += " · sleep in ${SleepTimer.remainingMinutes()} min"
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_wave)
            .setContentTitle(if (privateMode) "Private session" else title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(if (privateMode) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play", toggle
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .build()
    }

    @Suppress("DEPRECATION")
    private fun releaseLocks() {
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifi?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wake = null
        wifi = null
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        releaseLocks()
        if (Prefs.bool("wakeLock", true)) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bgbrowser:wake").apply { acquire() }
        }
        if (Prefs.bool("wifiLock", true)) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = wm.createWifiLock(mode, "bgbrowser:wifi").apply { acquire() }
        }
    }

    // Inaudible audio stream so the system always sees an active media player.
    private fun stopSilence() {
        running = false
        try { track?.stop(); track?.release() } catch (_: Exception) {}
        track = null
    }

    private fun startSilence() {
        stopSilence()
        val rate = 8000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(maxOf(min, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        running = true
        Thread {
            val buf = ShortArray(1024) { if (it % 2 == 0) 1 else -1 }
            while (running) {
                try { t.write(buf, 0, buf.size) } catch (_: Exception) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    override fun onDestroy() {
        try { unregisterReceiver(noisy) } catch (_: Exception) {}
        stopSilence()
        releaseLocks()
        session.isActive = false
        session.release()
        Bridge.service = null
        super.onDestroy()
    }
}

class IncognitoKeepService : KeepAliveService() {
    override val notifId: Int get() = 44
    override val sessionName: String get() = "BgBrowserIncognito"
    override val privateMode: Boolean get() = true
    override val kindId: Int get() = K_INCOG
    override val activityClass: Class<*> get() = IncognitoActivity::class.java
}

class TorKeepService : KeepAliveService() {
    override val notifId: Int get() = 45
    override val sessionName: String get() = "BgBrowserTor"
    override val privateMode: Boolean get() = true
    override val kindId: Int get() = K_TOR
    override val activityClass: Class<*> get() = TorActivity::class.java
}
