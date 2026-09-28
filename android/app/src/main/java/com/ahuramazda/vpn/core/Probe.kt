package com.ahuramazda.vpn.core

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Reachability and latency measurement for the relay list.
 *
 * A probe does exactly what a real session does — TCP connect, AHURA/1
 * handshake, SOCKS5 method negotiation — and then closes.  It never asks the
 * relay to open a target connection, so a probe costs one round-trip and no
 * traffic, and it also proves that the token and the stealth key are right.
 *
 * This is what makes `autoSelect` trustworthy: the app connects to the relay
 * that actually answered, not to the one that happens to be first in the list.
 * When the VPN is already up, `protect` must be `AhuraVpnService::protectSocket`
 * so the probe sockets do not get routed into the tunnel they are testing.
 */
object ServerProbe {

    class Result(val ok: Boolean, val millis: Int, val message: String)

    fun probe(
        profile: ServerProfile,
        timeoutMs: Int = 4000,
        protect: ((Socket) -> Boolean)? = null
    ): Result {
        if (profile.host.isEmpty()) return Result(false, 0, "no host")
        val started = System.currentTimeMillis()
        var socket: Socket? = null
        return try {
            val s = Socket()
            socket = s
            s.tcpNoDelay = true
            protect?.invoke(s)
            s.connect(InetSocketAddress(profile.host, profile.port), timeoutMs)
            val conn = ObfsStream.handshake(s, profile.stealthKey, profile.token, profile.obfs, timeoutMs)
            try {
                // the SOCKS5 greeting is the relay's second checkpoint
                // (method selection / RFC1929 auth)
                Socks5Client(conn).negotiate(profile.username, profile.password)
            } finally {
                conn.close()
            }
            Result(true, (System.currentTimeMillis() - started).toInt(), "")
        } catch (e: Exception) {
            try {
                socket?.close()
            } catch (ignored: Exception) {
            }
            Result(false, 0, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Probes every configured relay, stores the results, returns the best index. */
    fun probeAll(
        prefs: Prefs,
        timeoutMs: Int = 4000,
        protect: ((Socket) -> Boolean)? = null
    ): Int {
        val profiles = prefs.profiles
        var best = -1
        var bestMs = Int.MAX_VALUE
        profiles.forEachIndexed { index, profile ->
            val result = probe(profile, timeoutMs, protect)
            prefs.noteLatency(profile, if (result.ok) result.millis else -1)
            if (result.ok) {
                Log.i("probe ${profile.label()}: ${result.millis} ms")
                if (result.millis < bestMs) {
                    bestMs = result.millis
                    best = index
                }
            } else {
                Log.w("probe ${profile.label()}: ${result.message}")
            }
        }
        return best
    }
}
