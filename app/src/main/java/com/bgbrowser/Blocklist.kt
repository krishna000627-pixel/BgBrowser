package com.bgbrowser

import java.io.File
import android.content.Context

/** Small built-in list of common tracking and ad domains. A host matches when it or any parent domain is listed. */
object Blocklist {
    private val hosts = setOf(
        "google-analytics.com", "googletagmanager.com", "googletagservices.com", "googlesyndication.com",
        "doubleclick.net", "googleadservices.com", "adservice.google.com", "connect.facebook.net", "facebook.net",
        "scorecardresearch.com", "quantserve.com", "hotjar.com", "hotjar.io", "mixpanel.com", "segment.io",
        "segment.com", "amplitude.com", "fullstory.com", "mouseflow.com", "crazyegg.com", "clarity.ms",
        "bat.bing.com", "ads.twitter.com", "analytics.twitter.com", "ads-twitter.com", "analytics.tiktok.com",
        "business-api.tiktok.com", "px.ads.linkedin.com", "snap.licdn.com", "ads.linkedin.com", "adsrvr.org",
        "adnxs.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "rubiconproject.com",
        "pubmatic.com", "openx.net", "casalemedia.com", "amazon-adsystem.com", "moatads.com", "chartbeat.com",
        "chartbeat.net", "adform.net", "bidswitch.net", "demdex.net", "krxd.net", "bluekai.com",
        "everesttech.net", "mc.yandex.ru", "stats.wp.com", "pixel.wp.com", "ct.pinterest.com",
        "analytics.pinterest.com", "imrworldwide.com", "addthis.com", "sharethis.com", "statcounter.com",
        "ads.yahoo.com", "analytics.yahoo.com", "advertising.com", "2mdn.net", "serving-sys.com",
        "smartadserver.com", "teads.tv", "media.net", "tapad.com", "mathtag.com", "agkn.com", "rlcdn.com"
    )

    fun isTracker(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        while (true) {
            if (hosts.contains(h)) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }
}

/** Saved pages for the normal browser. Private tabs never write here. */
object Bookmarks {
    class Item(val url: String, val title: String)
    private var file: File? = null

    fun init(c: Context) { file = File(c.filesDir, "bookmarks.tsv") }

    fun items(): List<Item> {
        val f = file ?: return emptyList()
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().mapNotNull { l ->
                val p = l.split('\t')
                if (p.isNotEmpty() && p[0].isNotBlank()) Item(p[0], p.getOrElse(1) { "" }) else null
            }
        } catch (_: Exception) { emptyList() }
    }

    fun has(url: String) = items().any { it.url == url }

    fun add(url: String, title: String) {
        val f = file ?: return
        if (has(url)) return
        try { f.appendText(url + "\t" + title.replace('\t', ' ').replace('\n', ' ') + "\n") } catch (_: Exception) {}
    }

    fun remove(url: String) {
        val f = file ?: return
        try {
            val keep = items().filter { it.url != url }
            f.writeText(keep.joinToString("") { it.url + "\t" + it.title.replace('\t', ' ') + "\n" })
        } catch (_: Exception) {}
    }
}
