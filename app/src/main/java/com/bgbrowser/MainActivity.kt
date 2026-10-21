package com.bgbrowser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var modeBtn: Button
    private lateinit var speedBtn: Button
    private val ui = Handler(Looper.getMainLooper())
    private var mode = 0   // 0 = off, 1 = scroll (JS), 2 = swipe (touch)
    private var speed = 2  // 1..3
    private val prefs by lazy { getSharedPreferences("bg", MODE_PRIVATE) }

    private val swipeLoop = object : Runnable {
        override fun run() {
            if (mode == 2) {
                swipeUp()
                ui.postDelayed(this, longArrayOf(8000, 5000, 3000)[speed - 1])
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = prefs.getInt("mode", 0)
        speed = prefs.getInt("speed", 2)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        buildUi()
        setupWeb()

        Bridge.onCommand = { cmd ->
            ui.post { web.evaluateJavascript(if (cmd == "pause") PAUSE_JS else RESUME_JS, null) }
        }
        ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))

        web.loadUrl(prefs.getString("url", "https://www.google.com")!!)
        applyMode()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this)
        urlBox = EditText(this).apply {
            setSingleLine(); hint = "URL or search"
            imeOptions = EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, _, _ -> load(text.toString()); true }
        }
        val go = Button(this).apply { text = "Go"; setOnClickListener { load(urlBox.text.toString()) } }
        bar.addView(urlBox); bar.addView(go)

        val row = LinearLayout(this)
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; textSize = 11f; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
        modeBtn = btn("Auto") { mode = (mode + 1) % 3; applyMode() }
        speedBtn = btn("Speed") { speed = speed % 3 + 1; applyMode() }
        val battery = btn("Battery") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
        val boot = btn("Boot launch") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        row.addView(modeBtn); row.addView(speedBtn); row.addView(battery); row.addView(boot)

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(bar); root.addView(row); root.addView(web)
        setContentView(root)
    }

    private fun setupWeb() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            builtInZoomControls = true
            displayZoomControls = false
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, KEEPALIVE_JS, setOf("*"))
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String?, f: android.graphics.Bitmap?) {
                v.evaluateJavascript(KEEPALIVE_JS, null)
                url?.let { urlBox.setText(it) }
            }
            override fun onPageFinished(v: WebView, url: String?) {
                v.evaluateJavascript(KEEPALIVE_JS, null)
                v.evaluateJavascript(autoJs(mode == 1), null)
                url?.let { prefs.edit().putString("url", it).apply() }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                Bridge.service?.updateMeta(title)
            }
        }
    }

    private fun load(input: String) {
        val s = input.trim()
        if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.contains(".") && !s.contains(" ") -> "https://$s"
            else -> "https://www.google.com/search?q=" + Uri.encode(s)
        }
        web.loadUrl(url)
    }

    private fun applyMode() {
        modeBtn.text = "Auto: " + listOf("OFF", "SCROLL", "SWIPE")[mode]
        speedBtn.text = "Speed $speed"
        ui.removeCallbacks(swipeLoop)
        web.evaluateJavascript(autoJs(mode == 1), null)
        if (mode == 2) ui.postDelayed(swipeLoop, 2000)
        prefs.edit().putInt("mode", mode).putInt("speed", speed).apply()
    }

    // Real touch swipe (works while screen is on).
    private fun swipeUp() {
        val x = web.width / 2f
        val y0 = web.height * 0.75f
        val y1 = web.height * 0.25f
        val start = SystemClock.uptimeMillis()
        val steps = 10
        fun send(action: Int, y: Float) {
            val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            web.dispatchTouchEvent(e); e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, y0)
        for (i in 1..steps) ui.postDelayed({ send(MotionEvent.ACTION_MOVE, y0 + (y1 - y0) * i / steps) }, i * 20L)
        ui.postDelayed({ send(MotionEvent.ACTION_UP, y1) }, (steps + 1) * 20L)
    }

    private fun autoJs(on: Boolean): String =
        AUTO_JS.replace("__ON__", on.toString()).replace("__PX__", intArrayOf(3, 6, 12)[speed - 1].toString())

    // Keep the page and its timers/audio alive when the app is backgrounded.
    private fun keepRunning() { web.onResume(); web.resumeTimers() }
    override fun onPause() { super.onPause(); keepRunning() }
    override fun onStop() { super.onStop(); keepRunning() }
    override fun onResume() { super.onResume(); keepRunning() }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }

    override fun onDestroy() {
        Bridge.onCommand = null
        ui.removeCallbacksAndMessages(null)
        if (isFinishing) stopService(Intent(this, KeepAliveService::class.java))
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val PAUSE_JS =
            "document.querySelectorAll('audio,video').forEach(function(m){m.pause()});try{speechSynthesis.pause()}catch(e){}"
        const val RESUME_JS =
            "document.querySelectorAll('audio,video').forEach(function(m){m.play()});try{speechSynthesis.resume()}catch(e){}"

        // Makes the page think it is always visible/focused, and nudges Chrome's TTS 15s bug.
        val KEEPALIVE_JS = """
(function(){
  try{
    Object.defineProperty(document,'hidden',{get:function(){return false},configurable:true});
    Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'},configurable:true});
    Object.defineProperty(document,'webkitHidden',{get:function(){return false},configurable:true});
    Object.defineProperty(document,'webkitVisibilityState',{get:function(){return 'visible'},configurable:true});
    var stop=function(e){e.stopImmediatePropagation()};
    document.addEventListener('visibilitychange',stop,true);
    document.addEventListener('webkitvisibilitychange',stop,true);
    window.addEventListener('blur',stop,true);
    document.hasFocus=function(){return true};
  }catch(e){}
  if(!window.__bgk){
    window.__bgk=1;
    setInterval(function(){
      try{
        var s=window.speechSynthesis;
        if(s&&s.speaking&&!s.paused){s.pause();s.resume();}
      }catch(e){}
    },12000);
  }
})();
"""

        // Auto-scroll; at the bottom it clicks a "next" link/button.
        val AUTO_JS = """
(function(){
  if(window.__autoT){clearInterval(window.__autoT);window.__autoT=null;}
  if(!__ON__) return;
  var idle=0;
  function findNext(){
    var rel=document.querySelector('a[rel=next]'); if(rel) return rel;
    var c=document.querySelectorAll('a,button,[role=button]');
    for(var i=0;i<c.length;i++){
      var t=(c[i].innerText||c[i].getAttribute('aria-label')||'').trim().toLowerCase();
      if(/^(next|continue|next page|next chapter)\b|^[›»>→]+$/.test(t)) return c[i];
    }
    return null;
  }
  window.__autoT=setInterval(function(){
    var el=document.scrollingElement||document.documentElement;
    var before=el.scrollTop;
    window.scrollBy(0,__PX__);
    if(Math.abs(el.scrollTop-before)<1){idle++;}else{idle=0;}
    if(idle>=8){idle=0;var n=findNext();if(n){n.click();}}
  },100);
})();
"""
    }
}
