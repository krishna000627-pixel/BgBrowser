package com.bgbrowser

import android.content.Context
import java.io.File

object History {
    class Item(val ts: Long, val url: String, val title: String)
    private var file: File? = null

    fun init(c: Context) { file = File(c.filesDir, "history.tsv") }

    fun add(url: String, title: String) {
        val f = file ?: return
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        if (items(1).firstOrNull()?.url == url) return
        try {
            f.appendText("${System.currentTimeMillis()}\t$url\t${title.replace('\t', ' ').replace('\n', ' ')}\n")
            if (f.length() > 200_000) trim()
        } catch (_: Exception) {}
    }

    fun items(max: Int = 100): List<Item> {
        val f = file ?: return emptyList()
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().asReversed().asSequence().mapNotNull { l ->
                val p = l.split('\t')
                if (p.size >= 2) Item(p[0].toLongOrNull() ?: 0L, p[1], p.getOrElse(2) { "" }) else null
            }.take(max).toList()
        } catch (_: Exception) { emptyList() }
    }

    private fun trim() {
        val f = file ?: return
        try { f.writeText(f.readLines().takeLast(400).joinToString("\n") + "\n") } catch (_: Exception) {}
    }

    fun clear() { try { file?.delete() } catch (_: Exception) {} }
}
