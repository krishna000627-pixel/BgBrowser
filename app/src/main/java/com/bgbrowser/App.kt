package com.bgbrowser

import android.app.Application
import android.os.Build
import android.webkit.WebView
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        History.init(this)
        Bookmarks.init(this)
        val suffix = when (procName()) {
            "$packageName:incognito" -> "incognito"
            "$packageName:tor" -> "tor"
            else -> null
        }
        if (suffix != null && Build.VERSION.SDK_INT >= 28) {
            // Private processes get their own WebView data dir, wiped before WebView touches it.
            try {
                dataDir.listFiles()?.filter { it.name.startsWith("app_webview") && it.name.contains(suffix) }
                    ?.forEach { it.deleteRecursively() }
                cacheDir.listFiles()?.filter { it.name.startsWith("WebView") && it.name.contains(suffix) }
                    ?.forEach { it.deleteRecursively() }
            } catch (_: Exception) {}
            WebView.setDataDirectorySuffix(suffix)
        }
    }

    private fun procName(): String = try {
        File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
    } catch (_: Exception) { packageName }
}
