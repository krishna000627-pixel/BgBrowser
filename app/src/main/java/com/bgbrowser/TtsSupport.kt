package com.bgbrowser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import java.util.Locale

enum class TtsState { UNKNOWN, OK, NO_ENGINE, NO_DATA }

object TtsHelper {
    /** Engine present (TextToSpeech init works) and voice data for the device language installed. */
    fun check(ctx: Context, done: (TtsState) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var engine: TextToSpeech? = null
        engine = TextToSpeech(ctx.applicationContext) { status ->
            var st = TtsState.NO_ENGINE
            if (status == TextToSpeech.SUCCESS) {
                st = try {
                    val r = engine?.isLanguageAvailable(Locale.getDefault()) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    if (r >= TextToSpeech.LANG_AVAILABLE) TtsState.OK else TtsState.NO_DATA
                } catch (_: Exception) { TtsState.NO_DATA }
            }
            try { engine?.shutdown() } catch (_: Exception) {}
            main.post { done(st) }
        }
    }

    fun install(act: Activity) {
        try { act.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)); return } catch (_: Exception) {}
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts")))
        } catch (_: Exception) {
            try {
                act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")))
            } catch (_: Exception) {}
        }
    }
}

/** Reports speechSynthesis state from the page to the keep-alive service. */
class StateBridge {
    @JavascriptInterface
    fun speech(speaking: Boolean, paused: Boolean) { Bridge.service?.onSpeech(speaking, paused) }
}

/** Optional replacement for the page's speechSynthesis (injected by Js.TTS_SHIM). One per tab. */
class NativeTtsBridge(ctx: Context, private val send: (String) -> Unit) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val todo = ArrayList<Runnable>()

    private var curId = 0
    private var curText = ""
    private var curLang = ""
    private var curVoice = ""
    private var curRate = 1f
    private var curPitch = 1f
    private var curVolume = 1f
    private var base = 0
    private var lastChar = 0
    private var resumed = false
    @Volatile private var active = false

    private fun parse(id: String?): IntArray? {
        val p = id?.split(":") ?: return null
        if (p.size < 4) return null
        return try { intArrayOf(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt()) } catch (_: Exception) { null }
    }

    private fun emit(type: String, ci: Int) {
        send("window.__bgTtsEvt&&window.__bgTtsEvt($curId,'$type',$ci)")
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val p = parse(utteranceId) ?: return
            if (!active || p[0] != curId) return
            if (p[1] == 0 && !resumed) emit("start", 0)
        }
        override fun onDone(utteranceId: String?) {
            val p = parse(utteranceId) ?: return
            if (!active || p[0] != curId) return
            if (p[3] == 1) { active = false; emit("end", 0) }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            val p = parse(utteranceId) ?: return
            if (!active || p[0] != curId) return
            active = false
            emit("error", 0)
        }
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val p = parse(utteranceId) ?: return
            if (!active || p[0] != curId) return
            lastChar = base + p[2] + start
            emit("boundary", lastChar)
        }
    }

    init {
        tts = TextToSpeech(ctx.applicationContext) { st ->
            synchronized(this) {
                ready = st == TextToSpeech.SUCCESS
                if (ready) tts?.setOnUtteranceProgressListener(listener)
                val q = ArrayList(todo)
                todo.clear()
                if (ready) q.forEach { it.run() }
            }
        }
    }

    private fun whenReady(block: () -> Unit) {
        synchronized(this) {
            if (ready) block() else todo.add(Runnable { block() })
        }
    }

    private fun chunks(s: String): List<Pair<Int, String>> {
        val max = minOf(TextToSpeech.getMaxSpeechInputLength(), 3500)
        val out = ArrayList<Pair<Int, String>>()
        var i = 0
        while (i < s.length) {
            var e = minOf(s.length, i + max)
            if (e < s.length) {
                val sp = s.lastIndexOf(' ', e)
                if (sp > i + max / 2) e = sp
            }
            out.add(Pair(i, s.substring(i, e)))
            i = e
        }
        if (out.isEmpty()) out.add(Pair(0, ""))
        return out
    }

    private fun applyVoice(t: TextToSpeech) {
        try {
            if (curVoice.isNotEmpty()) {
                val v = t.voices?.firstOrNull { it.name == curVoice }
                if (v != null) { t.setVoice(v); return }
            }
            if (curLang.isNotEmpty()) t.setLanguage(Locale.forLanguageTag(curLang))
        } catch (_: Exception) {}
    }

    private fun begin(text: String) {
        val t = tts ?: return
        try {
            t.setSpeechRate(curRate.coerceIn(0.1f, 4f))
            t.setPitch(curPitch.coerceIn(0.1f, 2f))
            applyVoice(t)
            active = true
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, curVolume.coerceIn(0f, 1f)) }
            val parts = chunks(text)
            for ((k, part) in parts.withIndex()) {
                val last = if (k == parts.size - 1) 1 else 0
                t.speak(part.second, if (k == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params,
                    "$curId:$k:${part.first}:$last")
            }
        } catch (_: Exception) {
            active = false
            emit("error", 0)
        }
    }

    @JavascriptInterface
    fun speak(id: Int, text: String, lang: String, rate: Double, pitch: Double, volume: Double, voice: String) {
        whenReady {
            curId = id; curText = text; curLang = lang; curVoice = voice
            curRate = rate.toFloat(); curPitch = pitch.toFloat(); curVolume = volume.toFloat()
            base = 0; lastChar = 0; resumed = false
            begin(text)
        }
    }

    @JavascriptInterface
    fun stop() { active = false; try { tts?.stop() } catch (_: Exception) {} }

    @JavascriptInterface
    fun pause() { active = false; try { tts?.stop() } catch (_: Exception) {} }

    @JavascriptInterface
    fun resume() {
        whenReady {
            base = lastChar.coerceIn(0, curText.length)
            resumed = true
            begin(curText.substring(base))
        }
    }

    fun shutdown() { active = false; try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {} }
}
