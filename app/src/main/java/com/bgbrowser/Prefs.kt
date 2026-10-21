package com.bgbrowser

import android.content.Context
import java.io.File
import java.util.Properties

/** Tiny settings store that is safe to read from the :incognito and :tor processes (reloads when the file changes). */
object Prefs {
    private var file: File? = null
    private val map = HashMap<String, String>()
    private var stamp = -2L

    fun init(c: Context) {
        file = File(c.filesDir, "settings.properties")
        load()
    }

    @Synchronized private fun load() {
        map.clear()
        val f = file ?: return
        stamp = f.lastModified()
        if (!f.exists()) return
        try {
            val p = Properties()
            f.inputStream().use { p.load(it) }
            for (k in p.stringPropertyNames()) map[k] = p.getProperty(k)
        } catch (_: Exception) {}
    }

    @Synchronized private fun refresh() {
        val f = file ?: return
        if (f.lastModified() != stamp) load()
    }

    @Synchronized private fun save() {
        val f = file ?: return
        try {
            val p = Properties()
            for ((k, v) in map) p.setProperty(k, v)
            val tmp = File(f.path + ".tmp")
            tmp.outputStream().use { p.store(it, null) }
            tmp.renameTo(f)
            stamp = f.lastModified()
        } catch (_: Exception) {}
    }

    @Synchronized fun str(k: String, d: String = ""): String { refresh(); return map[k] ?: d }
    fun bool(k: String, d: Boolean): Boolean {
        val v = str(k, "")
        return if (v.isEmpty()) d else (v == "1" || v == "true")
    }
    fun int(k: String, d: Int): Int = str(k, "").toIntOrNull() ?: d

    @Synchronized fun put(k: String, v: Any?) {
        refresh()
        if (v == null) map.remove(k) else map[k] = v.toString()
        save()
    }
    @Synchronized fun remove(k: String) { refresh(); map.remove(k); save() }
    @Synchronized fun keys(prefix: String): List<String> { refresh(); return map.keys.filter { it.startsWith(prefix) } }
    @Synchronized fun removePrefix(prefix: String) { refresh(); map.keys.filter { it.startsWith(prefix) }.forEach { map.remove(it) }; save() }

    fun list(k: String): MutableSet<String> = str(k, "").split(",").filter { it.isNotBlank() }.toMutableSet()
    fun putList(k: String, s: Set<String>) = put(k, s.joinToString(","))
}
