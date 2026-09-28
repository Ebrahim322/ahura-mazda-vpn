package com.ahuramazda.vpn.core

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap

/** A ready-to-use SOCKS5 UDP association (control connection + relay socket). */
class UdpAssociation(val stream: ObfsStream, val socket: DatagramSocket, val relay: InetSocketAddress)

/** Opens UDP associations on behalf of the relay (implemented by `Tunnel`). */
interface UdpConnector {
    fun openAssociation(): UdpAssociation
}

/**
 * Carries device UDP datagrams (DNS first of all) through the relay's SOCKS5
 * UDP ASSOCIATE.
 *
 * One association per flow: the relay then has no ambiguity about where a
 * reply belongs, which matters most for DNS, where a burst of queries from
 * several sockets hits the same resolver.
 */
class UdpRelay(
    private val sink: PacketSink,
    private val connector: UdpConnector,
    private val stats: Stats,
    private val maxFlows: Int = 64
) {

    private val flows = ConcurrentHashMap<FlowKey, UdpFlow>()

    val flowCount: Int get() = flows.size

    fun onPacket(view: Packet.View) {
        if (view.payloadLength == 0) return
        val key = view.key()
        var flow = flows[key]
        if (flow == null) {
            if (flows.size >= maxFlows) {
                Log.w("UDP: flow limit ($maxFlows) reached, dropping ${key.dst}:${key.dstPort}")
                return
            }
            flow = UdpFlow(key, connector, sink, stats) { flows.remove(it) }
            val raced = flows.putIfAbsent(key, flow)
            if (raced != null) flow = raced else flow.start()
        }
        flow.send(view.payload())
    }

    fun closeAll() {
        for (flow in ArrayList(flows.values)) flow.close()
        flows.clear()
    }
}

internal class UdpFlow(
    private val key: FlowKey,
    private val connector: UdpConnector,
    private val sink: PacketSink,
    private val stats: Stats,
    private val onClosed: (FlowKey) -> Unit
) {

    private val lock = Object()
    private val pending = LinkedList<ByteArray>()
    private var association: UdpAssociation? = null
    private var dead = false
    private var lastActivityAt = System.currentTimeMillis()

    fun start() {
        stats.udpFlowStarted()
        Thread({ connectAndRead() }, "ahura-udp-${key.dstPort}").start()
    }

    fun send(payload: ByteArray) {
        synchronized(lock) {
            if (dead) return
            lastActivityAt = System.currentTimeMillis()
            if (association == null) {
                if (pending.size < 64) pending.addLast(payload)
                return
            }
        }
        transmit(payload)
    }

    private fun transmit(payload: ByteArray) {
        val assoc = association ?: return
        try {
            val header = Socks5Client.packUdpHeader(literal(key.dst), key.dstPort)
            val datagram = ByteArray(header.size + payload.size)
            System.arraycopy(header, 0, datagram, 0, header.size)
            System.arraycopy(payload, 0, datagram, header.size, payload.size)
            assoc.socket.send(DatagramPacket(datagram, datagram.size, assoc.relay))
            stats.uploaded(payload.size.toLong())
        } catch (e: IOException) {
            Log.d("UDP ${key.dst}:$key.dstPort send failed: ${e.message}")
            close()
        }
    }

    private fun connectAndRead() {
        val assoc: UdpAssociation
        try {
            assoc = connector.openAssociation()
        } catch (e: Exception) {
            stats.noteError("udp", e.message ?: e.javaClass.simpleName)
            close()
            return
        }
        synchronized(lock) {
            if (dead) {
                closeAssociation(assoc)
                return
            }
            association = assoc
        }
        // flush anything the device sent while we were connecting
        var queued: ByteArray? = null
        while (true) {
            synchronized(lock) {
                queued = if (pending.isEmpty()) null else pending.removeFirst()
            }
            val chunk = queued ?: break
            transmit(chunk)
        }

        val buffer = ByteArray(65535)
        try {
            while (true) {
                val datagram = DatagramPacket(buffer, buffer.size)
                assoc.socket.receive(datagram)
                synchronized(lock) {
                    lastActivityAt = System.currentTimeMillis()
                    if (dead) return
                }
                val parsed = Socks5Client.parseUdpHeader(datagram.data, datagram.length) ?: continue
                val payloadLength = datagram.length - parsed.payloadOffset
                if (payloadLength <= 0) continue
                val source = try {
                    InetAddress.getByName(parsed.host)
                } catch (e: Exception) {
                    continue
                }
                // buildUdp takes the payload on its own, so hand it an
                // exact-sized copy instead of the whole receive buffer.
                val payload = datagram.data.copyOfRange(parsed.payloadOffset, datagram.length)
                val packet = Packet.buildUdp(source, key.src, parsed.port, key.srcPort, payload, payload.size)
                sink.send(packet)
                stats.downloaded(payloadLength.toLong())
            }
        } catch (e: Exception) {
            if (!dead) Log.d("UDP ${key.dst}:$key.dstPort closed: ${e.message}")
        } finally {
            close()
        }
    }

    fun close() {
        var assoc: UdpAssociation?
        synchronized(lock) {
            if (dead) return
            dead = true
            assoc = association
        }
        if (assoc != null) closeAssociation(assoc)
        onClosed(key)
    }

    private fun closeAssociation(assoc: UdpAssociation) {
        try {
            assoc.socket.close()
        } catch (ignored: Exception) {
        }
        assoc.stream.close()
    }

    private companion object {
        fun literal(addr: InetAddress): String =
            (addr.hostAddress ?: "0.0.0.0").substringBefore('%')
    }
}
