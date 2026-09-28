package com.ahuramazda.vpn.core

import java.net.InetAddress

/**
 * IPv4 / IPv6 packet parsing and building.
 *
 * The tunnel works at the IP layer: every packet the device hands us through
 * the TUN device is parsed here, the flow it belongs to is looked up, and the
 * reply is built back into a raw IP packet.  Nothing in this class cares about
 * domain names — routing in Ahura is decided purely on IP / CIDR, exactly like
 * the "IP based" filtering this app is built to defeat.
 *
 * Deliberate limits (documented rather than hidden):
 *  * IPv4 fragments are dropped (the device's stack re-sends them whole for
 *    nearly every real-world flow; DF is on for TCP anyway).
 *  * IPv6 extension headers other than the trivial "next header == TCP/UDP"
 *    case are dropped.
 *  * TCP window scaling is ignored: the advertised window is honoured
 *    literally, and the tunnel keeps a fixed in-flight cap of its own.
 */
object Packet {

    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    const val FLAG_FIN = FIN
    const val FLAG_SYN = SYN
    const val FLAG_RST = RST
    const val FLAG_PSH = PSH
    const val FLAG_ACK = ACK

    /** A parsed packet.  Reused buffers keep the tunnel allocation-free. */
    class View {
        var version = 0
        var protocol = 0
        var ttl = 0
        var src: InetAddress? = null
        var dst: InetAddress? = null
        var srcPort = 0
        var dstPort = 0
        var seq = 0L
        var ack = 0L
        var flags = 0
        var window = 0
        var mss = 0
        var payloadOffset = 0
        var payloadLength = 0
        var raw: ByteArray? = null

        val isSyn: Boolean get() = flags and SYN != 0
        val isAck: Boolean get() = flags and ACK != 0
        val isFin: Boolean get() = flags and FIN != 0
        val isRst: Boolean get() = flags and RST != 0

        fun payload(): ByteArray {
            val src = raw ?: return ByteArray(0)
            val out = ByteArray(payloadLength)
            System.arraycopy(src, payloadOffset, out, 0, payloadLength)
            return out
        }

        fun key(): FlowKey = FlowKey(src!!, srcPort, dst!!, dstPort, protocol)
    }

    /** Parse `len` bytes of `data` into [view]; null when the packet is not
     *  something we can tunnel (fragment, unknown protocol, truncated, ...). */
    fun parse(data: ByteArray, len: Int, view: View): Boolean {
        if (len < 20) return false
        val v = (data[0].toInt() ushr 4) and 0x0F
        view.raw = data
        view.version = v
        val transportOffset: Int
        when (v) {
            4 -> {
                val ihl = (data[0].toInt() and 0x0F) * 4
                if (ihl < 20 || len < ihl) return false
                val totalLen = u16(data, 2)
                if (totalLen < ihl || totalLen > len) return false
                val fragField = u16(data, 6)
                if ((fragField and 0x3FFF) != 0) return false          // fragmented
                view.protocol = data[9].toInt() and 0xFF
                view.ttl = data[8].toInt() and 0xFF
                view.src = InetAddress.getByAddress(copy(data, 12, 4))
                view.dst = InetAddress.getByAddress(copy(data, 16, 4))
                transportOffset = ihl
                view.payloadOffset = totalLen                          // replaced below
            }
            6 -> {
                if (len < 40) return false
                val payloadLen = u16(data, 4)
                if (40 + payloadLen > len) return false
                view.protocol = data[6].toInt() and 0xFF
                view.ttl = data[7].toInt() and 0xFF
                if (view.protocol != PROTO_TCP && view.protocol != PROTO_UDP) return false
                view.src = InetAddress.getByAddress(copy(data, 8, 16))
                view.dst = InetAddress.getByAddress(copy(data, 24, 16))
                transportOffset = 40
            }
            else -> return false
        }

        val packetEnd = if (v == 4) u16(data, 2) else 40 + u16(data, 4)
        when (view.protocol) {
            PROTO_TCP -> {
                if (packetEnd - transportOffset < 20) return false
                view.srcPort = u16(data, transportOffset)
                view.dstPort = u16(data, transportOffset + 2)
                view.seq = u32(data, transportOffset + 4)
                view.ack = u32(data, transportOffset + 8)
                val dataOffset = ((data[transportOffset + 12].toInt() ushr 4) and 0x0F) * 4
                if (dataOffset < 20 || transportOffset + dataOffset > packetEnd) return false
                view.flags = data[transportOffset + 13].toInt() and 0xFF
                view.window = u16(data, transportOffset + 14)
                view.mss = parseMss(data, transportOffset + 20, transportOffset + dataOffset)
                view.payloadOffset = transportOffset + dataOffset
                view.payloadLength = packetEnd - view.payloadOffset
            }
            PROTO_UDP -> {
                if (packetEnd - transportOffset < 8) return false
                view.srcPort = u16(data, transportOffset)
                view.dstPort = u16(data, transportOffset + 2)
                view.payloadOffset = transportOffset + 8
                view.payloadLength = packetEnd - view.payloadOffset
                view.flags = 0
            }
            else -> {
                view.payloadOffset = transportOffset
                view.payloadLength = packetEnd - transportOffset
            }
        }
        return true
    }

    private fun parseMss(data: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i + 1 <= to - 1 && i < to) {
            val kind = data[i].toInt() and 0xFF
            if (kind == 0) break
            if (kind == 1) { i++; continue }
            if (i + 1 >= to) break
            val optLen = data[i + 1].toInt() and 0xFF
            if (optLen < 2 || i + optLen > to) break
            if (kind == 2 && optLen == 4) return u16(data, i + 2)
            i += optLen
        }
        return 0
    }

    // ------------------------------------------------------------------
    // building
    // ------------------------------------------------------------------

    /** Build a TCP segment from the tunnel towards the device. */
    fun buildTcp(
        src: InetAddress, dst: InetAddress, srcPort: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int, window: Int, payload: ByteArray, payloadLen: Int,
        synAckMss: Int = 0
    ): ByteArray {
        val v6 = src.address.size == 16
        val optionsLen = if (synAckMss > 0) 4 else 0
        val tcpLen = 20 + optionsLen + payloadLen
        val ipLen = if (v6) 40 else 20
        val out = ByteArray(ipLen + tcpLen)

        // --- IP header ---
        if (v6) {
            out[0] = (0x60).toByte()
            put16(out, 4, tcpLen)
            out[6] = PROTO_TCP.toByte()
            out[7] = 64
            System.arraycopy(src.address, 0, out, 8, 16)
            System.arraycopy(dst.address, 0, out, 24, 16)
        } else {
            out[0] = 0x45
            put16(out, 2, ipLen + tcpLen)
            put16(out, 4, nextId())
            out[8] = 64
            out[9] = PROTO_TCP.toByte()
            System.arraycopy(src.address, 0, out, 12, 4)
            System.arraycopy(dst.address, 0, out, 16, 4)
            put16(out, 10, checksum(out, 0, 20, 0))
        }

        // --- TCP header ---
        val t = ipLen
        put16(out, t, srcPort)
        put16(out, t + 2, dstPort)
        put32(out, t + 4, seq)
        put32(out, t + 8, ack)
        out[t + 12] = (((20 + optionsLen) / 4) shl 4).toByte()
        out[t + 13] = flags.toByte()
        put16(out, t + 14, window)
        if (optionsLen > 0) {
            out[t + 20] = 2
            out[t + 21] = 4
            put16(out, t + 22, synAckMss)
        }
        if (payloadLen > 0) System.arraycopy(payload, 0, out, t + 20 + optionsLen, payloadLen)

        val csum = transportChecksum(src, dst, out, ipLen, tcpLen, PROTO_TCP)
        put16(out, t + 16, csum)
        return out
    }

    /** Build a UDP datagram from the tunnel towards the device. */
    fun buildUdp(
        src: InetAddress, dst: InetAddress, srcPort: Int, dstPort: Int,
        payload: ByteArray, payloadLen: Int
    ): ByteArray {
        val v6 = src.address.size == 16
        val udpLen = 8 + payloadLen
        val ipLen = if (v6) 40 else 20
        val out = ByteArray(ipLen + udpLen)
        if (v6) {
            out[0] = 0x60.toByte()
            put16(out, 4, udpLen)
            out[6] = PROTO_UDP.toByte()
            out[7] = 64
            System.arraycopy(src.address, 0, out, 8, 16)
            System.arraycopy(dst.address, 0, out, 24, 16)
        } else {
            out[0] = 0x45
            put16(out, 2, ipLen + udpLen)
            put16(out, 4, nextId())
            out[8] = 64
            out[9] = PROTO_UDP.toByte()
            System.arraycopy(src.address, 0, out, 12, 4)
            System.arraycopy(dst.address, 0, out, 16, 4)
            put16(out, 10, checksum(out, 0, 20, 0))
        }
        val t = ipLen
        put16(out, t, srcPort)
        put16(out, t + 2, dstPort)
        put16(out, t + 4, udpLen)
        if (payloadLen > 0) System.arraycopy(payload, 0, out, t + 8, payloadLen)
        // UDP over IPv4: a zero checksum means "not computed" and is legal.
        val csum = transportChecksum(src, dst, out, ipLen, udpLen, PROTO_UDP)
        put16(out, t + 6, if (csum == 0) 0xFFFF else csum)
        return out
    }

    /** A RST we send to the device to reject a flow we refuse to carry. */
    fun buildRst(view: View): ByteArray {
        val src = view.dst!!
        val dst = view.src!!
        return buildTcp(src, dst, view.dstPort, view.srcPort, view.ack, view.seq + 1, RST or ACK, 0, EMPTY, 0)
    }

    private val EMPTY = ByteArray(0)
    private val idCounter = java.util.concurrent.atomic.AtomicInteger(1)

    private fun nextId(): Int = idCounter.incrementAndGet() and 0xFFFF

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    fun checksum(data: ByteArray, offset: Int, length: Int, initial: Int): Int {
        var sum = initial
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun transportChecksum(
        src: InetAddress, dst: InetAddress, data: ByteArray,
        offset: Int, length: Int, protocol: Int
    ): Int {
        var sum = 0
        val s = src.address
        val d = dst.address
        var i = 0
        while (i + 1 < s.size) {
            sum += ((s[i].toInt() and 0xFF) shl 8) or (s[i + 1].toInt() and 0xFF)
            sum += ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
            i += 2
        }
        sum += protocol
        sum += length
        var j = offset
        val end = offset + length
        while (j + 1 < end) {
            sum += ((data[j].toInt() and 0xFF) shl 8) or (data[j + 1].toInt() and 0xFF)
            j += 2
        }
        if (j < end) sum += (data[j].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    fun u16(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    fun put16(data: ByteArray, at: Int, value: Int) {
        data[at] = ((value ushr 8) and 0xFF).toByte()
        data[at + 1] = (value and 0xFF).toByte()
    }

    fun u32(data: ByteArray, at: Int): Long =
        ((data[at].toLong() and 0xFF) shl 24) or ((data[at + 1].toLong() and 0xFF) shl 16) or
            ((data[at + 2].toLong() and 0xFF) shl 8) or (data[at + 3].toLong() and 0xFF)

    fun put32(data: ByteArray, at: Int, value: Long) {
        data[at] = ((value ushr 24) and 0xFF).toByte()
        data[at + 1] = ((value ushr 16) and 0xFF).toByte()
        data[at + 2] = ((value ushr 8) and 0xFF).toByte()
        data[at + 3] = (value and 0xFF).toByte()
    }

    fun copy(data: ByteArray, offset: Int, length: Int): ByteArray =
        data.copyOfRange(offset, offset + length)

    /** Sequence-number arithmetic with wrap-around, per RFC 1982 style. */
    fun seqDiff(a: Long, b: Long): Long {
        val diff = (a - b) and 0xFFFFFFFFL
        return if (diff >= 0x80000000L) diff - 0x100000000L else diff
    }
}

/** Identifies one flow (5-tuple) on the TUN device. */
class FlowKey(
    val src: InetAddress, val srcPort: Int,
    val dst: InetAddress, val dstPort: Int,
    val protocol: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FlowKey) return false
        return srcPort == other.srcPort && dstPort == other.dstPort &&
            protocol == other.protocol && src == other.src && dst == other.dst
    }

    override fun hashCode(): Int {
        var h = src.hashCode()
        h = h * 31 + dst.hashCode()
        h = h * 31 + srcPort
        h = h * 31 + dstPort
        h = h * 31 + protocol
        return h
    }

    override fun toString(): String = "$dst:$dstPort"
}
