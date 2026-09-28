package com.ahuramazda.vpn.core

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Live counters shown in the UI, the notification and the log. */
class Stats {

    val startedAt = System.currentTimeMillis()
    private val up = AtomicLong()
    private val down = AtomicLong()
    private val tcpStarted = AtomicInteger()
    private val udpStarted = AtomicInteger()
    private val errors = AtomicInteger()

    @Volatile
    var tcpFlows: Int = 0

    @Volatile
    var udpFlows: Int = 0

    @Volatile
    var lastError: String = ""

    @Volatile
    var lastErrorAt: Long = 0

    fun uploaded(bytes: Long) {
        up.addAndGet(bytes)
    }

    fun downloaded(bytes: Long) {
        down.addAndGet(bytes)
    }

    fun tcpFlowStarted() {
        tcpStarted.incrementAndGet()
    }

    fun tcpFlowFinished() {
    }

    fun udpFlowStarted() {
        udpStarted.incrementAndGet()
    }

    fun noteError(where: String, message: String) {
        errors.incrementAndGet()
        lastError = "$where: $message"
        lastErrorAt = System.currentTimeMillis()
        Log.w("error ($where): $message")
    }

    val uploadedBytes: Long get() = up.get()

    val downloadedBytes: Long get() = down.get()

    val totalBytes: Long get() = up.get() + down.get()

    val tcpConnections: Int get() = tcpStarted.get()

    val udpConnections: Int get() = udpStarted.get()

    val errorCount: Int get() = errors.get()

    val uptimeSeconds: Long get() = (System.currentTimeMillis() - startedAt) / 1000

    fun reset() {
        up.set(0)
        down.set(0)
        tcpStarted.set(0)
        udpStarted.set(0)
        errors.set(0)
        lastError = ""
    }

    companion object {
        /** "12.4 MB" — used by the notification and the home screen. */
        fun human(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024.0
            var index = 0
            while (value >= 1024 && index < units.size - 1) {
                value /= 1024.0
                index++
            }
            return if (value < 10) String.format(java.util.Locale.US, "%.1f %s", value, units[index])
            else String.format(java.util.Locale.US, "%.0f %s", value, units[index])
        }

        fun duration(seconds: Long): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(java.util.Locale.US, "%02d:%02d", m, s)
        }
    }
}
