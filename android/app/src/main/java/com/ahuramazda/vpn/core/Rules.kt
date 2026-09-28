package com.ahuramazda.vpn.core

import java.math.BigInteger
import java.net.InetAddress

/** What the tunnel does with the packets of a flow. */
enum class IpAction { PROXY, DIRECT, BLOCK }

/** A single IP / CIDR rule, as edited by the user. */
class IpRule(val cidr: String, val action: IpAction, val note: String = "") {

    val prefix: Prefix = Prefix.parse(cidr)
        ?: throw IllegalArgumentException("'$cidr' is not a CIDR (examples: 8.8.8.0/24, 2001:4860::/32)")

    fun matches(addr: InetAddress): Boolean = prefix.contains(addr)

    override fun toString(): String = "${action.name.lowercase()} $cidr" + if (note.isEmpty()) "" else "  # $note"
}

/** An IP prefix (network address + length). */
class Prefix(val v6: Boolean, val bytes: ByteArray, val bits: Int) {

    fun contains(addr: InetAddress): Boolean {
        val raw = addr.address
        if (raw.size != bytes.size) return false
        var remaining = bits
        var i = 0
        while (remaining > 0) {
            if (i >= raw.size) return true
            val take = if (remaining >= 8) 8 else remaining
            val mask = (0xFF shl (8 - take)) and 0xFF
            if ((raw[i].toInt() and mask) != (bytes[i].toInt() and mask)) return false
            remaining -= take
            i++
        }
        return true
    }

    override fun equals(other: Any?): Boolean =
        other is Prefix && other.v6 == v6 && other.bits == bits && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = (bits * 31) + bytes.contentHashCode() + if (v6) 1 else 0

    override fun toString(): String = NetworkMath.format(this)

    companion object {
        /** Accepts "10.0.0.0/8", "10/8", "8.8.8.8" (host route) and IPv6. */
        fun parse(text: String): Prefix? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val slash = trimmed.indexOf('/')
            val hostPart = if (slash >= 0) trimmed.substring(0, slash) else trimmed
            val v6 = hostPart.contains(':')
            val address = NetworkMath.parseLiteral(hostPart) ?: return null
            if (address.size != if (v6) 16 else 4) return null
            var bits = if (v6) 128 else 32
            if (slash >= 0) {
                val parsed = trimmed.substring(slash + 1).trim().toIntOrNull() ?: return null
                if (parsed < 0 || parsed > bits) return null
                bits = parsed
            }
            val masked = NetworkMath.maskBytes(address, bits)
            return Prefix(v6, masked, bits)
        }
    }
}

/** Prefix arithmetic: parsing, masking, range <-> prefix conversion and the
 *  complement of a set of prefixes.  The last one is what lets Ahura honor
 *  "bypass LAN / bypass these CIDRs" on Android versions that have no
 *  `excludeRoute`: instead of routing 0.0.0.0/0 and then trying to subtract,
 *  the service installs the *complement* as its routes, so the bypassed
 *  networks never enter the tunnel in the first place. */
object NetworkMath {

    fun parseLiteral(text: String): ByteArray? {
        if (text.isEmpty()) return null
        if (text.contains(':')) return parseV6(text)
        return parseV4(text)
    }

    private fun parseV4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.isEmpty() || parts.size > 4) return null
        val out = ByteArray(4)
        for (i in parts.indices) {
            val value = parts[i].toIntOrNull() ?: return null
            if (value < 0 || value > 255) return null
            out[i] = value.toByte()
        }
        return out
    }

    private fun parseV6(text: String): ByteArray? {
        val out = ByteArray(16)
        var head = text
        var tail: String? = null
        val dc = text.indexOf("::")
        if (dc >= 0) {
            head = text.substring(0, dc)
            tail = text.substring(dc + 2)
        }
        val headGroups = if (head.isEmpty()) emptyList() else head.split(':')
        val tailGroups = when {
            tail == null -> emptyList()
            tail.isEmpty() -> emptyList()
            else -> tail.split(':')
        }
        if (headGroups.size + tailGroups.size > 8) return null
        var index = 0
        for (g in headGroups) {
            val value = g.toIntOrNull(16) ?: return null
            if (value > 0xFFFF) return null
            out[index++] = ((value ushr 8) and 0xFF).toByte()
            out[index++] = (value and 0xFF).toByte()
        }
        var tailIndex = 16 - tailGroups.size * 2
        for (g in tailGroups) {
            val value = g.toIntOrNull(16) ?: return null
            if (value > 0xFFFF) return null
            out[tailIndex++] = ((value ushr 8) and 0xFF).toByte()
            out[tailIndex++] = (value and 0xFF).toByte()
        }
        return out
    }

    fun maskBytes(bytes: ByteArray, bits: Int): ByteArray {
        val out = bytes.copyOf()
        var remaining = bits
        for (i in out.indices) {
            if (remaining >= 8) {
                remaining -= 8
            } else if (remaining > 0) {
                val mask = (0xFF shl (8 - remaining)) and 0xFF
                out[i] = (out[i].toInt() and mask).toByte()
                remaining = 0
            } else {
                out[i] = 0
            }
        }
        return out
    }

    fun format(prefix: Prefix): String {
        val host = if (prefix.v6) formatV6(prefix.bytes) else formatV4(prefix.bytes)
        return "$host/${prefix.bits}"
    }

    fun formatV4(bytes: ByteArray): String =
        "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}.${bytes[2].toInt() and 0xFF}.${bytes[3].toInt() and 0xFF}"

    fun formatV6(bytes: ByteArray): String {
        val groups = ArrayList<String>(8)
        var bestStart = -1
        var bestLen = 0
        var runStart = -1
        for (i in 0 until 8) {
            val value = ((bytes[i * 2].toInt() and 0xFF) shl 8) or (bytes[i * 2 + 1].toInt() and 0xFF)
            if (value == 0) {
                if (runStart < 0) runStart = i
            } else if (runStart >= 0) {
                if (i - runStart > bestLen) {
                    bestLen = i - runStart
                    bestStart = runStart
                }
                runStart = -1
            }
            groups.add(Integer.toHexString(value))
        }
        if (runStart >= 0 && 8 - runStart > bestLen) {
            bestLen = 8 - runStart
            bestStart = runStart
        }
        if (bestLen < 2) return groups.joinToString(":")
        val head = groups.subList(0, bestStart).joinToString(":")
        val tail = groups.subList(bestStart + bestLen, 8).joinToString(":")
        return "$head::$tail"
    }

    fun toBigInt(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

    fun fromBigInt(value: BigInteger, v6: Boolean): ByteArray {
        val size = if (v6) 16 else 4
        val raw = value.toByteArray()
        val out = ByteArray(size)
        val copy = if (raw.size > size) size else raw.size
        System.arraycopy(raw, raw.size - copy, out, size - copy, copy)
        return out
    }

    fun range(prefix: Prefix): Pair<BigInteger, BigInteger> {
        val totalBits = if (prefix.v6) 128 else 32
        val start = toBigInt(prefix.bytes)
        val hostBits = totalBits - prefix.bits
        val end = start.add(BigInteger.ONE.shiftLeft(hostBits)).subtract(BigInteger.ONE)
        return start to end
    }

    fun isPrivate(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
            addr.isAnyLocalAddress || addr.isMulticastAddress
        ) return true
        val raw = addr.address
        if (raw.size == 4) {
            val a = raw[0].toInt() and 0xFF
            val b = raw[1].toInt() and 0xFF
            if (a == 100 && b in 64..127) return true            // CGNAT 100.64/10
            if (a == 192 && b == 0) return true                  // 192.0.0.0/24, 192.0.2.0/24
            if (a == 198 && (b == 18 || b == 19)) return true     // benchmarking
            if (a >= 240) return true                             // reserved
            if (a == 169 && b == 254) return true                 // link local
        } else {
            val first = raw[0].toInt() and 0xFF
            if ((first and 0xFE) == 0xFC) return true             // unique local fc00::/7
            if (first == 0xFE && (raw[1].toInt() and 0xC0) == 0x80) return true   // link local
        }
        return false
    }

    /** Prefixes covering everything that is *not* inside `excluded`. */
    fun complement(all: Prefix, excluded: List<Prefix>): List<Prefix> {
        val totalBits = if (all.v6) 128 else 32
        val relevant = excluded.filter { it.v6 == all.v6 }.sortedBy { toBigInt(it.bytes) }
        val out = ArrayList<Prefix>()
        val total = BigInteger.ONE.shiftLeft(totalBits)
        var cursor = BigInteger.ZERO
        for (p in relevant) {
            val (s, e) = range(p)
            if (s > cursor) out.addAll(rangeToPrefixes(all.v6, cursor, s.subtract(BigInteger.ONE)))
            val next = e.add(BigInteger.ONE)
            if (next > cursor) cursor = next
            if (cursor >= total) return out
        }
        if (cursor < total) out.addAll(rangeToPrefixes(all.v6, cursor, total.subtract(BigInteger.ONE)))
        return out
    }

    fun rangeToPrefixes(v6: Boolean, start: BigInteger, end: BigInteger): List<Prefix> {
        val totalBits = if (v6) 128 else 32
        val out = ArrayList<Prefix>()
        var cursor = start
        while (cursor <= end) {
            var align = 0
            var probe = cursor
            while (align < totalBits && !probe.testBit(0)) {
                probe = probe.shiftRight(1)
                align++
            }
            val size = end.subtract(cursor).add(BigInteger.ONE)
            val bySize = size.bitLength() - 1
            val blockBits = if (align < bySize) align else bySize
            out.add(Prefix(v6, fromBigInt(cursor, v6), totalBits - blockBits))
            cursor = cursor.add(BigInteger.ONE.shiftLeft(blockBits))
        }
        return out
    }

    val PRIVATE_V4: List<Prefix> = listOf(
        "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
        "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16", "198.18.0.0/15", "224.0.0.0/4",
        "240.0.0.0/4"
    ).mapNotNull { Prefix.parse(it) }

    val PRIVATE_V6: List<Prefix> = listOf(
        "::1/128", "fc00::/7", "fe80::/10", "ff00::/8", "2001:db8::/32"
    ).mapNotNull { Prefix.parse(it) }
}

/**
 * The routing brain: which destination IPs get tunneled, which are left to the
 * operating system, which are dropped.
 */
class RuleSet(rules: List<IpRule>) {

    val rules: List<IpRule> = ArrayList(rules)

    /** Longest-prefix match wins; unrouted traffic is DIRECT (it never enters
     *  the tunnel anyway, because it is not in [tunnelRoutes]). */
    fun actionFor(dst: InetAddress): IpAction {
        var best: IpRule? = null
        for (rule in rules) {
            if (!rule.matches(dst)) continue
            if (best == null || rule.prefix.bits > best.prefix.bits) best = rule
        }
        return best?.action ?: IpAction.DIRECT
    }

    fun prefixes(action: IpAction): List<Prefix> = rules.filter { it.action == action }.map { it.prefix }

    /**
     * The route list handed to `VpnService.Builder`.
     *
     * * `useExcludeRoute == true` (Android 13+): route the PROXY ranges and let
     *   the platform exclude the DIRECT ones — exact and cheap.
     * * otherwise: the complement, so DIRECT ranges stay outside the tunnel.
     *   BLOCK ranges are still routed so the tunnel can drop them (that is what
     *   a block rule means).
     */
    fun tunnelRoutes(useExcludeRoute: Boolean, enableV6: Boolean): List<Prefix> {
        val proxy = prefixes(IpAction.PROXY).filter { enableV6 || !it.v6 }
        val direct = prefixes(IpAction.DIRECT).filter { enableV6 || !it.v6 }
        val block = prefixes(IpAction.BLOCK).filter { enableV6 || !it.v6 }
        if (useExcludeRoute || direct.isEmpty()) return (proxy + block).distinct()

        val out = ArrayList<Prefix>()
        for (v6 in listOf(false, true)) {
            if (!enableV6 && v6) continue
            val base = if (v6) Prefix.parse("::/0")!! else Prefix.parse("0.0.0.0/0")!!
            val proxyAll = proxy.any { it.bits == 0 && it.v6 == v6 }
            if (!proxyAll) {
                out.addAll(proxy.filter { it.v6 == v6 })
                continue
            }
            out.addAll(NetworkMath.complement(base, direct.filter { it.v6 == v6 }))
        }
        out.addAll(block)
        return out.distinct()
    }

    /** DIRECT prefixes that must be excluded explicitly (Android 13+). */
    fun directExcludes(enableV6: Boolean): List<Prefix> =
        prefixes(IpAction.DIRECT).filter { enableV6 || !it.v6 }

    fun describe(dst: InetAddress): String = "${actionFor(dst).name} ${dst.hostAddress}"

    companion object {
        /** Full tunnel: everything through the relay. */
        fun fullTunnel(bypassLocalNetworks: Boolean, enableV6: Boolean): RuleSet {
            val rules = ArrayList<IpRule>()
            if (bypassLocalNetworks) {
                for (p in NetworkMath.PRIVATE_V4) rules.add(IpRule(p.toString(), IpAction.DIRECT, "local"))
                if (enableV6) {
                    for (p in NetworkMath.PRIVATE_V6) rules.add(IpRule(p.toString(), IpAction.DIRECT, "local"))
                }
            }
            rules.add(IpRule("0.0.0.0/0", IpAction.PROXY, "everything else"))
            if (enableV6) rules.add(IpRule("::/0", IpAction.PROXY, "everything else"))
            return RuleSet(rules)
        }

        /** Only the listed CIDRs are tunneled; everything else stays direct. */
        fun listOnly(cidrs: List<String>): RuleSet {
            val rules = ArrayList<IpRule>()
            for (cidr in cidrs) {
                val prefix = Prefix.parse(cidr) ?: continue
                rules.add(IpRule(prefix.toString(), IpAction.PROXY))
            }
            return RuleSet(rules)
        }
    }
}
