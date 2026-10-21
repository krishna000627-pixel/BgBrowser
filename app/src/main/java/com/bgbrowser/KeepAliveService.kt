package com.bgbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat

object Bridge {
    @Volatile var service: KeepAliveService? = null
    @Volatile var onCommand: ((String) -> Unit)? = null
}

class KeepAliveService : Service() {
    companion object {
        const val CH = "bg_browser"
        const val ID = 42
        const val ACTION_TOGGLE = "com.bgbrowser.TOGGLE"
    }

    private lateinit var session: MediaSessionCompat
    private var track: AudioTrack? = null
    @Volatile private var running = false
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var playing = true
    private var title = "Background Browser"

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Bridge.service = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Background browsing", NotificationManager.IMPORTANCE_LOW))

        // The "fake" media session the web page never registers itself.
        session = MediaSessionCompat(this, "BgBrowser").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { setPlaying(true); Bridge.onCommand?.invoke("resume") }
                override fun onPause() { setPlaying(false); Bridge.onCommand?.invoke("pause") }
                override fun onStop() { stopSelf() }
            })
            isActive = true
        }
        pushState()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID, buildNotification())
        }

        acquireLocks()
        startSilence()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) {
            val p = !playing
            setPlaying(p)
            Bridge.onCommand?.invoke(if (p) "resume" else "pause")
        }
        return START_STICKY
    }

    fun updateMeta(t: String?) {
        if (t.isNullOrBlank()) return
        title = t
        pushState()
        getSystemService(NotificationManager::class.java).notify(ID, buildNotification())
    }

    private fun setPlaying(p: Boolean) {
        playing = p
        pushState()
        getSystemService(NotificationManager::class.java).notify(ID, buildNotification())
    }

    private fun pushState() {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
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
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, KeepAliveService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText("Running in background")
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bgbrowser:wake").apply { acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifi = wm.createWifiLock(mode, "bgbrowser:wifi").apply { acquire() }
    }

    // Inaudible audio stream so the system always sees an active media player.
    private fun startSilence() {
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
        running = false
        try { track?.stop(); track?.release() } catch (_: Exception) {}
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        session.isActive = false
        session.release()
        Bridge.service = null
        super.onDestroy()
    }
}
