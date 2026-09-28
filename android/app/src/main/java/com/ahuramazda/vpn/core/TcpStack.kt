package com.ahuramazda.vpn.core

import java.io.IOException
import java.net.InetAddress
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap

/** Anything that can push an IP packet back into the TUN device. */
interface PacketSink {
    fun send(packet: ByteArray): Boolean
}

/** Opens relay tunnels on behalf of the TCP stack (implemented by `Tunnel`). */
interface TunnelConnector {
    fun openStream(dst: InetAddress, dstPort: Int): ObfsStream
}

/**
 * A userspace TCP endpoint, driven by the packets the device's own IP stack
 * sends into the TUN device, with the payload carried to the relay over a
 * SOCKS5 CONNECT stream.
 *
 * The device is always the active opener (it is the one making connections),
 * so this stack is a passive opener: SYN -> connect the relay -> SYN-ACK.
 *
 * What it implements:
 *  * SYN / SYN-ACK / ACK handshake with our own ISN and MSS option,
 *  * in-order data both ways with RFC-793 style ACKs,
 *  * receive-window and in-flight flow control towards the device,
 *  * a retransmission timer with exponential back-off and a give-up limit,
 *  * FIN in both directions (half-close) plus RST,
 *  * zero-window probing so a stalled peer cannot wedge the flow,
 *  * idle timeouts so flows cannot leak.
 *
 * Deliberate simplifications (they cost efficiency, never correctness):
 *  * out-of-order segments are not buffered — we re-ACK the expected
 *    sequence and let the device's stack retransmit (the TUN side is a
 *    loopback-quality path, so this is rare),
 *  * no SACK, no window scaling, no timestamps.
 */
class TcpStack(
    private val sink: PacketSink,
    private val connector: TunnelConnector,
    private val stats: Stats,
    private val maxFlows: Int = 128
) {

    private val flows = ConcurrentHashMap<FlowKey, TcpFlow>()

    val flowCount: Int get() = flows.size

    /** Called from the single TUN reader thread. */
    fun onPacket(view: Packet.View) {
        if (view.protocol != Packet.PROTO_TCP) return
        val key = view.key()
        val existing = flows[key]
        if (existing != null) {
            existing.onSegment(view)
            return
        }
        if (!view.isSyn || view.isAck) {
            if (!view.isRst) sink.send(Packet.buildRst(view))
            return
        }
        if (flows.size >= maxFlows) {
            Log.w("TCP: flow limit ($maxFlows) reached, refusing ${key.dst}:${key.dstPort}")
            sink.send(Packet.buildRst(view))
            return
        }
        val flow = TcpFlow(key, view.mss, sink, connector, stats, this)
        val raced = flows.putIfAbsent(key, flow)
        if (raced != null) {
            raced.onSegment(view)
            return
        }
        flow.start()
    }

    internal fun remove(key: FlowKey, flow: TcpFlow) {
        flows.remove(key, flow)
    }

    fun closeAll() {
        for (flow in ArrayList(flows.values)) flow.abort(silent = true)
        flows.clear()
    }
}

/**
 * One TCP flow: device stack <-> relay stream.
 *
 * Threads per flow: a reader (relay -> device queue) and a pump (device queue
 * -> relay, plus retransmission and FIN/close bookkeeping).  The TUN reader
 * thread only ever hands segments in, never blocks on I/O.
 */
internal class TcpFlow(
    private val key: FlowKey,
    synMss: Int,
    private val sink: PacketSink,
    private val connector: TunnelConnector,
    private val stats: Stats,
    private val stack: TcpStack
) {

    private val lock = Object()
    private val remoteAddr: InetAddress = key.dst
    private val localAddr: InetAddress = key.src
    private val mss: Int = when {
        synMss in 536..1400 -> synMss
        remoteAddr.address.size == 16 -> 1400
        else -> 1400
    }

    // device-facing TCP state
    private var sndUna = 0L
    private var sndNxt = 0L
    private var rcvNxt: Long = 0
    private var peerWindow = 65535
    private var synAcked = false
    private var localFinSent = false
    private var upstreamFinPending = false
    private var upstreamFinSent = false
    private var upstreamEof = false

    // queues
    private val toDevice = LinkedList<ByteArray>()      // relay -> device
    private val toUpstream = LinkedList<ByteArray>()    // device -> relay
    private val inFlight = LinkedList<Sent>()           // unacknowledged segments

    private var toUpstreamBytes = 0
    private var toDeviceBytes = 0
    private var lastSendAt = 0L
    private var lastActivityAt = System.currentTimeMillis()
    private var zeroWindowSince = 0L
    private var rto = RTO_INITIAL
    private var retries = 0
    private var dead = false
    private var stream: ObfsStream? = null

    private class Sent(val seq: Long, val data: ByteArray)

    fun start() {
        synchronized(lock) {
            // ISN: time based, per RFC 6528 style spirit (uniqueness is what
            // actually matters here).
            sndNxt = (System.currentTimeMillis() * 4096L) and 0xFFFFFFFFL
            sndUna = sndNxt
            rcvNxt = 0
            localFinSent = false
            dead = false
            lastSendAt = System.currentTimeMillis()
            lastActivityAt = lastSendAt
        }
        Thread({ handshakeAndRead() }, "ahura-tcp-${key.dstPort}").start()
        Thread({ pump() }, "ahura-pump-${key.dstPort}").start()
    }

    private fun handshakeAndRead() {
        val conn: ObfsStream
        try {
            conn = connector.openStream(remoteAddr, key.dstPort)
        } catch (e: Exception) {
            Log.d("TCP ${remoteAddr.hostAddress}:${key.dstPort} refused: ${e.message}")
            stats.noteError("connect", e.message ?: e.javaClass.simpleName)
            reject()
            return
        }
        synchronized(lock) {
            if (dead) {
                conn.close()
                return
            }
            stream = conn
            // SYN-ACK: our ISN, acknowledging the device's SYN.
            sendLocked(Packet.FLAG_SYN or Packet.FLAG_ACK, EMPTY, synAckMss = mss)
            sndNxt += 1                       // SYN consumes one sequence number
            synAcked = true
            lastSendAt = System.currentTimeMillis()
        }
        stats.tcpFlowStarted()

        val buffer = ByteArray(RELAY_READ)
        try {
            while (true) {
                val n = conn.read(buffer, 0, buffer.size)
                if (n <= 0) break
                val chunk = buffer.copyOf(n)
                synchronized(lock) {
                    toDevice.addLast(chunk)
                    toDeviceBytes += n
                    lastActivityAt = System.currentTimeMillis()
                    lock.notifyAll()
                }
            }
        } catch (e: IOException) {
            Log.d("TCP ${key.dstPort}: relay stream ended (${e.message})")
        } finally {
            synchronized(lock) {
                upstreamEof = true
                lock.notifyAll()
            }
        }
    }

    /** Called by the TUN reader thread for every segment of this flow. */
    fun onSegment(view: Packet.View) {
        synchronized(lock) {
            if (dead) return
            lastActivityAt = System.currentTimeMillis()
            if (view.isRst) {
                abort(silent = false)
                return
            }
            if (view.isAck) {
                val ack = view.ack
                if (Packet.seqDiff(ack, sndUna) > 0 && Packet.seqDiff(ack, sndNxt) <= 0) {
                    sndUna = ack
                    while (inFlight.isNotEmpty() && Packet.seqDiff(inFlight.first.seq, sndUna) < 0) {
                        val sent = inFlight.first
                        if (Packet.seqDiff(sent.seq + sent.data.size, sndUna) <= 0) {
                            inFlight.removeFirst()
                        } else {
                            break
                        }
                    }
                    rto = RTO_INITIAL
                    retries = 0
                }
                peerWindow = view.window
                if (peerWindow > 0) zeroWindowSince = 0
            }

            if (view.payloadLength > 0) {
                val diff = Packet.seqDiff(view.seq, rcvNxt)
                if (diff == 0L) {
                    val data = view.payload()
                    rcvNxt += data.size
                    toUpstream.addLast(data)
                    toUpstreamBytes += data.size
                    stats.downloaded(data.size.toLong())
                    lock.notifyAll()
                    sendAckLocked()
                } else {
                    // duplicate or out of order: re-ack what we expect and let
                    // the device's stack retransmit.
                    if (diff < 0) sendAckLocked()
                }
            }

            if (view.isFin) {
                val end = view.seq + view.payloadLength
                if (Packet.seqDiff(end, rcvNxt) == 0L) {
                    rcvNxt += 1
                    upstreamFinPending = true
                    lock.notifyAll()
                    sendAckLocked()
                    if (localFinSent) {
                        // both directions are closed and acknowledged
                        finish()
                    }
                }
            }
        }
    }

    private fun pump() {
        while (true) {
            var writeChunk: ByteArray? = null
            var closeUpstream = false
            var sendFinToDevice = false
            var idle = false
            synchronized(lock) {
                if (dead) return
                transmitLocked()
                when {
                    toUpstream.isNotEmpty() -> writeChunk = toUpstream.removeFirst()
                    upstreamFinPending && !upstreamFinSent -> {
                        upstreamFinSent = true
                        closeUpstream = true
                    }
                    upstreamEof && toDevice.isEmpty() && !localFinSent && synAcked -> {
                        sendFinToDevice = true
                    }
                    else -> idle = true
                }
            }

            val chunk = writeChunk
            writeChunk = null
            if (chunk != null) {
                val conn = stream
                if (conn != null) {
                    try {
                        conn.write(chunk, 0, chunk.size)
                        conn.flush()
                        synchronized(lock) {
                            toUpstreamBytes -= chunk.size
                            stats.uploaded(chunk.size.toLong())
                            lastActivityAt = System.currentTimeMillis()
                        }
                    } catch (e: IOException) {
                        Log.d("TCP ${key.dstPort}: write to relay failed (${e.message})")
                        abort(silent = false)
                        return
                    }
                } else {
                    synchronized(lock) { toUpstream.addFirst(chunk) }
                    idle = true
                }
            }

            if (closeUpstream) {
                stream?.flush()
                stream?.shutdownOutput()
            }

            if (sendFinToDevice) {
                synchronized(lock) {
                    sendLocked(Packet.FLAG_FIN or Packet.FLAG_ACK, EMPTY)
                    sndNxt += 1
                    localFinSent = true
                }
            }

            if (idle) {
                synchronized(lock) {
                    if (dead) return
                    val now = System.currentTimeMillis()
                    if (now - lastActivityAt > IDLE_TIMEOUT_MS) {
                        Log.d("TCP ${key.dstPort}: idle timeout, closing flow")
                        abort(silent = false)
                        return
                    }
                    transmitLocked()
                    if (upstreamEof && toDevice.isEmpty() && toUpstream.isEmpty() &&
                        localFinSent && upstreamFinSent
                    ) {
                        finish()
                        return
                    }
                    try {
                        lock.wait(80)
                    } catch (ignored: InterruptedException) {
                    }
                }
            }
        }
    }

    /** Send everything the device side allows.  Called with `lock` held. */
    private fun transmitLocked() {
        if (dead) return
        val now = System.currentTimeMillis()
        if (inFlight.isNotEmpty() && now - lastSendAt >= rto) {
            val sent = inFlight.first
            sendRawLocked(Packet.FLAG_ACK or Packet.FLAG_PSH, sent.data)
            lastSendAt = now
            retries++
            rto = if (rto * 2 > RTO_MAX) RTO_MAX else rto * 2
            if (retries > MAX_RETRIES) {
                Log.w("TCP ${key.dstPort}: giving up after $retries retransmissions")
                stats.noteError("tcp", "retransmission limit reached")
                abort(silent = false)
            }
            return
        }
        while (toDevice.isNotEmpty()) {
            val inFlightBytes = Packet.seqDiff(sndNxt, sndUna)
            val window = if (peerWindow.toLong() < MAX_IN_FLIGHT) peerWindow.toLong() else MAX_IN_FLIGHT
            val allowed = window - inFlightBytes
            if (allowed <= 0) {
                if (peerWindow == 0) {
                    if (zeroWindowSince == 0L) zeroWindowSince = now
                    if (now - zeroWindowSince > ZERO_WINDOW_PROBE_MS) {
                        zeroWindowSince = now
                        probePeer()
                    }
                }
                return
            }
            val head = toDevice.first
            val take = when {
                head.size.toLong() <= allowed -> head.size
                allowed < mss.toLong() -> allowed.toInt()
                else -> mss
            }
            if (take <= 0) return
            val chunk = if (take == head.size) {
                toDevice.removeFirst()
            } else {
                toDevice.removeFirst()
                toDevice.addFirst(head.copyOfRange(take, head.size))
                head.copyOf(take)
            }
            toDeviceBytes -= chunk.size
            sendLocked(Packet.FLAG_ACK or Packet.FLAG_PSH, chunk)
            inFlight.addLast(Sent(sndNxt, chunk))
            sndNxt += chunk.size
            lastSendAt = now
            retries = 0
        }
    }

    /** Zero-window probe: resend the last byte we sent, as RFC 1122 allows. */
    private fun probePeer() {
        val sent = inFlight.lastOrNull() ?: return
        val one = sent.data.copyOfRange(sent.data.size - 1, sent.data.size)
        val base = sndNxt - 1
        val packet = Packet.buildTcp(
            remoteAddr, localAddr, key.dstPort, key.srcPort,
            base, rcvNxt, Packet.FLAG_ACK, receiveWindow(), one, one.size
        )
        sink.send(packet)
    }

    private fun sendAckLocked() = sendLocked(Packet.FLAG_ACK, EMPTY)

    private fun sendLocked(flags: Int, payload: ByteArray, synAckMss: Int = 0) {
        sendRawLocked(flags, payload, synAckMss)
    }

    private fun sendRawLocked(flags: Int, payload: ByteArray, synAckMss: Int = 0) {
        val packet = Packet.buildTcp(
            remoteAddr, localAddr, key.dstPort, key.srcPort,
            sndNxt, rcvNxt, flags, receiveWindow(), payload, payload.size, synAckMss
        )
        sink.send(packet)
    }

    private fun receiveWindow(): Int {
        // Advertise what we can actually buffer: what is already queued plus
        // the space left before the cap.
        val queued = toDeviceBytes
        val free = RECEIVE_CAP - queued
        return if (free < 0) 0 else free
    }

    private fun finish() {
        if (dead) return
        dead = true
        stream?.close()
        stack.remove(key, this)
        stats.tcpFlowFinished()
        lock.notifyAll()
    }

    /** We could not reach the relay: RST the device so the app fails fast. */
    private fun reject() {
        synchronized(lock) {
            if (dead) return
            sendLocked(Packet.FLAG_RST or Packet.FLAG_ACK, EMPTY)
            dead = true
        }
        stack.remove(key, this)
        stats.tcpFlowFinished()
    }

    fun abort(silent: Boolean) {
        synchronized(lock) {
            if (dead) return
            dead = true
            if (!silent) {
                // RST the device side: it is the fastest way to tell the app
                // that the connection is gone instead of a half-open socket.
                try {
                    sendRawLocked(Packet.FLAG_RST or Packet.FLAG_ACK, EMPTY)
                } catch (ignored: RuntimeException) {
                }
            }
            stream?.close()
        }
        stack.remove(key, this)
        stats.tcpFlowFinished()
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val RELAY_READ = 8192
        const val MAX_IN_FLIGHT = 262144L
        const val RECEIVE_CAP = 262144
        const val RTO_INITIAL = 1000L
        const val RTO_MAX = 8000L
        const val MAX_RETRIES = 8
        const val IDLE_TIMEOUT_MS = 180_000L
        const val ZERO_WINDOW_PROBE_MS = 2000L
    }
}
