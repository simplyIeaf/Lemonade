package com.leaf.lemonade.core

import java.util.concurrent.CopyOnWriteArrayList

object OutputLog {
    enum class Level { PRINT, INFO, WARN, ERROR }
    class Entry(val time: Long, val level: Level, val text: String)

    private val entries = ArrayList<Entry>()
    val listeners = CopyOnWriteArrayList<(Entry?) -> Unit>()

    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    fun add(level: Level, text: String) {
        val e = Entry(System.currentTimeMillis(), level, text)
        synchronized(entries) {
            entries.add(e)
            if (entries.size > 3000) entries.subList(0, 500).clear()
        }
        try { android.util.Log.i("MobileStudio", "[$level] $text") } catch (_: Throwable) {}
        for (l in listeners) l(e)
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
        for (l in listeners) l(null)
    }

    fun print(s: String) = add(Level.PRINT, s)
    fun info(s: String) = add(Level.INFO, s)
    fun warn(s: String) = add(Level.WARN, s)
    fun error(s: String) = add(Level.ERROR, s)
}
