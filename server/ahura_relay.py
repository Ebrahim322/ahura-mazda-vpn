#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Ahura Relay — a small, dependency-free SOCKS5 relay server that powers
Ahura Mazda VPN (the Android IP-tunnel client in ../android).

Why it exists
-------------
The Android client is an *IP based* tunnel: it grabs raw IP packets from a
`VpnService` TUN device and turns every TCP/UDP flow into a SOCKS5 request
(optionally wrapped in the "AHURA/1" stealth layer) that is sent to this
relay.  Nothing about the tunnel is domain based — routing decisions are made
purely on destination IP / CIDR rules on the device, so DNS poisoning and
domain-based blocking cannot break or fingerprint the tunnel.

Features
--------
* SOCKS5 (RFC 1928) with CONNECT and UDP ASSOCIATE, plus optional
  username/password auth (RFC 1929) and multi-token "stealth" auth.
* AHURA/1 obfuscation layer: a tiny handshake + HMAC-SHA256 counter-mode
  stream cipher with framed records.  Cheap to run, but it removes the SOCKS5
  byte signature (`0x05 0x01 0x00 ...`) that naive DPI engines key on.
* Target policy: private / reserved ranges refused by default, optional
  CIDR allow-list, per-IP connection limits, connection cap.
* Live web dashboard (Persian, RTL, zero external assets) with traffic
  sparkline, per-user stats and a request log, available at /  with JSON at
  /api/stats and a probe at /healthz.
* Pure standard library: python3 + nothing else.  Runs on any VPS.

Wire protocol (AHURA/1)
-----------------------
client -> server, immediately after TCP connect, plain ASCII, <=256 bytes:

    "AHURA/1 " <nonce_hex> " " <token> "\\n"

    nonce_hex : 32 lowercase hex chars (16 random bytes, fresh per connection)

    master   = HMAC-SHA256(key=stealth_key, msg=b"ahura/v1" + nonce_bytes)
    key_c2s  = HMAC-SHA256(master, b"c2s")
    key_s2c  = HMAC-SHA256(master, b"s2c")

Every byte after the handshake, **in both directions**, travels in records:

    <uint16 BE length> <length bytes of ciphertext>

plaintext XOR keystream, where keystream block *i* (32 bytes) is
HMAC-SHA256(key_dir, uint64 BE i).  Counters restart per connection and the
keys are unique per connection (fresh nonce), so no keystream is ever reused.

If the handshake fails the server answers (still in clear text, before any
key is derived) with "AHURA/1 ERR <reason>\\n" and closes the connection.
With `--obfs none` the relay speaks bare SOCKS5 and skips all of this.

NOTE (be honest with yourself): the stealth layer is obfuscation, not real
encryption.  It stops fingerprints and casual blocking; for content privacy
keep using TLS/HTTPS inside the tunnel (which every sane app does anyway).
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import ipaddress
import json
import os
import random
import secrets
import select
import selectors
import signal
import socket
import struct
import sys
import threading
import time
from collections import Counter, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = "1.1.0"
PROTO_NAME = "AHURA/1"
HANDSHAKE_MAX = 256
MAX_RECORD = 8192          # plaintext bytes per obfuscated record
MAX_PAD = 64               # random padding inside a TLS-lookalike frame
RELAY_BUFFER = 65536
UDP_MAX_DATAGRAM = 65535
MAX_BUFFERED = 1 << 20      # per direction, bounds relay memory per session

# Framing modes.  Both encrypted modes use the same handshake and the same
# keystream — they differ only in how records are put on the wire:
#
#   ahura/1 : <uint16 len><cipher>                       (compact)
#   tls     : 17 03 03 <uint16 outer> [<uint16 len><cipher><random pad>]
#             — every frame looks like one TLS application-data record, the
#               outer length is randomised by the padding, and the first bytes
#               the client ever sends are 16 03 01 …, i.e. a ClientHello.
MODE_AHURA = "ahura/1"
MODE_TLS = "tls"
MODE_NONE = "none"
MODE_ANY = "any"
MODES_ENCRYPTED = (MODE_AHURA, MODE_TLS)


# --------------------------------------------------------------------------
# logging
# --------------------------------------------------------------------------
_LOG_LOCK = threading.Lock()
LOG_RING: deque = deque(maxlen=200)          # (ts, level, text) for the dashboard
LOG_LEVELS = {"debug": 10, "info": 20, "warn": 30, "error": 40}
_log_level = LOG_LEVELS["info"]


def _ts() -> str:
    return time.strftime("%H:%M:%S", time.localtime())


def log(text: str, level: str = "info") -> None:
    if LOG_LEVELS.get(level, 20) < _log_level:
        return
    line = "%s [%-5s] %s" % (_ts(), level.upper(), text)
    with _LOG_LOCK:
        LOG_RING.append((time.time(), level, text))
        sys.stdout.write(line + "\n")
        sys.stdout.flush()


# --------------------------------------------------------------------------
# crypto: AHURA/1 stream cipher
# --------------------------------------------------------------------------
def derive_keys(stealth_key: bytes, nonce: bytes) -> tuple[bytes, bytes]:
    master = hmac.new(stealth_key, b"ahura/v1" + nonce, hashlib.sha256).digest()
    key_c2s = hmac.new(master, b"c2s", hashlib.sha256).digest()
    key_s2c = hmac.new(master, b"s2c", hashlib.sha256).digest()
    return key_c2s, key_s2c


def xor_keystream(key: bytes, counter: int, data: bytes) -> tuple[bytes, int]:
    """XOR `data` with the keystream starting at block `counter`.

    Returns (ciphertext, next_counter).  Both directions advance the counter
    purely as a function of the number of bytes processed, so encryptor and
    decryptor always stay in sync."""
    out = []
    i = 0
    n = len(data)
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


class RecordStream:
    """Byte stream over a socket, optionally wrapped in AHURA/1 records.

    Two modes of use:

    * *prelude* (blocking socket): `read_exact` / `send_all` speak plain text
      while the SOCKS5 handshake is negotiated.
    * *pump* (non-blocking socket): `feed`/`take` for inbound, `write_plain`/
      `flush` for outbound.  Both sockets of a relayed session are driven by a
      single selector loop, which is what keeps a relay from dead-locking when
      both peers stop reading at the same time (a blocking read-send loop
      dead-locks there — that bug is what the 300 KB round-trip test catches).
    """

    def __init__(self, sock: socket.socket, enc_key: bytes | None, dec_key: bytes | None,
                 mode: str = MODE_AHURA):
        self.sock = sock
        self.enc_key = enc_key
        self.dec_key = dec_key
        self.mode = mode
        self._enc_ctr = 0
        self._dec_ctr = 0
        self._inbuf = bytearray()     # decoded plaintext, not yet consumed
        self._raw = bytearray()       # bytes from the socket, not yet decoded
        self._out = bytearray()       # encoded bytes waiting for the socket
        self._fin = False             # half-close requested by the remote peer
        self.dead = False

    # ------------------------------------------------------------------
    # helpers
    # ------------------------------------------------------------------
    def has_pending_out(self) -> bool:
        return bool(self._out) or self._fin

    def _frame(self, payload: bytes) -> bytes:
        """Wrap `payload` in one TLS application-data record + random padding."""
        pad = os.urandom(random.randint(0, MAX_PAD))
        body = payload + pad
        return b"\x17\x03\x03" + struct.pack("!H", len(body)) + body

    def _encode(self, data: bytes) -> bytes:
        if self.enc_key is None:
            return data
        out = []
        off = 0
        while off < len(data):
            chunk = data[off:off + MAX_RECORD]
            off += len(chunk)
            cipher, self._enc_ctr = xor_keystream(self.enc_key, self._enc_ctr, chunk)
            record = struct.pack("!H", len(cipher)) + cipher
            out.append(self._frame(record) if self.mode == MODE_TLS else record)
        return b"".join(out)

    def _needed_raw(self) -> int:
        """How many more raw bytes are needed before `_decode` can progress."""
        if self.enc_key is None:
            return 1
        if self.mode == MODE_TLS:
            if len(self._raw) < 5:
                return 5 - len(self._raw)
            outer = struct.unpack("!H", bytes(self._raw[3:5]))[0]
            return max(0, 5 + outer - len(self._raw))
        if len(self._raw) < 2:
            return 2 - len(self._raw)
        length = struct.unpack("!H", bytes(self._raw[:2]))[0]
        return max(0, 2 + length - len(self._raw))

    def _decode(self) -> None:
        """Turn whatever raw bytes we hold into plaintext."""
        if self.enc_key is None:
            self._inbuf.extend(self._raw)
            self._raw.clear()
            return
        if self.mode == MODE_TLS:
            self._decode_tls()
            return
        while len(self._raw) >= 2:
            length = struct.unpack("!H", self._raw[:2])[0]
            if length > MAX_RECORD:
                raise ValueError("record too large: %d" % length)
            if len(self._raw) < 2 + length:
                return
            body = bytes(self._raw[2:2 + length])
            del self._raw[:2 + length]
            plain, self._dec_ctr = xor_keystream(self.dec_key, self._dec_ctr, body)
            self._inbuf.extend(plain)

    def _decode_tls(self) -> None:
        """Undo one or more TLS-lookalike frames; the padding is discarded."""
        while len(self._raw) >= 5:
            ctype = self._raw[0]
            if ctype not in (0x16, 0x17) or self._raw[1] != 0x03:
                raise ValueError("bad frame header")
            outer = struct.unpack("!H", bytes(self._raw[3:5]))[0]
            if outer < 2 or outer > MAX_RECORD + 2 + MAX_PAD:
                raise ValueError("bad frame length: %d" % outer)
            if len(self._raw) < 5 + outer:
                return
            body = bytes(self._raw[5:5 + outer])
            del self._raw[:5 + outer]
            inner = struct.unpack("!H", body[:2])[0]
            if inner > MAX_RECORD or len(body) < 2 + inner:
                raise ValueError("bad record inside frame")
            cipher = body[2:2 + inner]
            plain, self._dec_ctr = xor_keystream(self.dec_key, self._dec_ctr, cipher)
            self._inbuf.extend(plain)

    # ------------------------------------------------------------------
    # prelude API (blocking sockets)
    # ------------------------------------------------------------------
    def _recv_blocking(self, n: int) -> bytes:
        out = bytearray()
        while len(out) < n:
            try:
                chunk = self.sock.recv(n - len(out))
            except (BlockingIOError, InterruptedError):
                continue
            if not chunk:
                break
            out.extend(chunk)
        return bytes(out)

    def read_exact(self, n: int) -> bytes:
        """Exactly n plaintext bytes, decoding AHURA/1 records on the way."""
        out = bytearray()
        if self._inbuf:
            take = min(n, len(self._inbuf))
            out += self._inbuf[:take]
            del self._inbuf[:take]
        while len(out) < n:
            if self.enc_key is None:
                chunk = self._recv_blocking(n - len(out))
                if not chunk:
                    break
                out += chunk
                continue
            while not self._inbuf:
                head = self._recv_blocking(self._needed_raw())
                if not head:
                    return bytes(out)
                self._raw.extend(head)
                self._decode()
            take = min(n - len(out), len(self._inbuf))
            out += self._inbuf[:take]
            del self._inbuf[:take]
        return bytes(out)

    def send_all(self, data: bytes) -> None:
        self.write_plain(data)
        while not self.flush():
            select.select([], [self.sock], [], 5.0)

    # ------------------------------------------------------------------
    # pump API (non-blocking sockets)
    # ------------------------------------------------------------------
    def feed(self) -> bool:
        """Read what is available and decode it.  False means EOF/error."""
        while True:
            try:
                chunk = self.sock.recv(65536)
            except BlockingIOError:
                break
            except OSError:
                self.dead = True
                return False
            if not chunk:
                self.dead = True
                return False
            self._raw.extend(chunk)
            if len(chunk) < 65536:
                break
        try:
            self._decode()
        except ValueError:
            self.dead = True
            return False
        return True

    def pending_in(self) -> int:
        """Decoded plaintext bytes waiting to be handed to the caller."""
        return len(self._inbuf)

    def take(self, limit: int = RELAY_BUFFER) -> bytes:
        if not self._inbuf:
            return b""
        out = bytes(self._inbuf[:limit])
        del self._inbuf[:limit]
        return out

    def write_plain(self, data: bytes) -> None:
        if data:
            self._out.extend(self._encode(data))

    def flush(self) -> bool:
        """Push pending bytes out.  True when the buffer is empty (and, if a
        half-close was requested, once the FIN has been sent)."""
        while self._out:
            try:
                sent = self.sock.send(self._out)
            except BlockingIOError:
                return False
            except OSError:
                self.dead = True
                self._out.clear()
                break
            if sent <= 0:
                return False
            del self._out[:sent]
        if self._fin and not self._out:
            self._fin = False
            try:
                self.sock.shutdown(socket.SHUT_WR)
            except OSError:
                self.dead = True
        return True

    def finish(self) -> None:
        """No more outbound data: flush, then half-close."""
        self._fin = True
        self.flush()

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


class Stats:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.sessions: dict[int, dict] = {}
        self._next_id = 1
        self.total_sessions = 0
        self.up_bytes = 0          # client -> internet
        self.down_bytes = 0        # internet -> client
        self.errors = Counter()
        self.users: dict[str, dict] = {}
        self.targets = Counter()
        self.history: deque = deque(maxlen=150)     # (t, bps_up, bps_down)
        self._last = (time.time(), 0, 0, 0)
        self.started = time.time()

    # -- sessions --------------------------------------------------------
    def open_session(self, peer: str, user: str, kind: str) -> int:
        with self.lock:
            sid = self._next_id
            self._next_id += 1
            self.total_sessions += 1
            self.sessions[sid] = {
                "id": sid, "peer": peer, "user": user, "kind": kind,
                "target": "-", "start": time.time(), "up": 0, "down": 0,
            }
            u = self.users.setdefault(user, {"sessions": 0, "up": 0, "down": 0})
            u["sessions"] += 1
            return sid

    def set_target(self, sid: int, target: str) -> None:
        with self.lock:
            s = self.sessions.get(sid)
            if s is not None:
                s["target"] = target
                self.targets[target] += 1

    def add_bytes(self, sid: int, up: int = 0, down: int = 0) -> None:
        with self.lock:
            s = self.sessions.get(sid)
            if s is not None:
                s["up"] += up
                s["down"] += down
                u = self.users.get(s["user"])
                if u is not None:
                    u["up"] += up
                    u["down"] += down
            self.up_bytes += up
            self.down_bytes += down

    def close_session(self, sid: int) -> None:
        with self.lock:
            self.sessions.pop(sid, None)

    def bump_error(self, kind: str) -> None:
        with self.lock:
            self.errors[kind] += 1

    # -- rate sampling ---------------------------------------------------
    def sample(self) -> None:
        now = time.time()
        with self.lock:
            t0, u0, d0, _ = self._last
            dt = max(now - t0, 1e-6)
            up = (self.up_bytes - u0) / dt
            down = (self.down_bytes - d0) / dt
            self._last = (now, self.up_bytes, self.down_bytes, 0)
            self.history.append((now, up, down))

    def snapshot(self) -> dict:
        with self.lock:
            sessions = sorted(self.sessions.values(), key=lambda s: s["id"], reverse=True)
            for s in sessions:
                s["age_s"] = round(time.time() - s["start"], 1)
            return {
                "app": "ahura-relay",
                "version": VERSION,
                "protocol": PROTO_NAME,
                "uptime_s": round(time.time() - self.started, 1),
                "now": time.time(),
                "active_sessions": len(sessions),
                "total_sessions": self.total_sessions,
                "up_bytes": self.up_bytes,
                "down_bytes": self.down_bytes,
                "users": {k: dict(v) for k, v in self.users.items()},
                "sessions": sessions[:60],
                "top_targets": self.targets.most_common(10),
                "errors": dict(self.errors),
                "history": [{"t": t, "up": u, "down": d} for t, u, d in self.history],
                "log": [{"t": t, "lvl": lvl, "msg": msg} for t, lvl, msg in list(LOG_RING)[-60:]],
            }


STATS = Stats()
RUNTIME: dict = {"server": None, "cfg": None}   # filled in by main(), read by the dashboard
_public_ip_cache: str | None = None


def detect_public_ip() -> str:
    """Best-effort public address of this machine, for the import link.

    A connected UDP socket never sends a packet; `getsockname()` merely tells
    us which local address would be used, which on a VPS is its public IP.
    Anything private or loopback is reported as "unknown" rather than baked
    into a link that could never work.
    """
    global _public_ip_cache
    if _public_ip_cache is not None:
        return _public_ip_cache
    ip = ""
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            probe.connect(("1.1.1.1", 53))
            ip = probe.getsockname()[0]
        finally:
            probe.close()
    except OSError:
        ip = ""
    try:
        parsed = ipaddress.ip_address(ip)
        if parsed.is_private or parsed.is_loopback or parsed.is_link_local:
            ip = ""
    except ValueError:
        ip = ""
    _public_ip_cache = ip
    return ip


# --------------------------------------------------------------------------
# target policy
# --------------------------------------------------------------------------
class Policy:
    def __init__(self, cfg: "Config") -> None:
        self.allow_private = cfg.allow_private
        self.allow = [ipaddress.ip_network(c, strict=False) for c in cfg.allow_cidr]
        self.deny = [ipaddress.ip_network(c, strict=False) for c in cfg.deny_cidr]
        self.allow_ports = set(cfg.allow_ports)

    def check(self, ip: str, port: int) -> tuple[bool, str]:
        try:
            addr = ipaddress.ip_address(ip)
        except ValueError:
            return False, "bad-address"
        if not self.allow_private and (addr.is_private or addr.is_loopback or addr.is_link_local
                                       or addr.is_multicast or addr.is_reserved or addr.is_unspecified):
            return False, "private-target"
        if self.allow and not any(addr in net for net in self.allow):
            return False, "not-in-allowlist"
        if any(addr in net for net in self.deny):
            return False, "denied-cidr"
        if self.allow_ports and port not in self.allow_ports:
            return False, "port-not-allowed"
        return True, ""


# --------------------------------------------------------------------------
# config
# --------------------------------------------------------------------------
class Config:
    def __init__(self, data: dict | None = None) -> None:
        d = {
            "host": "0.0.0.0",
            "port": 1080,
            "dashboard_host": "0.0.0.0",
            "dashboard_port": 8080,
            "obfs": MODE_ANY,               # any | ahura/1 | tls | none
            "stealth_key": "",
            "tokens": [],                   # list of token strings ("user:token" keeps a label)
            "public_host": "",              # printed in the import link; "" = autodetect
            "socks_user": "",
            "socks_pass": "",
            "max_connections": 2048,
            "max_per_ip": 64,
            "idle_timeout": 600,
            "connect_timeout": 12,
            "allow_private": False,
            "allow_ports": [],
            "allow_cidr": [],
            "deny_cidr": [],
            "log_level": "info",
        }
        if data:
            d.update({k: v for k, v in data.items() if k in d})
        self.__dict__.update(d)

    @staticmethod
    def load(path: str | None) -> "Config":
        data = {}
        if path:
            with open(path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
        return Config(data)


# --------------------------------------------------------------------------
# SOCKS5 helpers
# --------------------------------------------------------------------------
CMD_CONNECT, CMD_BIND, CMD_UDP = 1, 2, 3
REP_OK, REP_GENFAIL, REP_NOTALLOWED, REP_NETUNREACH, REP_HOSTUNREACH, REP_REFUSED, REP_TTL, REP_CMD, REP_ATYP = range(9)


def _set_interest(sel: selectors.BaseSelector, sock, tag: str, reg: dict, mask: int) -> None:
    """(De)register a socket with a combined read/write interest mask."""
    if mask and not reg[tag]:
        sel.register(sock, mask, tag)
        reg[tag] = True
    elif mask and reg[tag]:
        # NOTE: pass `tag` again — modify() otherwise resets the key's data
        # to None and the pump would no longer know which socket fired.
        sel.modify(sock, mask, tag)
    elif not mask and reg[tag]:
        try:
            sel.unregister(sock)
        except (KeyError, ValueError):
            pass
        reg[tag] = False


def parse_addr(atyp: int, read) -> tuple[str, int]:
    if atyp == 0x01:
        return socket.inet_ntoa(read(4)), struct.unpack("!H", read(2))[0]
    if atyp == 0x04:
        return socket.inet_ntop(socket.AF_INET6, read(16)), struct.unpack("!H", read(2))[0]
    if atyp == 0x03:
        ln = read(1)[0]
        host = read(ln).decode("idna" if False else "utf-8", "replace")
        return host, struct.unpack("!H", read(2))[0]
    raise ValueError("bad atyp %d" % atyp)


def pack_addr(host: str, port: int) -> bytes:
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        ip = None
    if ip is not None and ip.version == 4:
        return b"\x01" + ip.packed + struct.pack("!H", port)
    if ip is not None and ip.version == 6:
        return b"\x04" + ip.packed + struct.pack("!H", port)
    raw = host.encode("idna") if any(ord(c) > 127 for c in host) else host.encode()
    return b"\x03" + bytes([len(raw)]) + raw + struct.pack("!H", port)


# --------------------------------------------------------------------------
# session (one client TCP connection)
# --------------------------------------------------------------------------
class Session(threading.Thread):
    daemon = True

    def __init__(self, sock: socket.socket, peer: tuple, server: "RelayServer") -> None:
        super().__init__(name="session")
        self.sock = sock
        self.peer_ip = peer[0]
        self.peer = "%s:%d" % (peer[0], peer[1])
        self.server = server
        self.stats = server.stats
        self.stream: RecordStream | None = None
        self.user = "anonymous"
        self.sid = -1
        self.closed = threading.Event()
        self.framed = False            # client greeted us inside a TLS record
        self.mode = MODE_AHURA         # framing mode chosen by the client

    # -- handshake -------------------------------------------------------
    def _recv_n(self, n: int) -> bytes:
        out = bytearray()
        while len(out) < n:
            chunk = self.sock.recv(n - len(out))
            if not chunk:
                break
            out.extend(chunk)
        return bytes(out)

    def _read_handshake_line(self) -> bytes:
        """Read the client's greeting.

        Two shapes are accepted on the same port: the plain line
        `AHURA/1 <nonce> <token> <mode>\\n`, and (stealth mode) the very same
        line carried inside a TLS record whose first five bytes are a perfect
        ClientHello header: `16 03 01 <len16>`.  `self.framed` remembers which
        one we saw, because the reply must be framed the same way.
        """
        first = self._recv_n(1)
        if not first:
            return b""
        if first[0] == 0x16:
            head = first + self._recv_n(4)
            if len(head) < 5:
                return b""
            outer = struct.unpack("!H", head[3:5])[0]
            if outer < 1 or outer > 4096:
                return b""
            body = self._recv_n(outer)
            if len(body) < outer:
                return b""
            self.framed = True
            return body.split(b"\n", 1)[0][:HANDSHAKE_MAX]
        self.framed = False
        buf = bytearray(first)
        deadline = time.time() + 15
        while len(buf) < HANDSHAKE_MAX:
            if time.time() > deadline:
                break
            ch = self.sock.recv(1)
            if not ch:
                break
            if ch == b"\n":
                break
            buf.extend(ch)
        return bytes(buf)

    def _reply_text(self, text: str) -> None:
        """Send a prelude reply, framed to match how the client greeted us."""
        payload = (text + "\n").encode("ascii", "replace")
        if not self.framed:
            self.sock.sendall(payload)
            return
        pad = os.urandom(random.randint(0, MAX_PAD))
        body = payload + pad
        self.sock.sendall(b"\x16\x03\x03" + struct.pack("!H", len(body)) + body)

    def _handshake(self) -> bool:
        line = self._read_handshake_line()
        parts = line.decode("ascii", "replace").strip().split(" ")
        if len(parts) == 3:
            mode = MODE_AHURA                      # clients from v1.0.0
        elif len(parts) == 4:
            mode = parts[3]
        else:
            self.stats.bump_error("bad-handshake")
            self._reply_text("%s ERR bad-handshake" % PROTO_NAME)
            return False
        if parts[0] != PROTO_NAME:
            self.stats.bump_error("bad-handshake")
            self._reply_text("%s ERR bad-handshake" % PROTO_NAME)
            return False
        if mode not in MODES_ENCRYPTED or (mode == MODE_TLS) != self.framed:
            # Asking for a framing we are not actually speaking is the one
            # thing that must never be papered over: the streams would drift.
            self.stats.bump_error("bad-mode")
            self._reply_text("%s ERR bad-mode" % PROTO_NAME)
            return False
        if not self.server.allows_mode(mode):
            self.stats.bump_error("mode-not-allowed")
            self._reply_text("%s ERR mode-not-allowed" % PROTO_NAME)
            return False
        try:
            nonce = bytes.fromhex(parts[1])
        except ValueError:
            nonce = b""
        if len(nonce) != 16:
            self.stats.bump_error("bad-nonce")
            self._reply_text("%s ERR bad-nonce" % PROTO_NAME)
            return False
        token = parts[2]
        if not self.server.check_token(token):
            self.stats.bump_error("bad-token")
            log("rejected %s: bad token" % self.peer, "warn")
            time.sleep(0.3)
            self._reply_text("%s ERR unauthorized" % PROTO_NAME)
            return False
        self.user = self.server.token_label(token)
        key_c2s, key_s2c = derive_keys(self.server.stealth_key, nonce)
        # The reply line is deliberately still in clear text: it is the switch
        # point of the protocol, carries no secret, and lets a client tell
        # "wrong token / wrong key" apart from "server is speaking something
        # else entirely".  Everything after this byte leaves encrypted.
        self._reply_text("%s OK %s" % (PROTO_NAME, mode))
        self.stream = RecordStream(self.sock, key_s2c, key_c2s, mode)
        self.mode = mode
        return True

    # -- lifecycle -------------------------------------------------------
    def start_thread(self) -> None:
        """Run this session on its own thread, keeping the server's
        active-session counters honest no matter how the thread dies."""
        def body() -> None:
            try:
                self.run()
            finally:
                self.server.session_done(self.peer_ip)
        threading.Thread(target=body, name="session", daemon=True).start()

    # -- entry point -----------------------------------------------------
    def run(self) -> None:
        try:
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            # The handshake/prelude runs on a blocking socket with a deadline;
            # the data pump that follows is fully non-blocking.
            self.sock.settimeout(self.server.cfg.connect_timeout + 10)
            if self.server.cfg.obfs == MODE_NONE:
                self.stream = RecordStream(self.sock, None, None, MODE_NONE)
            else:
                if not self._handshake():
                    return
            self.sid = self.stats.open_session(self.peer, self.user, "tcp")
            self._serve()
        except (ConnectionResetError, BrokenPipeError, TimeoutError, socket.timeout):
            pass
        except Exception as exc:          # noqa: BLE001 - never kill the server thread pool
            self.stats.bump_error(type(exc).__name__)
            log("session %s failed: %s" % (self.peer, exc), "error")
            if os.environ.get("AHURA_TRACEBACK"):
                import traceback
                traceback.print_exc()
        finally:
            if self.sid >= 0:
                self.stats.close_session(self.sid)
            try:
                self.stream.close() if self.stream else self.sock.close()
            except OSError:
                pass

    # -- socks5 ----------------------------------------------------------
    def _serve(self) -> None:
        assert self.stream is not None
        cfg = self.server.cfg
        head = self.stream.read_exact(2)
        if len(head) < 2 or head[0] != 0x05:
            self.stats.bump_error("bad-socks-version")
            return
        methods = set(self.stream.read_exact(head[1]))
        if cfg.socks_user:
            if 0x02 not in methods:
                self.stream.send_all(b"\x05\xff")
                self.stats.bump_error("no-acceptable-auth")
                return
            self.stream.send_all(b"\x05\x02")
            if not self._auth_userpass():
                return
        else:
            self.stream.send_all(b"\x05\x00")

        hdr = self.stream.read_exact(4)
        if len(hdr) < 4:
            return
        _, cmd, _, atyp = hdr
        try:
            host, port = parse_addr(atyp, self.stream.read_exact)
        except (ValueError, IndexError):
            self.stream.send_all(b"\x05" + bytes([REP_ATYP]) + b"\x00\x01\x00\x00\x00\x00\x00\x00")
            return

        if cmd == CMD_CONNECT:
            self._do_connect(host, port)
        elif cmd == CMD_UDP:
            self._do_udp()
        else:
            self._reply(REP_CMD)
            self.stats.bump_error("unsupported-cmd")

    def _auth_userpass(self) -> bool:
        assert self.stream is not None
        ver = self.stream.read_exact(1)
        if not ver or ver[0] != 0x01:
            return False
        ulen = self.stream.read_exact(1)
        user = self.stream.read_exact(ulen[0]) if ulen else b""
        plen = self.stream.read_exact(1)
        pwd = self.stream.read_exact(plen[0]) if plen else b""
        cfg = self.server.cfg
        ok = (hmac.compare_digest(user.decode("utf-8", "replace"), cfg.socks_user)
              and hmac.compare_digest(pwd.decode("utf-8", "replace"), cfg.socks_pass))
        self.stream.send_all(b"\x01\x00" if ok else b"\x01\x01")
        if not ok:
            self.stats.bump_error("auth-failed")
            return False
        self.user = cfg.socks_user
        return True

    def _reply(self, rep: int, host: str = "0.0.0.0", port: int = 0) -> None:
        assert self.stream is not None
        self.stream.send_all(b"\x05" + bytes([rep]) + b"\x00" + pack_addr(host, port))

    # -- CONNECT ---------------------------------------------------------
    def _resolve(self, host: str, port: int) -> list:
        """Resolve the target ourselves so that the policy check happens on
        the real IP, not on a hostname the client could lie about."""
        try:
            ipaddress.ip_address(host)
            return [(socket.AF_INET6 if ":" in host else socket.AF_INET, host)]
        except ValueError:
            pass
        infos = socket.getaddrinfo(host, port, proto=socket.IPPROTO_TCP)
        out = []
        for fam, _, _, _, sa in infos:
            out.append((fam, sa[0]))
        return out

    def _do_connect(self, host: str, port: int) -> None:
        assert self.stream is not None
        cfg = self.server.cfg
        last_err = "unreachable"
        for fam, ip in self._resolve(host, port):
            ok, why = self.server.policy.check(ip, port)
            if not ok:
                self.stats.bump_error(why)
                log("%s -> %s:%d refused (%s)" % (self.user, ip, port, why), "warn")
                self._reply(REP_NOTALLOWED)
                return
            try:
                remote = socket.socket(fam, socket.SOCK_STREAM)
                remote.settimeout(cfg.connect_timeout)
                remote.connect((ip, port))
                remote.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                remote.settimeout(None)
            except OSError as exc:
                last_err = str(exc)
                continue
            local = remote.getsockname()
            self._reply(REP_OK, local[0], local[1])
            self.stats.set_target(self.sid, "%s:%d" % (ip, port))
            log("%s connect %s:%d" % (self.user, ip, port), "debug")
            try:
                self._pipe(remote)
            finally:
                try:
                    remote.close()
                except OSError:
                    pass
            return
        self.stats.bump_error("connect-failed")
        log("%s connect %s:%d failed: %s" % (self.user, host, port, last_err), "warn")
        self._reply(REP_HOSTUNREACH)

    def _pipe(self, remote: socket.socket) -> None:
        """Full-duplex byte pump between the client stream and `remote`.

        Both sockets are non-blocking and driven by one selector loop with an
        explicit pending-write buffer per direction, so a slow (or stalled)
        peer can never wedge the session: back-pressure simply stops reading
        from the fast side until the slow side drains.  Half-close is honoured
        in both directions.

        Two traps this shape avoids (both were caught by the 300 KB
        round-trip test):
          * a blocking read/send loop dead-locks as soon as both peers stop
            reading at the same time;
          * bytes that were already decoded (or already queued) must be
            flushed on every wake-up, not only when fresh socket data arrives,
            otherwise the tail of a transfer waits for an event that will
            never come.
        """
        stream = self.stream
        assert stream is not None
        sel = selectors.DefaultSelector()
        remote.setblocking(False)
        self.sock.setblocking(False)

        up_read = down_read = True     # still expecting data from that side
        want_fin = False               # client half-closed -> FIN the target
        to_remote = bytearray()        # client -> target, waiting for the socket
        reg = {"client": False, "remote": False}

        def drain() -> None:
            """Hand decoded client bytes to the target socket, bounded so a
            slow target cannot make the relay buffer without limit."""
            nonlocal want_fin
            while len(to_remote) < MAX_BUFFERED and stream.pending_in():
                data = stream.take(RELAY_BUFFER)
                if not data:
                    break
                to_remote.extend(data)      # extend(), not += : closures can't rebind
                self.stats.add_bytes(self.sid, up=len(data))
            if want_fin and not to_remote and not stream.pending_in():
                try:
                    remote.shutdown(socket.SHUT_WR)
                except OSError:
                    pass
                want_fin = False

        def sync() -> None:
            mask = (selectors.EVENT_READ if up_read else 0) | \
                   (selectors.EVENT_WRITE if stream.has_pending_out() else 0)
            _set_interest(sel, self.sock, "client", reg, mask)
            mask = (selectors.EVENT_READ if down_read else 0) | \
                   (selectors.EVENT_WRITE if to_remote else 0)
            _set_interest(sel, remote, "remote", reg, mask)

        try:
            drain()
            while up_read or down_read or to_remote or stream.has_pending_out():
                sync()
                events = sel.select(timeout=self.server.cfg.idle_timeout)
                if not events:
                    self.stats.bump_error("idle-timeout")
                    break
                for key, mask in events:
                    if key.data == "client":
                        if mask & selectors.EVENT_WRITE:
                            stream.flush()
                        if (mask & selectors.EVENT_READ) and up_read:
                            if not stream.feed():
                                up_read = False
                                want_fin = True
                    else:
                        if (mask & selectors.EVENT_WRITE) and to_remote:
                            try:
                                sent = remote.send(to_remote)
                                del to_remote[:sent]
                            except BlockingIOError:
                                pass
                            except OSError:
                                to_remote.clear()
                                up_read = False
                                want_fin = False
                        if (mask & selectors.EVENT_READ) and down_read:
                            try:
                                data = remote.recv(RELAY_BUFFER)
                            except BlockingIOError:
                                data = None
                            except OSError:
                                data = b""
                            if data:
                                self.stats.add_bytes(self.sid, down=len(data))
                                stream.write_plain(data)
                                stream.flush()
                            elif data is not None:
                                down_read = False
                                stream.finish()      # flush + FIN once drained
                drain()
            stream.flush()
        finally:
            sel.close()

    # -- UDP ASSOCIATE ---------------------------------------------------
    def _do_udp(self) -> None:
        assert self.stream is not None
        cfg = self.server.cfg
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp.bind((cfg.host if cfg.host not in ("0.0.0.0", "::") else "0.0.0.0", 0))
        udp.setblocking(False)
        self.sock.setblocking(False)
        bound = udp.getsockname()
        self._reply(REP_OK, bound[0], bound[1])
        self.stats.set_target(self.sid, "udp-associate")
        log("%s udp associate :%d" % (self.user, bound[1]), "debug")

        sel = selectors.DefaultSelector()
        sel.register(self.sock, selectors.EVENT_READ, "control")
        sel.register(udp, selectors.EVENT_READ, "client-udp")
        client_addr: tuple | None = None
        peers: dict[socket.socket, tuple] = {}
        last_activity = time.time()
        idle = self.server.cfg.idle_timeout
        try:
            while True:
                events = sel.select(timeout=idle)
                if not events:
                    self.stats.bump_error("idle-timeout")
                    break
                for key, _ in events:
                    if key.data == "control":
                        # any byte on the control connection (other than the
                        # client going away) is ignored, as RFC 1928 allows
                        if not self.stream.feed():
                            return
                        self.stream.take(65536)
                        continue
                    if key.data == "client-udp":
                        try:
                            data, addr = udp.recvfrom(UDP_MAX_DATAGRAM)
                        except BlockingIOError:
                            continue
                        client_addr = addr
                        last_activity = time.time()
                        if len(data) < 4 or data[2] != 0:
                            continue
                        atyp = data[3]
                        try:
                            pos = 4
                            if atyp == 0x01:
                                tgt = socket.inet_ntoa(data[pos:pos + 4]); pos += 4
                            elif atyp == 0x04:
                                tgt = socket.inet_ntop(socket.AF_INET6, data[pos:pos + 16]); pos += 16
                            elif atyp == 0x03:
                                ln = data[pos]; pos += 1
                                tgt = data[pos:pos + ln].decode("utf-8", "replace"); pos += ln
                            else:
                                continue
                            port = struct.unpack("!H", data[pos:pos + 2])[0]; pos += 2
                        except (IndexError, struct.error):
                            continue
                        payload = data[pos:]
                        ok, why = self.server.policy.check(tgt, port)
                        if not ok:
                            self.stats.bump_error(why)
                            continue
                        sock = self._udp_peer(peers, sel, tgt, port)
                        if sock is None:
                            continue
                        try:
                            sock.sendto(payload, (tgt, port))
                            self.stats.add_bytes(self.sid, up=len(payload))
                        except OSError:
                            self._drop_peer(peers, sel, sock)
                        continue
                    # response from an upstream peer
                    src = peers.get(key.fileobj)
                    if src is None:
                        continue
                    try:
                        data, addr = key.fileobj.recvfrom(UDP_MAX_DATAGRAM)
                    except BlockingIOError:
                        continue
                    except OSError:
                        self._drop_peer(peers, sel, key.fileobj)
                        continue
                    last_activity = time.time()
                    if client_addr:
                        header = b"\x00\x00\x00" + pack_addr(src[0], src[1])
                        try:
                            udp.sendto(header + data, client_addr)
                            self.stats.add_bytes(self.sid, down=len(data))
                        except OSError:
                            pass
                    _ = addr
                if time.time() - last_activity > idle:
                    break
        finally:
            for s in list(peers):
                self._drop_peer(peers, sel, s)
            sel.close()
            try:
                udp.close()
            except OSError:
                pass

    def _udp_peer(self, peers: dict, sel, host: str, port: int) -> socket.socket | None:
        for sock, tgt in peers.items():
            if tgt == (host, port):
                return sock
        if len(peers) > 512:
            return None
        try:
            fam = socket.AF_INET6 if ":" in host else socket.AF_INET
            sock = socket.socket(fam, socket.SOCK_DGRAM)
            sock.setblocking(False)
            sock.connect((host, port))
        except OSError:
            self.stats.bump_error("udp-peer")
            return None
        peers[sock] = (host, port)
        sel.register(sock, selectors.EVENT_READ, "peer")
        return sock

    def _drop_peer(self, peers: dict, sel, sock) -> None:
        peers.pop(sock, None)
        try:
            sel.unregister(sock)
        except (KeyError, ValueError):
            pass
        try:
            sock.close()
        except OSError:
            pass


# --------------------------------------------------------------------------
# relay server
# --------------------------------------------------------------------------
class RelayServer:
    def __init__(self, cfg: Config) -> None:
        self.cfg = cfg
        self.stats = STATS
        self.policy = Policy(cfg)
        self.stop = threading.Event()
        self._active = 0
        self._per_ip: Counter = Counter()
        self._lock = threading.Lock()
        key = cfg.stealth_key.strip()
        if cfg.obfs != MODE_NONE and not key:
            key = secrets.token_hex(16)
            log("no stealth_key configured — generated one for this run: %s" % key, "warn")
        self.stealth_key = bytes.fromhex(key) if len(key) == 32 and all(c in "0123456789abcdefABCDEF" for c in key) else key.encode()
        self.tokens = self._parse_tokens(cfg.tokens)

    def allows_mode(self, mode: str) -> bool:
        want = self.cfg.obfs
        return want == MODE_ANY or want == mode

    def first_token(self) -> str:
        for tok in self.tokens:
            return tok
        return "-"

    def import_uri(self, host: str | None = None) -> str:
        """`ahura://` link the Android app can import in one tap."""
        host = host or self.cfg.public_host or detect_public_ip()
        if not host:
            return ""
        return "ahura://relay@%s:%d?key=%s&token=%s&obfs=%s&name=relay" % (
            host, self.cfg.port, self.stealth_key.decode("utf-8", "replace"),
            self.first_token(), self.cfg.obfs if self.cfg.obfs != MODE_ANY else MODE_TLS)

    @staticmethod
    def _parse_tokens(raw) -> dict:
        out = {}
        for item in raw or []:
            if isinstance(item, str):
                if ":" in item:
                    label, tok = item.split(":", 1)
                    out[tok] = label
                else:
                    out[item] = item
            elif isinstance(item, dict):
                out[str(item.get("token", ""))] = str(item.get("label", item.get("token", "")))
        return {k: v for k, v in out.items() if k}

    def check_token(self, token: str) -> bool:
        if not self.tokens:
            return True                     # open relay: no stealth auth required
        return any(hmac.compare_digest(token, t) for t in self.tokens)

    def token_label(self, token: str) -> str:
        return self.tokens.get(token, "anonymous")

    # -- socket setup ----------------------------------------------------
    def _listen(self, host: str, port: int) -> socket.socket:
        if ":" in host:
            s = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
            try:
                s.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
            except OSError:
                pass
        else:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((host, port))
        s.listen(256)
        s.settimeout(0.5)
        return s

    def serve_forever(self) -> None:
        cfg = self.cfg
        srv = self._listen(cfg.host, cfg.port)
        log("relay listening on %s:%d (obfs=%s, auth=%s, policy: private=%s allowlist=%s)"
            % (cfg.host, cfg.port, cfg.obfs,
               "token" if self.tokens else ("userpass" if cfg.socks_user else "none"),
               cfg.allow_private, cfg.allow_cidr or "all"), "info")
        while not self.stop.is_set():
            try:
                conn, peer = srv.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            with self._lock:
                allowed = self._active < cfg.max_connections and self._per_ip[peer[0]] < cfg.max_per_ip
                if allowed:
                    self._active += 1
                    self._per_ip[peer[0]] += 1
            if not allowed:
                self.stats.bump_error("too-many-connections")
                try:
                    conn.close()
                except OSError:
                    pass
                continue
            Session(conn, peer, self).start_thread()

    def session_done(self, peer_ip: str) -> None:
        with self._lock:
            self._active = max(0, self._active - 1)
            if self._per_ip[peer_ip] <= 1:
                self._per_ip.pop(peer_ip, None)
            else:
                self._per_ip[peer_ip] -= 1

    def shutdown(self) -> None:
        self.stop.set()



# --------------------------------------------------------------------------
# dashboard
# --------------------------------------------------------------------------
DASHBOARD_HTML = r"""<!doctype html>
<html lang="fa" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Ahura Relay — داشبورد</title>
<style>
  :root{--bg:#0b1020;--card:#141b31;--card2:#1b2440;--fg:#e8ecf8;--mut:#8b98b8;--ok:#31d0aa;--warn:#ffb454;--err:#ff6b81;--acc:#7c8cff}
  *{box-sizing:border-box}
  body{margin:0;background:radial-gradient(1200px 600px at 80% -10%,#1c2a55,#0b1020 60%);color:var(--fg);
       font-family:Vazirmatn,IRANSans,Tahoma,system-ui,-apple-system,sans-serif;padding:24px}
  h1{font-size:22px;margin:0 0 4px;display:flex;align-items:center;gap:10px}
  .dot{width:10px;height:10px;border-radius:50%;background:var(--ok);box-shadow:0 0 12px var(--ok);animation:p 2s infinite}
  @keyframes p{50%{opacity:.35}}
  .sub{color:var(--mut);font-size:13px;margin-bottom:18px}
  .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:14px}
  .card{background:linear-gradient(180deg,var(--card2),var(--card));border:1px solid #26314f;border-radius:16px;padding:16px}
  .k{color:var(--mut);font-size:12px;margin-bottom:6px}
  .v{font-size:24px;font-weight:700;font-variant-numeric:tabular-nums}
  .v small{font-size:12px;color:var(--mut);font-weight:400}
  .row{display:flex;gap:14px;flex-wrap:wrap;margin-top:14px}
  .row>.card{flex:1 1 340px}
  canvas{width:100%;height:90px;display:block}
  table{width:100%;border-collapse:collapse;font-size:13px}
  th,td{text-align:right;padding:7px 6px;border-bottom:1px solid #232e4b;white-space:nowrap}
  th{color:var(--mut);font-weight:500;font-size:12px}
  code{background:#0e1530;padding:2px 6px;border-radius:6px;font-size:12px;direction:ltr;display:inline-block}
  .scroll{max-height:300px;overflow:auto}
  .lg{font-family:ui-monospace,Menlo,monospace;font-size:12px;direction:ltr;text-align:left;color:#c7d0ea}
  .lg .warn{color:var(--warn)}.lg .error{color:var(--err)}.lg .debug{color:var(--mut)}
  .pill{background:#0e1530;border:1px solid #26314f;border-radius:999px;padding:3px 10px;font-size:12px;color:var(--mut)}
  .foot{color:var(--mut);font-size:12px;margin-top:18px}
</style>
</head>
<body>
<h1><span class="dot"></span>Ahura Relay <span class="pill" id="ver">—</span></h1>
<div class="sub">سرور رله آهورا مزدا — وضعیت زنده تانل، مصرف ترافیک و نشست‌ها (هر ۲ ثانیه به‌روز می‌شود)</div>

<div class="grid">
  <div class="card"><div class="k">ترافیک آپلود (دستگاه ← اینترنت)</div><div class="v" id="up">0 <small>B</small></div></div>
  <div class="card"><div class="k">ترافیک دانلود (اینترنت ← دستگاه)</div><div class="v" id="down">0 <small>B</small></div></div>
  <div class="card"><div class="k">نشست‌های فعال</div><div class="v" id="act">0</div></div>
  <div class="card"><div class="k">کل نشست‌ها / آپ‌تایم</div><div class="v" id="tot">0</div></div>
</div>

<div class="row">
  <div class="card"><div class="k">نمودار پهنای باند (بایت بر ثانیه)</div><canvas id="spark" width="600" height="90"></canvas>
    <div class="sub" id="rate" style="margin:6px 0 0"></div></div>
  <div class="card"><div class="k">پروفایل‌های کاربری</div><div id="users" class="scroll"></div></div>
</div>

<div class="card" style="margin-top:14px">
  <div class="k">لینک اتصال اپ اندروید (کپی کنید و در اپ وارد کنید)</div>
  <div class="sub" id="uri" style="margin:6px 0 10px">—</div>
  <div class="k">حالت پنهان‌سازی این پورت</div>
  <div class="pill" id="obfs">—</div>
</div>

<div class="row">
  <div class="card"><div class="k">نشست‌های فعال</div><div id="sessions" class="scroll"></div></div>
  <div class="card"><div class="k">مقصدهای پرترافیک</div><div id="targets" class="scroll"></div>
    <div class="k" style="margin-top:12px">خطاها</div><div id="errors"></div></div>
</div>

<div class="card" style="margin-top:14px"><div class="k">گزارش زنده سرور</div><div id="log" class="lg scroll"></div></div>
<div class="foot">Ahura Relay v<span id="ver2"></span> — برای اتصال کلاینت اندروید از <code id="ep">ip:port</code> استفاده کنید.</div>

<script>
const fmt = b => { const u=['B','KB','MB','GB','TB']; let i=0; b=Number(b)||0;
  while (b>=1024 && i<u.length-1){b/=1024;i++} return (b<10&&i>0?b.toFixed(1):Math.round(b))+' '+u[i]; };
const esc = s => String(s).replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
let hist=[];
function table(rows, head){ return '<table><tr>'+head.map(h=>'<th>'+h+'</th>').join('')+'</tr>'+
  rows.map(r=>'<tr>'+r.map(c=>'<td>'+c+'</td>').join('')+'</tr>').join('')+'</table>'; }
function draw(){
  const c=document.getElementById('spark'); if(!c) return; const g=c.getContext('2d');
  const W=c.width,H=c.height; g.clearRect(0,0,W,H);
  const up=hist.map(h=>h.up), dn=hist.map(h=>h.down); const max=Math.max(1024,...up,...dn);
  const line=(arr,color)=>{ g.beginPath(); g.strokeStyle=color; g.lineWidth=2;
    arr.forEach((v,i)=>{ const x=W-(i/(Math.max(arr.length-1,1)))*W, y=H-(v/max)*(H-8)-4;
      i?g.lineTo(x,y):g.moveTo(x,y); }); g.stroke();
    g.lineTo(W,H); g.lineTo(arr.length?W-(arr.length-1)/Math.max(arr.length-1,1)*W:W,H); g.closePath();
    g.fillStyle=color+'22'; g.fill(); };
  line(dn,'#31d0aa'); line(up,'#7c8cff');
  g.fillStyle='#8b98b8'; g.font='11px Tahoma'; g.textAlign='right';
  g.fillText('پیک: '+fmt(max)+'/s',W-4,12);
}
async function tick(){
  try{
    const r=await fetch('api/stats'); const d=await r.json();
    document.getElementById('ver').textContent=d.protocol+' · v'+d.version;
    document.getElementById('ver2').textContent=d.version;
    document.getElementById('up').innerHTML=fmt(d.up_bytes);
    document.getElementById('down').innerHTML=fmt(d.down_bytes);
    document.getElementById('act').textContent=d.active_sessions;
    document.getElementById('tot').innerHTML=d.total_sessions+' <small>· '+Math.round(d.uptime_s)+'s</small>';
    document.getElementById('ep').textContent=location.hostname+':'+(d.port||1080);
    document.getElementById('obfs').textContent=d.obfs||'—';
    document.getElementById('uri').innerHTML = d.import_uri ?
      '<code id="uricode">'+esc(d.import_uri)+'</code>' : '— (public_host را در کانفیگ تنظیم کنید)';
    const last=d.history[d.history.length-1]||{up:0,down:0};
    document.getElementById('rate').textContent='آپلود '+fmt(last.up)+'/s — دانلود '+fmt(last.down)+'/s';
    hist=d.history.slice(-120); draw();
    document.getElementById('users').innerHTML = Object.keys(d.users).length ?
      table(Object.entries(d.users).map(([k,v])=>['<code>'+esc(k)+'</code>',v.sessions,fmt(v.up),fmt(v.down)]),
            ['کاربر','نشست','آپلود','دانلود']) : '<div class="sub">هنوز کاربری وصل نشده است.</div>';
    document.getElementById('sessions').innerHTML = d.sessions.length ?
      table(d.sessions.map(s=>[s.id,'<code>'+esc(s.target)+'</code>',esc(s.user),esc(s.peer),fmt(s.up),fmt(s.down),s.age_s+'s']),
            ['#','مقصد','کاربر','کلاینت','آپلود','دانلود','عمر']) : '<div class="sub">بدون نشست فعال.</div>';
    document.getElementById('targets').innerHTML = d.top_targets.length ?
      table(d.top_targets.map(t=>['<code>'+esc(t[0])+'</code>',t[1]]),['مقصد','تعداد']) : '<div class="sub">—</div>';
    document.getElementById('errors').innerHTML = Object.keys(d.errors).length ?
      table(Object.entries(d.errors).map(([k,v])=>[esc(k),v]),['نوع','تعداد']) : '<div class="sub">بدون خطا</div>';
    document.getElementById('log').innerHTML = d.log.slice(-40).reverse()
      .map(l=>'<div class="'+esc(l.lvl)+'">'+esc(l.msg)+'</div>').join('');
  }catch(e){ /* dashboard must never crash the relay */ }
}
tick(); setInterval(tick,2000);
</script>
</body></html>
"""


class DashboardHandler(BaseHTTPRequestHandler):
    server_version = "AhuraRelay/" + VERSION

    def do_GET(self) -> None:      # noqa: N802
        path = self.path.split("?")[0]
        if path in ("/", "/index.html", "/dashboard"):
            body = DASHBOARD_HTML.encode("utf-8")
            self._send(200, "text/html; charset=utf-8", body)
        elif path in ("/api/stats", "/stats", "/api/stats/"):
            snapshot = STATS.snapshot()
            srv = RUNTIME.get("server")
            if srv is not None:
                snapshot["obfs"] = srv.cfg.obfs
                snapshot["port"] = srv.cfg.port
                snapshot["stealth_key"] = srv.stealth_key.decode("utf-8", "replace")
                snapshot["import_uri"] = srv.import_uri()
            body = json.dumps(snapshot, ensure_ascii=False).encode("utf-8")
            self._send(200, "application/json; charset=utf-8", body)
        elif path == "/healthz":
            self._send(200, "text/plain; charset=utf-8", b"ok\n")
        else:
            self._send(404, "text/plain; charset=utf-8", b"not found\n")

    def _send(self, code: int, ctype: str, body: bytes) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except OSError:
            pass

    def log_message(self, fmt: str, *args) -> None:
        if _log_level <= LOG_LEVELS["debug"]:
            log("dashboard: " + fmt % args, "debug")


def start_dashboard(cfg: Config) -> ThreadingHTTPServer:
    httpd = ThreadingHTTPServer((cfg.dashboard_host, cfg.dashboard_port), DashboardHandler)
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, name="dashboard", daemon=True).start()
    log("dashboard on http://%s:%d" % (cfg.dashboard_host, cfg.dashboard_port))
    return httpd


def start_sampler() -> None:
    def loop() -> None:
        while True:
            time.sleep(1.0)
            try:
                STATS.sample()
            except Exception:      # noqa: BLE001
                pass
    threading.Thread(target=loop, name="sampler", daemon=True).start()


# --------------------------------------------------------------------------
# cli
# --------------------------------------------------------------------------
def parse_args(argv: list[str]) -> Config:
    p = argparse.ArgumentParser("ahura_relay", description="Ahura Relay — SOCKS5 relay + AHURA/1 obfuscation + dashboard")
    p.add_argument("-c", "--config", help="JSON config file")
    p.add_argument("--host", help="listen address (use :: for dual-stack)")
    p.add_argument("-p", "--port", type=int, help="listen port (default 1080)")
    p.add_argument("--dashboard-host")
    p.add_argument("--dashboard-port", type=int)
    p.add_argument("--obfs", choices=[MODE_ANY, MODE_AHURA, MODE_TLS, MODE_NONE],
                   help="framing accepted on the port: any (default) | ahura/1 | tls | none")
    p.add_argument("--stealth-key", help="shared secret for AHURA/1 (hex or text)")
    p.add_argument("--public-host", help="host/IP printed in the ahura:// import link")
    p.add_argument("--token", action="append", default=[], metavar="[LABEL:]TOKEN",
                   help="stealth token allowed to connect (repeatable)")
    p.add_argument("--socks-user")
    p.add_argument("--socks-pass")
    p.add_argument("--allow-private", action="store_true", help="allow targets in private ranges")
    p.add_argument("--allow-cidr", action="append", default=[], help="only relay to these CIDRs (repeatable)")
    p.add_argument("--deny-cidr", action="append", default=[])
    p.add_argument("--allow-port", action="append", type=int, default=[])
    p.add_argument("--max-connections", type=int)
    p.add_argument("--max-per-ip", type=int)
    p.add_argument("--idle-timeout", type=int)
    p.add_argument("--log-level", choices=list(LOG_LEVELS))
    args = p.parse_args(argv)

    cfg = Config.load(args.config)
    for key, val in vars(args).items():
        if key in ("config", "token", "allow_cidr", "deny_cidr", "allow_port", "allow_private", "stealth_key") or val is None:
            continue
        if hasattr(cfg, key):
            setattr(cfg, key, val)
    if args.stealth_key:
        cfg.stealth_key = args.stealth_key
    if args.allow_private:
        cfg.allow_private = True
    if args.allow_cidr:
        cfg.allow_cidr = args.allow_cidr
    if args.deny_cidr:
        cfg.deny_cidr = args.deny_cidr
    if args.allow_port:
        cfg.allow_ports = args.allow_port
    if args.token:
        cfg.tokens = args.token
    if os.environ.get("AHURA_STEALTH_KEY"):
        cfg.stealth_key = os.environ["AHURA_STEALTH_KEY"]
    return cfg


def main(argv: list[str] | None = None) -> int:
    global _log_level
    cfg = parse_args(list(sys.argv[1:] if argv is None else argv))
    _log_level = LOG_LEVELS.get(cfg.log_level, LOG_LEVELS["info"])

    server = RelayServer(cfg)
    RUNTIME["server"] = server
    RUNTIME["cfg"] = cfg
    detect_public_ip()
    start_dashboard(cfg)
    start_sampler()
    log("framing modes accepted: %s" % cfg.obfs)
    uri = server.import_uri()
    if uri:
        log("import link for the Android app:\n    %s" % uri)
    else:
        log("set --public-host to print a ready-to-import ahura:// link", "warn")

    def bye(signum, _frame):        # noqa: ANN001
        log("signal %s received — shutting down" % signum, "warn")
        server.shutdown()
        sys.exit(0)

    signal.signal(signal.SIGINT, bye)
    signal.signal(signal.SIGTERM, bye)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
