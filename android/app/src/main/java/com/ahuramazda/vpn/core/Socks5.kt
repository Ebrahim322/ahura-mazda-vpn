package com.ahuramazda.vpn.core

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException

/**
 * SOCKS5 client (RFC 1928 + RFC 1929), running on top of [ObfsStream].
 *
 * Two things make it different from a textbook SOCKS5 client:
 *
 *  * CONNECT is always issued with a **literal IP address** — the tunnel
 *    already knows the destination from the IP header, so no name resolution
 *    happens on the device.  That is the whole point of an IP based design:
 *    poisoned DNS or domain filtering on the local network cannot influence
 *    which host we reach, and the relay sees exactly the IP the device asked
 *    for.
 *  * UDP goes through UDP ASSOCIATE, so a single relay connection can carry
 *    both TCP streams and UDP datagrams (DNS included).
 */
class Socks5Client(private val stream: ObfsStream) {

    private var negotiated = false

    /** Greeting + optional username/password auth. */
    fun negotiate(user: String, password: String) {
        if (negotiated) return
        val wantAuth = user.isNotEmpty()
        val greeting = if (wantAuth) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00)
        stream.write(greeting)
        stream.flush()
        val reply = ByteArray(2)
        if (stream.readFully(reply, 0, 2) < 2) throw IOException("SOCKS5: relay closed during greeting")
        if (reply[0].toInt() != 0x05) throw IOException("SOCKS5: bad version ${reply[0]}")
        when (reply[1].toInt() and 0xFF) {
            0x00 -> Unit
            0x02 -> authenticate(user, password)
            else -> throw IOException("SOCKS5: relay demands an unsupported auth method (0x%02x)".format(reply[1]))
        }
        negotiated = true
    }

    private fun authenticate(user: String, password: String) {
        val u = user.toByteArray(Charsets.UTF_8)
        val p = password.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(3 + u.size + p.size)
        buf[0] = 0x01
        buf[1] = u.size.toByte()
        System.arraycopy(u, 0, buf, 2, u.size)
        buf[2 + u.size] = p.size.toByte()
        System.arraycopy(p, 0, buf, 3 + u.size, p.size)
        stream.write(buf)
        stream.flush()
        val reply = ByteArray(2)
        if (stream.readFully(reply, 0, 2) < 2 || reply[1].toInt() != 0x00) {
            throw IOException("SOCKS5: username/password rejected")
        }
    }

    /** CONNECT to `host` (a literal IP) and return the relay's bound address. */
    fun connect(host: String, port: Int): InetSocketAddress {
        val request = byteArrayOf(0x05, 0x01, 0x00) + packAddr(host, port)
        stream.write(request)
        stream.flush()
        val head = ByteArray(4)
        if (stream.readFully(head, 0, 4) < 4) throw IOException("SOCKS5: relay closed on CONNECT")
        val atyp = head[3].toInt() and 0xFF
        val bound = readAddrBody(atyp)
        val rep = head[1].toInt() and 0xFF
        if (rep != 0x00) throw IOException("SOCKS5: CONNECT to $host:$port failed — " + repText(rep))
        return bound
    }

    /** UDP ASSOCIATE; returns the relay endpoint user datagrams must be sent to. */
    fun udpAssociate(relayHost: String): InetSocketAddress {
        val request = byteArrayOf(0x05, 0x03, 0x00) + packAddr("0.0.0.0", 0)
        stream.write(request)
        stream.flush()
        val head = ByteArray(4)
        if (stream.readFully(head, 0, 4) < 4) throw IOException("SOCKS5: relay closed on UDP ASSOCIATE")
        val atyp = head[3].toInt() and 0xFF
        val bound = readAddrBody(atyp)
        val rep = head[1].toInt() and 0xFF
        if (rep != 0x00) throw IOException("SOCKS5: UDP ASSOCIATE failed — " + repText(rep))
        // A relay behind NAT answers with an unspecified address; in that case
        // the datagrams go to the relay's host, only the port matters.
        val address = bound.address
        val usable = address != null && !address.isAnyLocalAddress && !address.isLoopbackAddress
        val host = if (usable) address.hostAddress else relayHost
        return InetSocketAddress(host, bound.port)
    }

    private fun readAddrBody(atyp: Int): InetSocketAddress {
        when (atyp) {
            0x01 -> {
                val buf = ByteArray(6)
                if (stream.readFully(buf, 0, 6) < 6) throw IOException("SOCKS5: truncated IPv4 reply")
                return InetSocketAddress(InetAddress.getByAddress(Packet.copy(buf, 0, 4)), Packet.u16(buf, 4))
            }
            0x04 -> {
                val buf = ByteArray(18)
                if (stream.readFully(buf, 0, 18) < 18) throw IOException("SOCKS5: truncated IPv6 reply")
                return InetSocketAddress(InetAddress.getByAddress(Packet.copy(buf, 0, 16)), Packet.u16(buf, 16))
            }
            0x03 -> {
                val len = stream.readByte()
                if (len <= 0) throw IOException("SOCKS5: truncated domain reply")
                val name = ByteArray(len)
                if (stream.readFully(name, 0, len) < len) throw IOException("SOCKS5: truncated domain reply")
                val portBuf = ByteArray(2)
                if (stream.readFully(portBuf, 0, 2) < 2) throw IOException("SOCKS5: truncated domain reply")
                val host = String(name, Charsets.UTF_8)
                return InetSocketAddress(host, Packet.u16(portBuf, 0))
            }
            else -> throw IOException("SOCKS5: unsupported address type $atyp")
        }
    }

    companion object {

        fun packAddr(host: String, port: Int): ByteArray {
            val literal = try {
                InetAddress.getByName(host)
            } catch (e: UnknownHostException) {
                null
            }
            // Only literals are accepted: resolving a name here would defeat
            // the IP based design (and the tunnel never needs it).
            if (literal != null && literal.hostAddress != null && isLiteral(host)) {
                val bytes = literal.address
                val out = ByteArray(1 + bytes.size + 2)
                out[0] = if (bytes.size == 16) 0x04 else 0x01
                System.arraycopy(bytes, 0, out, 1, bytes.size)
                Packet.put16(out, 1 + bytes.size, port)
                return out
            }
            throw IOException("SOCKS5: '$host' is not an IP literal — Ahura tunnels IPs, not names")
        }

        private fun isLiteral(host: String): Boolean =
            host.isNotEmpty() && (host[0].isDigit() || host.contains(':'))

        /** Header for a datagram carried inside a UDP ASSOCIATE tunnel. */
        fun packUdpHeader(host: String, port: Int): ByteArray =
            byteArrayOf(0x00, 0x00, 0x00) + packAddr(host, port)

        /** Parses a datagram received from the relay; returns (ip, port, offset). */
        class UdpPacket(val host: String, val port: Int, val payloadOffset: Int)

        fun parseUdpHeader(data: ByteArray, length: Int): UdpPacket? {
            if (length < 4) return null
            if (data[2].toInt() != 0) return null                  // fragments unsupported
            val atyp = data[3].toInt() and 0xFF
            var offset = 4
            val host: String
            when (atyp) {
                0x01 -> {
                    if (length < offset + 4 + 2) return null
                    host = InetAddress.getByAddress(Packet.copy(data, offset, 4)).hostAddress ?: return null
                    offset += 4
                }
                0x04 -> {
                    if (length < offset + 16 + 2) return null
                    host = InetAddress.getByAddress(Packet.copy(data, offset, 16)).hostAddress ?: return null
                    offset += 16
                }
                0x03 -> {
                    if (length < offset + 1) return null
                    val len = data[offset].toInt() and 0xFF
                    offset += 1
                    if (length < offset + len + 2) return null
                    host = String(data, offset, len, Charsets.UTF_8)
                    offset += len
                }
                else -> return null
            }
            val port = Packet.u16(data, offset)
            return UdpPacket(host, port, offset + 2)
        }

        private fun repText(rep: Int): String = when (rep) {
            1 -> "general failure"
            2 -> "not allowed by relay rules"
            3 -> "network unreachable"
            4 -> "host unreachable"
            5 -> "connection refused"
            6 -> "TTL expired"
            7 -> "command not supported"
            8 -> "address type not supported"
            else -> "error $rep"
        }
    }
}
