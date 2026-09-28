package com.ahuramazda.vpn.core

import android.util.Log as AndroidLog
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-app log ring: the service writes, the UI reads.  Also mirrors to logcat.
 * No AndroidX, no third party, so the whole app keeps exactly one dependency
 * (the Kotlin stdlib).
 */
object Log {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    class Entry(val time: Long, val level: Level, val message: String)

    private const val CAPACITY = 500
    private const val TAG = "AhuraVpn"

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val listeners = CopyOnWriteArrayList<(Entry) -> Unit>()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var debugEnabled: Boolean = false

    fun d(message: String) {
        if (debugEnabled) add(Level.DEBUG, message)
    }

    fun i(message: String) = add(Level.INFO, message)

    fun w(message: String) = add(Level.WARN, message)

    fun e(message: String) = add(Level.ERROR, message)

    fun e(message: String, throwable: Throwable) =
        add(Level.ERROR, message + ": " + throwable.javaClass.simpleName + " " + (throwable.message ?: ""))

    fun add(level: Level, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, message)
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > CAPACITY) entries.removeFirst()
        }
        when (level) {
            Level.DEBUG -> AndroidLog.d(TAG, message)
            Level.INFO -> AndroidLog.i(TAG, message)
            Level.WARN -> AndroidLog.w(TAG, message)
            Level.ERROR -> AndroidLog.e(TAG, message)
        }
        for (listener in listeners) {
            try {
                listener(entry)
            } catch (ignored: Throwable) {
            }
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { ArrayList(entries) }

    fun tail(count: Int): List<Entry> = synchronized(lock) {
        val all = ArrayList(entries)
        if (all.size <= count) all else ArrayList(all.subList(all.size - count, all.size))
    }

    fun clear() = synchronized(lock) { entries.clear() }

    fun subscribe(listener: (Entry) -> Unit) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: (Entry) -> Unit) {
        listeners.remove(listener)
    }

    fun format(entry: Entry): String =
        stamp.format(Date(entry.time)) + "  " + entry.level.name.padEnd(5) + "  " + entry.message

    fun asText(): String = snapshot().joinToString("\n") { format(it) }
}
