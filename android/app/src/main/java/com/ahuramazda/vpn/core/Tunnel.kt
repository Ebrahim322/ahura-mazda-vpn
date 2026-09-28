package com.ahuramazda.vpn.core

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/** Everything the engine needs, snapshotted from the settings. */
class TunnelConfig(
    val serverHost: String,
    val serverPort: Int,
    val stealthKey: String,
    val token: String,
    val obfs: String,
    val username: String,
    val password: String,
    val rules: RuleSet,
    val enableV6: Boolean,
    val udpEnabled: Boolean,
    val dnsPrimary: String,
    val dnsSecondary: String,
    val mtu: Int,
    val maxFlows: Int = 128
)

/**
 * The tunnel engine: reads IP packets out of the TUN device, decides what to
 * do with each flow by IP, and carries it to the relay.
 *
 * The decision is always made on the destination **IP** (never a name), which
 * is the design point of this app: a poisoned or blocked DNS server, a
 * transparent proxy, or a domain-based blocklist on the local network cannot
 * stop or redirect the tunnel, because no name is ever resolved on the device.
 */
class Tunnel(
    private val device: ParcelFileDescriptor,
    private val config: TunnelConfig,
    private val stats: Stats
) : PacketSink, TunnelConnector, UdpConnector {

    private val running = AtomicBoolean(false)
    private val writeLock = Object()
    private val input = FileInputStream(device.fileDescriptor)
    private val output = FileOutputStream(device.fileDescriptor)
    private val tcp = TcpStack(this, this, stats, config.maxFlows)
    private val udp = UdpRelay(this, this, stats)

    @Volatile
    private var stopReason: String = ""

    val activeTcpFlows: Int get() = tcp.flowCount

    val activeUdpFlows: Int get() = udp.flowCount

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Log.i("tunnel up: ${config.serverHost}:${config.serverPort} obfs=${config.obfs} " +
            "udp=${config.udpEnabled} mtu=${config.mtu} tcp=${tcp.flowCount} rules=${config.rules.rules.size}")
        Thread({ readLoop() }, "ahura-tun").start()
    }

    fun stop(reason: String) {
        if (!running.compareAndSet(true, false)) return
        stopReason = reason
        Log.i("tunnel down: $reason")
        tcp.closeAll()
        udp.closeAll()
        try {
            input.close()
        } catch (ignored: IOException) {
        }
        try {
            output.close()
        } catch (ignored: IOException) {
        }
    }

    val status: String
        get() = if (running.get()) "running" else if (stopReason.isEmpty()) "stopped" else "stopped ($stopReason)"

    private fun readLoop() {
        val buffer = ByteArray(config.mtu + 64)
        val view = Packet.View()
        while (running.get()) {
            val length = try {
                input.read(buffer)
            } catch (e: IOException) {
                if (running.get()) Log.e("TUN read failed", e)
                break
            }
            if (length <= 0) continue
            if (!Packet.parse(buffer, length, view)) continue
            stats.tcpFlows = tcp.flowCount
            stats.udpFlows = udp.flowCount
            when (view.protocol) {
                Packet.PROTO_TCP -> handleTcp(view)
                Packet.PROTO_UDP -> handleUdp(view)
                else -> {
                    // ICMP and friends: nothing to relay, drop quietly.
                    Log.d("dropping protocol ${view.protocol}")
                }
            }
        }
        if (running.get()) {
            Log.w("TUN device closed unexpectedly")
            stop("tun closed")
        }
    }

    private fun handleTcp(view: Packet.View) {
        val dst = view.dst ?: return
        when (config.rules.actionFor(dst)) {
            IpAction.PROXY -> tcp.onPacket(view)
            IpAction.BLOCK -> {
                stats.noteError("block", dst.hostAddress ?: "?")
                send(Packet.buildRst(view))
            }
            IpAction.DIRECT -> {
                // Traffic the user asked to bypass should never have entered
                // the tunnel; if it did (older Android without excludeRoute),
                // refuse it loudly instead of leaking it in the clear.
                Log.d("DROP direct-rule packet to ${dst.hostAddress} (route not excluded)")
                send(Packet.buildRst(view))
            }
        }
    }

    private fun handleUdp(view: Packet.View) {
        val dst = view.dst ?: return
        when (config.rules.actionFor(dst)) {
            IpAction.PROXY -> if (config.udpEnabled) {
                udp.onPacket(view)
            } else {
                Log.d("UDP to ${dst.hostAddress} dropped (UDP relay disabled)")
            }
            IpAction.BLOCK, IpAction.DIRECT -> Log.d("UDP to ${dst.hostAddress} dropped by rule")
        }
    }

    // ---- PacketSink ---------------------------------------------------
    override fun send(packet: ByteArray): Boolean {
        synchronized(writeLock) {
            if (!running.get()) return false
            return try {
                output.write(packet)
                true
            } catch (e: IOException) {
                if (running.get()) Log.e("TUN write failed", e)
                false
            }
        }
    }

    // ---- TunnelConnector ----------------------------------------------
    override fun openStream(dst: InetAddress, dstPort: Int): ObfsStream {
        val literal = (dst.hostAddress ?: throw IOException("no IP for $dst")).substringBefore('%')
        val conn = ObfsStream.connect(
            config.serverHost, config.serverPort, config.stealthKey, config.token,
            config.obfs, connectTimeoutMs = 15000, readTimeoutMs = 8000
        )
        try {
            val socks = Socks5Client(conn)
            socks.negotiate(config.username, config.password)
            socks.connect(literal, dstPort)
            conn.setReadTimeout(0)
            return conn
        } catch (e: Exception) {
            conn.close()
            throw e
        }
    }

    // ---- UdpConnector --------------------------------------------------
    override fun openAssociation(): UdpAssociation {
        val conn = ObfsStream.connect(
            config.serverHost, config.serverPort, config.stealthKey, config.token,
            config.obfs, connectTimeoutMs = 15000, readTimeoutMs = 0
        )
        try {
            val socks = Socks5Client(conn)
            socks.negotiate(config.username, config.password)
            val relay = socks.udpAssociate(config.serverHost)
            val socket = DatagramSocket()
            socket.soTimeout = 0
            return UdpAssociation(conn, socket, InetSocketAddress(relay.address ?: InetAddress.getByName(config.serverHost), relay.port))
        } catch (e: Exception) {
            conn.close()
            throw e
        }
    }
}
