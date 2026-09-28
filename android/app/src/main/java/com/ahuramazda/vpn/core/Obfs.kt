package com.ahuramazda.vpn.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AHURA/1 — the obfuscation layer the relay speaks.
 *
 * Wire format (identical to `server/ahura_relay.py`, verified by
 * `server/tools/self_test.py`):
 *
 *   client -> relay, plain ASCII, right after connect:
 *       "AHURA/1 <nonce_hex> <token>\n"
 *   relay  -> client, plain ASCII:
 *       "AHURA/1 OK\n"            (or "AHURA/1 ERR <reason>\n")
 *   everything after that, both directions:
 *       <uint16 BE length> <ciphertext>   … plaintext XOR keystream
 *
 *       master  = HMAC-SHA256(stealth_key, "ahura/v1" + nonce)
 *       key_c2s = HMAC-SHA256(master, "c2s")
 *       key_s2c = HMAC-SHA256(master, "s2c")
 *       block i = HMAC-SHA256(key_dir, uint64 BE i)      (32 bytes of keystream)
 *
 * Why bother: a bare SOCKS5 connection starts with a very recognisable
 * `05 01 00 …` fingerprint and its traffic is plaintext on the wire.  The
 * stealth layer removes the fingerprint and makes payload inspection useless
 * without the shared key.  (Be honest with yourself about what this is:
 * obfuscation with a shared secret, not a hardened anti-DPI protocol.  Keep
 * using HTTPS inside — the tunnel leaves it untouched.)
 *
 * With `mode == MODE_NONE` the class is a thin pass-through and the app talks
 * bare SOCKS5 to the relay.
 */
class ObfsStream private constructor(
    val socket: Socket,
    private val encKey: ByteArray?,
    private val decKey: ByteArray?
) {

    private val input: InputStream = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val encMac: Mac? = encKey?.let { hmac(it) }
    private val decMac: Mac? = decKey?.let { hmac(it) }
    private var encCounter = 0L
    private var decCounter = 0L
    private var pending: ByteArray? = null
    private var pendingOff = 0
    private var pendingEnd = 0
    private var scratch: ByteArray? = null

    val encrypted: Boolean get() = encKey != null

    fun setReadTimeout(millis: Int) {
        try {
            socket.soTimeout = millis
        } catch (ignored: IOException) {
        }
    }

    /** Reads up to `len` bytes, blocking until at least one is available.
     *  Returns -1 when the peer closed the stream. */
    fun read(buf: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val available = pendingEnd - pendingOff
        val n = if (len < available) len else available
        System.arraycopy(pending!!, pendingOff, buf, off, n)
        pendingOff += n
        return n
    }

    /** Reads exactly `len` bytes unless the stream ends first; returns the
     *  number of bytes actually read. */
    fun readFully(buf: ByteArray, off: Int, len: Int): Int {
        var got = 0
        while (got < len) {
            val n = read(buf, off + got, len - got)
            if (n <= 0) return got
            got += n
        }
        return got
    }

    /** Reads one plaintext byte, or -1 at EOF (SOCKS5 prelude helper). */
    fun readByte(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    fun write(buf: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        if (encKey == null) {
            output.write(buf, off, len)
            return
        }
        var done = 0
        while (done < len) {
            val take = if (len - done > MAX_RECORD) MAX_RECORD else len - done
            var sc = scratch
            if (sc == null || sc.size < take + 2) {
                sc = ByteArray(MAX_RECORD + 2)
                scratch = sc
            }
            sc[0] = ((take ushr 8) and 0xFF).toByte()
            sc[1] = (take and 0xFF).toByte()
            encCounter = crypt(encMac!!, encCounter, buf, off + done, take, sc, 2)
            output.write(sc, 0, take + 2)
            done += take
        }
    }

    fun write(data: ByteArray) = write(data, 0, data.size)

    fun flush() {
        try {
            output.flush()
        } catch (ignored: IOException) {
        }
    }

    fun shutdownOutput() {
        try {
            socket.shutdownOutput()
        } catch (ignored: IOException) {
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (ignored: IOException) {
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private fun fill(): Boolean {
        if (pending != null && pendingOff < pendingEnd) return true
        if (encKey == null) {
            val buf = ByteArray(READ_CHUNK)
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                throw e
            }
            if (n <= 0) {
                pending = null
                return false
            }
            pending = buf
            pendingOff = 0
            pendingEnd = n
            return true
        }
        val head = ByteArray(2)
        if (readRaw(head, 2) < 2) return false
        val length = ((head[0].toInt() and 0xFF) shl 8) or (head[1].toInt() and 0xFF)
        if (length <= 0 || length > MAX_RECORD) {
            throw IOException("AHURA/1: bad record length $length (wrong stealth key?)")
        }
        val body = ByteArray(length)
        if (readRaw(body, length) < length) return false
        decCounter = crypt(decMac!!, decCounter, body, 0, length, body, 0)
        pending = body
        pendingOff = 0
        pendingEnd = length
        return true
    }

    private fun readRaw(buf: ByteArray, len: Int): Int {
        var got = 0
        while (got < len) {
            val n = input.read(buf, got, len - got)
            if (n <= 0) throw EOFException("AHURA/1: stream closed")
            got += n
        }
        return got
    }

    private fun crypt(mac: Mac, counter: Long, src: ByteArray, srcOff: Int, len: Int, dst: ByteArray, dstOff: Int): Long {
        var c = counter
        var i = 0
        val counterBytes = ByteArray(8)
        while (i < len) {
            var value = c
            for (b in 7 downTo 0) {
                counterBytes[b] = (value and 0xFF).toByte()
                value = value ushr 8
            }
            mac.reset()
            mac.update(counterBytes)
            val block = mac.doFinal()
            val take = if (len - i < 32) len - i else 32
            for (j in 0 until take) {
                dst[dstOff + i + j] = (src[srcOff + i + j].toInt() xor block[j].toInt()).toByte()
            }
            c++
            i += take
        }
        return c
    }

    companion object {
        const val MODE_AHURA = "ahura/1"
        const val MODE_NONE = "none"

        private const val MAX_RECORD = 8192
        private const val READ_CHUNK = 16384
        private const val PROTO = "AHURA/1"
        private val random = SecureRandom()

        /** Opens the TCP connection to the relay and, in stealth mode,
         *  performs the AHURA/1 handshake. */
        fun connect(
            host: String,
            port: Int,
            stealthKey: String,
            token: String,
            mode: String,
            connectTimeoutMs: Int = 12000,
            readTimeoutMs: Int = 30000
        ): ObfsStream {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            if (mode != MODE_AHURA) return ObfsStream(socket, null, null)

            val nonce = ByteArray(16)
            random.nextBytes(nonce)
            val hello = ("$PROTO ${nonce.toHex()} " + (if (token.isEmpty()) "-" else token) + "\n")
                .toByteArray(Charsets.US_ASCII)
            socket.getOutputStream().write(hello)
            socket.getOutputStream().flush()

            val reply = readPlainLine(socket, 160)
            if (!reply.startsWith("$PROTO OK")) {
                socket.close()
                throw IOException("relay refused handshake: ${reply.trim()}")
            }
            val key = parseKey(stealthKey)
            val master = hmac(key, ("ahura/v1".toByteArray(Charsets.US_ASCII) + nonce))
            val c2s = hmac(master, "c2s".toByteArray(Charsets.US_ASCII))
            val s2c = hmac(master, "s2c".toByteArray(Charsets.US_ASCII))
            return ObfsStream(socket, c2s, s2c)
        }

        private fun readPlainLine(socket: Socket, max: Int): String {
            val out = StringBuilder()
            val input = socket.getInputStream()
            var i = 0
            val one = ByteArray(1)
            while (i < max) {
                val n = input.read(one)
                if (n <= 0) break
                val ch = one[0].toInt() and 0xFF
                if (ch == '\n'.code) break
                out.append(ch.toChar())
                i++
            }
            return out.toString()
        }

        private fun parseKey(stealthKey: String): ByteArray {
            val trimmed = stealthKey.trim()
            if (trimmed.isEmpty()) throw IOException("stealth key is empty")
            if (trimmed.length == 32 && trimmed.all { isHex(it) }) return hexToBytes(trimmed)
            return trimmed.toByteArray(Charsets.UTF_8)
        }

        private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
            val mac = hmac(key)
            return mac.doFinal(message)
        }

        private fun hmac(key: ByteArray): Mac {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac
        }

        private fun isHex(c: Char): Boolean =
            c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        fun hexToBytes(hex: String): ByteArray {
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                out[i] = ((hexDigit(hex[i * 2]) shl 4) or hexDigit(hex[i * 2 + 1])).toByte()
            }
            return out
        }

        private fun hexDigit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            else -> c - 'A' + 10
        }

        /** Random hex, used to generate a stealth key on first launch. */
        fun randomHex(bytes: Int): String {
            val buf = ByteArray(bytes)
            random.nextBytes(buf)
            return buf.toHex()
        }

        fun ByteArray.toHex(): String {
            val digits = "0123456789abcdef"
            val sb = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xFF
                sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
            }
            return sb.toString()
        }
    }
}
