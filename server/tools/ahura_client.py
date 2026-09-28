#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ahura_client.py — reference client for the AHURA/1 protocol + SOCKS5.

This is the Python twin of the Kotlin code in
`android/app/src/main/java/com/ahuramazda/vpn/core/` (Obfs.kt / Socks5.kt).
Keeping the two implementations identical on the wire is exactly why this
file exists: the self-test suite proves the protocol, the Android client
speaks it.

Usage as a CLI (quick check that a relay works):

    python3 ahura_client.py --host 1.2.3.4 --port 1080 \
        --key "$(cat stealth.key)" --token mobile1 --target 1.1.1.1:443 --probe
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import ipaddress
import os
import select
import socket
import struct
import sys

MAX_RECORD = 8192
PROTO = "AHURA/1"


# --------------------------------------------------------------------------
# AHURA/1 crypto (mirror of the relay implementation)
# --------------------------------------------------------------------------
def derive_keys(stealth_key: bytes, nonce: bytes) -> tuple[bytes, bytes]:
    master = hmac.new(stealth_key, b"ahura/v1" + nonce, hashlib.sha256).digest()
    return (hmac.new(master, b"c2s", hashlib.sha256).digest(),
            hmac.new(master, b"s2c", hashlib.sha256).digest())


def xor_keystream(key: bytes, counter: int, data: bytes) -> tuple[bytes, int]:
    out = []
    i, n = 0, len(data)
    while i < n:
        block = hmac.new(key, counter.to_bytes(8, "big"), hashlib.sha256).digest()
        take = 32 if n - i >= 32 else n - i
        chunk = data[i:i + take]
        if take == 32:
            out.append((int.from_bytes(chunk, "big") ^ int.from_bytes(block, "big")).to_bytes(32, "big"))
        else:
            out.append(bytes(a ^ b for a, b in zip(chunk, block)))
        counter += 1
        i += take
    return b"".join(out), counter


class AhuraStream:
    """Framed, optionally encrypted byte stream (client side)."""

    def __init__(self, sock: socket.socket, key_c2s: bytes | None, key_s2c: bytes | None):
        self.sock = sock
        self.key_c2s, self.key_s2c = key_c2s, key_s2c
        self._enc = self._dec = 0
        self._buf = bytearray()
        self._raw = bytearray()
        self.read_timeout: float | None = None

    # -- raw framing -----------------------------------------------------
    def _fill_raw(self) -> bool:
        """Pull one record (or one socket read) into the plaintext buffer."""
        if self._raw:
            return True
        if self.read_timeout is not None and not select.select([self.sock], [], [], self.read_timeout)[0]:
            raise TimeoutError("timed out waiting for the relay")
        if self.key_s2c is None:
            chunk = self.sock.recv(65536)
        else:
            head = self._recv_all(2)
            if len(head) < 2:
                return False
            ln = struct.unpack("!H", head)[0]
            chunk = self._recv_all(ln)
            if len(chunk) < ln:
                return False
            chunk, self._dec = xor_keystream(self.key_s2c, self._dec, chunk)
        if not chunk:
            return False
        self._raw.extend(chunk)
        return True

    def _recv_all(self, n: int) -> bytes:
        out = bytearray()
        while len(out) < n:
            part = self.sock.recv(n - len(out))
            if not part:
                break
            out.extend(part)
        return bytes(out)

    def _need_raw(self, n: int) -> bytes:
        while len(self._raw) < n:
            if not self._fill_raw():
                break
        out = bytes(self._raw[:n])
        del self._raw[:n]
        return out

    # -- public ----------------------------------------------------------
    def recv_some(self, limit: int = 65536, timeout: float | None = None) -> bytes:
        """Whatever the tunnel has for us right now (b'' == EOF)."""
        self.read_timeout = timeout
        out = bytearray()
        for src in (self._buf, self._raw):
            if len(out) < limit and src:
                take = min(limit - len(out), len(src))
                out += src[:take]
                del src[:take]
        if not out:
            if not self._fill_raw():
                return b""
            take = min(limit, len(self._raw))
            out += self._raw[:take]
            del self._raw[:take]
        return bytes(out)

    def recv_exact(self, n: int) -> bytes:
        out = bytearray()
        if self._buf:
            take = min(n, len(self._buf))
            out.extend(self._buf[:take])
            del self._buf[:take]
        while len(out) < n:
            chunk = self._need_raw(max(n - len(out), 1))
            if not chunk:
                break
            out.extend(chunk)
        self._buf.extend(out[n:])
        del out[n:]
        return bytes(out)

    def send(self, data: bytes) -> None:
        if not data:
            return
        if self.key_c2s is None:
            self.sock.sendall(data)
            return
        off = 0
        while off < len(data):
            chunk = data[off:off + MAX_RECORD]
            off += len(chunk)
            ct, self._enc = xor_keystream(self.key_c2s, self._enc, chunk)
            self.sock.sendall(struct.pack("!H", len(ct)) + ct)


# --------------------------------------------------------------------------
# SOCKS5 helpers
# --------------------------------------------------------------------------
def pack_addr(host: str, port: int) -> bytes:
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        ip = None
    if ip and ip.version == 4:
        return b"\x01" + ip.packed + struct.pack("!H", port)
    if ip and ip.version == 6:
        return b"\x04" + ip.packed + struct.pack("!H", port)
    raw = host.encode()
    return b"\x03" + bytes([len(raw)]) + raw + struct.pack("!H", port)


def read_addr(stream: AhuraStream, atyp: int | None = None) -> tuple[str, int]:
    """Read ATYP+ADDR+PORT; pass `atyp` when a 4-byte reply header (which
    already carried the address type) has been consumed."""
    if atyp is None:
        atyp = stream.recv_exact(1)[0]
    if atyp == 1:
        return socket.inet_ntoa(stream.recv_exact(4)), struct.unpack("!H", stream.recv_exact(2))[0]
    if atyp == 4:
        return socket.inet_ntop(socket.AF_INET6, stream.recv_exact(16)), struct.unpack("!H", stream.recv_exact(2))[0]
    ln = stream.recv_exact(1)[0]
    return stream.recv_exact(ln).decode("utf-8", "replace"), struct.unpack("!H", stream.recv_exact(2))[0]


REP_TEXT = {
    0: "succeeded", 1: "general failure", 2: "not allowed by ruleset", 3: "network unreachable",
    4: "host unreachable", 5: "connection refused", 6: "TTL expired", 7: "command not supported",
    8: "address type not supported",
}


class AhuraError(Exception):
    pass


class AhuraClient:
    """One TCP connection to an Ahura relay.  `handshake()` then `connect()`."""

    def __init__(self, host: str, port: int, token: str = "", stealth_key: str = "",
                 obfs: str = "ahura/1", user: str = "", password: str = "", timeout: float = 20.0):
        self.host, self.port, self.token = host, port, token
        self.stealth_key = stealth_key
        self.obfs, self.user, self.password, self.timeout = obfs, user, password, timeout
        self.sock: socket.socket | None = None
        self.stream: AhuraStream | None = None

    # -- connection ------------------------------------------------------
    def open(self) -> "AhuraClient":
        self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        # Plain blocking socket afterwards: per-call deadlines are implemented
        # with select() so a read deadline can never abort a concurrent send().
        self.sock.settimeout(None)
        if self.obfs == "ahura/1":
            if not self.stealth_key:
                raise AhuraError("stealth key required for obfs=ahura/1")
            nonce = os.urandom(16)
            key = (bytes.fromhex(self.stealth_key) if len(self.stealth_key) == 32
                   and all(c in "0123456789abcdefABCDEF" for c in self.stealth_key)
                   else self.stealth_key.encode())
            key_c2s, key_s2c = derive_keys(key, nonce)
            line = "%s %s %s\n" % (PROTO, nonce.hex(), self.token or "-")
            self.sock.sendall(line.encode())
            first = bytearray()
            while len(first) < 64:
                ch = self.sock.recv(1)
                if not ch:
                    break
                if ch == b"\n":
                    break
                first.extend(ch)
            reply = first.decode("ascii", "replace")
            if not reply.startswith(PROTO + " OK"):
                raise AhuraError("relay refused handshake: %s" % reply.strip())
            self.stream = AhuraStream(self.sock, key_c2s, key_s2c)
        else:
            self.stream = AhuraStream(self.sock, None, None)
        return self

    # -- socks5 ----------------------------------------------------------
    def socks_handshake(self) -> None:
        assert self.stream is not None
        if self.user:
            self.stream.send(b"\x05\x02\x00\x02")
        else:
            self.stream.send(b"\x05\x01\x00")
        ver, method = self.stream.recv_exact(2)
        if ver != 0x05:
            raise AhuraError("bad socks version %d" % ver)
        if method == 0x02:
            u, p = self.user.encode(), self.password.encode()
            self.stream.send(b"\x01" + bytes([len(u)]) + u + bytes([len(p)]) + p)
            av, status = self.stream.recv_exact(2)
            if not av or status != 0:
                raise AhuraError("username/password auth failed")
        elif method != 0x00:
            raise AhuraError("proxy requires an unsupported auth method (0x%02x)" % method)

    def connect_ip(self, ip: str, port: int) -> tuple[str, int]:
        """CONNECT to a literal IP.  Returns the relay's bound address."""
        assert self.stream is not None
        self.socks_handshake()
        self.stream.send(b"\x05\x01\x00" + pack_addr(ip, port))
        head = self.stream.recv_exact(4)
        if len(head) < 4 or head[0] != 0x05:
            raise AhuraError("malformed CONNECT reply")
        # NOTE: head == VER REP RSV ATYP, so the address type is already known
        bound = read_addr(self.stream, head[3])
        if head[1] != 0:
            raise AhuraError("CONNECT failed: %s" % REP_TEXT.get(head[1], "error %d" % head[1]))
        return bound

    # -- convenience -----------------------------------------------------
    def http_get(self, ip: str, port: int, host_header: str, path: str = "/", extra: str = "") -> bytes:
        self.connect_ip(ip, port)
        req = ("GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: ahura-client/1.0\r\n"
               "Connection: close\r\nAccept: */*\r\n%s\r\n" % (path, host_header, extra))
        self.stream.send(req.encode())
        out = bytearray()
        while True:
            data = self.stream.recv_some(65536, timeout=10)
            if not data:
                break
            out.extend(data)
            if len(out) > 64_000:
                break
        return bytes(out)

    def close(self) -> None:
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass


def main(argv=None) -> int:
    p = argparse.ArgumentParser("ahura_client")
    p.add_argument("--host", required=True)
    p.add_argument("--port", type=int, default=1080)
    p.add_argument("--token", default="")
    p.add_argument("--key", default=os.environ.get("AHURA_STEALTH_KEY", ""))
    p.add_argument("--obfs", choices=["ahura/1", "none"], default="ahura/1")
    p.add_argument("--user", default="")
    p.add_argument("--password", default="")
    p.add_argument("--target", required=True, help="ip:port")
    p.add_argument("--probe", action="store_true", help="speak plain HTTP HEAD on the target")
    args = p.parse_args(argv)

    ip, port = args.target.rsplit(":", 1)
    c = AhuraClient(args.host, args.port, token=args.token, stealth_key=args.key,
                    obfs=args.obfs, user=args.user, password=args.password)
    try:
        c.open()
        print("handshake: ok (%s)" % args.obfs)
        c.socks_handshake()
        bound = c.connect_ip(ip, int(port))
        print("connected to %s:%s via %s:%d" % (ip, port, bound[0], bound[1]))
        if args.probe:
            c.stream.send(("HEAD / HTTP/1.0\r\nHost: %s\r\n\r\n" % ip).encode())
            print(c.stream.recv_exact(1024).decode("latin-1", "replace").split("\r\n")[0])
        return 0
    except AhuraError as exc:
        print("FAILED: %s" % exc, file=sys.stderr)
        return 2
    finally:
        c.close()


if __name__ == "__main__":
    sys.exit(main())
