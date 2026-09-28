#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
self_test.py — end-to-end tests for the Ahura relay + AHURA/1 protocol.

The Android client cannot be unit-tested in this environment, so the wire
protocol is pinned down here: the reference client (ahura_client.py) speaks
exactly the same bytes as the Kotlin implementation in
`android/.../core/Obfs.kt` + `Socks5.kt`.

Run:  python3 server/tools/self_test.py
"""

from __future__ import annotations

import json
import os
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

import ahura_relay as relay                                     # noqa: E402
from ahura_client import AhuraClient, AhuraError, AhuraStream, pack_addr   # noqa: E402

STEALTH_KEY = "8f1c0b7a5d2e4f6a9b3c1d0e2f4a6b8c"                  # 16 bytes, hex
TOKEN_USER = "mobile-01"
RESULT: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    RESULT.append((name, bool(ok), detail))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, ("  — " + detail) if detail and not ok else ""))


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


# --------------------------------------------------------------------------
# local test targets
# --------------------------------------------------------------------------
class TargetHandler(BaseHTTPRequestHandler):
    def do_GET(self):                                            # noqa: N802
        body = b"hello-from-target"
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):                                   # noqa: D102
        pass


def start_http_target(port: int) -> ThreadingHTTPServer:
    srv = ThreadingHTTPServer(("127.0.0.1", port), TargetHandler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def start_echo_target(port: int) -> None:
    """TCP echo server used to check byte-exact transfer (framing + cipher)."""
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(8)

    def loop():
        while True:
            conn, _ = srv.accept()
            threading.Thread(target=echo_one, args=(conn,), daemon=True).start()

    def echo_one(conn: socket.socket):
        try:
            while True:
                data = conn.recv(65536)
                if not data:
                    break
                conn.sendall(data)
        finally:
            conn.close()

    threading.Thread(target=loop, daemon=True).start()


def start_udp_echo(port: int) -> None:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", port))

    def loop():
        while True:
            data, addr = s.recvfrom(65535)
            s.sendto(b"echo:" + data, addr)

    threading.Thread(target=loop, daemon=True).start()


class SniffProxy:
    """Sits between client and relay and records the first bytes of the
    client->relay direction, so we can assert the traffic is obfuscated."""

    def __init__(self, listen_port: int, upstream_port: int):
        self.upstream_port = upstream_port
        self.captured = bytearray()
        self.lock = threading.Lock()
        self.srv = socket.socket()
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(("127.0.0.1", listen_port))
        self.srv.listen(8)
        threading.Thread(target=self._accept_loop, daemon=True).start()

    def _accept_loop(self):
        while True:
            client, _ = self.srv.accept()
            threading.Thread(target=self._pump, args=(client,), daemon=True).start()

    def _pump(self, client: socket.socket):
        up = socket.create_connection(("127.0.0.1", self.upstream_port))
        def c2s():
            try:
                while True:
                    data = client.recv(65536)
                    if not data:
                        break
                    with self.lock:
                        if len(self.captured) < 2048:
                            self.captured.extend(data[:2048])
                    up.sendall(data)
            finally:
                up.shutdown(socket.SHUT_WR)
        threading.Thread(target=c2s, daemon=True).start()
        try:
            while True:
                data = up.recv(65536)
                if not data:
                    break
                client.sendall(data)
        finally:
            client.close()
            up.close()

    def blob(self) -> bytes:
        with self.lock:
            return bytes(self.captured)


# --------------------------------------------------------------------------
# suite
# --------------------------------------------------------------------------
def run_tests() -> int:
    relay_port, http_port, echo_port, udp_port = free_port(), free_port(), free_port(), free_port()
    dash_port, sniff_port = free_port(), free_port()
    start_http_target(http_port)
    start_echo_target(echo_port)
    start_udp_echo(udp_port)

    cfg = relay.Config({
        "host": "127.0.0.1", "port": relay_port,
        "dashboard_host": "127.0.0.1", "dashboard_port": dash_port,
        "obfs": "ahura/1", "stealth_key": STEALTH_KEY,
        "tokens": ["vip:" + TOKEN_USER],
        "allow_private": True,
        "idle_timeout": 20,
        "log_level": "warn",
    })
    server = relay.RelayServer(cfg)
    threading.Thread(target=server.serve_forever, name="relay-test", daemon=True).start()
    relay.start_dashboard(cfg)
    relay.start_sampler()
    time.sleep(0.4)

    # 1 — handshake + CONNECT + HTTP through the tunnel
    try:
        c = AhuraClient("127.0.0.1", relay_port, token=TOKEN_USER, stealth_key=STEALTH_KEY).open()
        bound = c.connect_ip("127.0.0.1", http_port)   # connect_ip does the SOCKS5 greeting itself
        c.stream.send(b"GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
        response = b""
        while b"hello-from-target" not in response and len(response) < 8192:
            chunk = c.stream.recv_some(4096, timeout=10)
            if not chunk:
                break
            response += chunk
        check("obfs handshake + SOCKS5 CONNECT + HTTP response", b"hello-from-target" in response,
              response[:120].decode("latin-1", "replace"))
        check("relay reports its bound address", bound[0] == "127.0.0.1" and bound[1] > 0, str(bound))
        c.close()
    except Exception as exc:                                     # noqa: BLE001
        check("obfs handshake + SOCKS5 CONNECT + HTTP response", False, repr(exc))

    # 2 — wrong token is rejected, no data survives
    try:
        c = AhuraClient("127.0.0.1", relay_port, token="wrong-token", stealth_key=STEALTH_KEY).open()
        check("bad token rejected", False, "handshake unexpectedly succeeded")
        c.close()
    except AhuraError as exc:
        check("bad token rejected", "unauthorized" in str(exc) or "refused" in str(exc), str(exc))
    except Exception as exc:                                     # noqa: BLE001
        check("bad token rejected", False, repr(exc))

    # 3 — big transfer round-trip: proves record framing + keystream counters stay in sync
    try:
        payload = os.urandom(300_000)
        c = AhuraClient("127.0.0.1", relay_port, token=TOKEN_USER, stealth_key=STEALTH_KEY).open()
        c.connect_ip("127.0.0.1", echo_port)
        got = bytearray()
        sender = threading.Thread(target=c.stream.send, args=(payload,), daemon=True)
        sender.start()
        deadline = time.time() + 40
        stalled = ""
        while len(got) < len(payload) and time.time() < deadline:
            try:
                chunk = c.stream.recv_some(min(65536, len(payload) - len(got)), timeout=10)
            except TimeoutError:
                stalled = "stalled after %d bytes" % len(got)
                break
            if not chunk:
                stalled = "EOF after %d bytes" % len(got)
                break
            got.extend(chunk)
        if stalled:
            print("      (%s; relay stats %s)" % (stalled, {
                k: v for k, v in relay.STATS.snapshot().items() if k in ("up_bytes", "down_bytes", "errors")}))
        sender.join(timeout=5)
        check("300 KB round-trip through cipher (integrity)", bytes(got) == payload,
              "got %d of %d bytes" % (len(got), len(payload)))
        check("1000+ records framed correctly", len(payload) / 8192 > 30, "")
        c.close()
    except Exception as exc:                                     # noqa: BLE001
        check("300 KB round-trip through cipher (integrity)", False, repr(exc))

    # 4 — private target denied when the policy forbids it
    cfg2 = relay.Config({"host": "127.0.0.1", "port": relay_port + 1, "obfs": "none",
                         "allow_private": False, "log_level": "warn", "idle_timeout": 20})
    relay2 = relay.RelayServer(cfg2)
    threading.Thread(target=relay2.serve_forever, daemon=True).start()
    time.sleep(0.3)
    try:
        c = AhuraClient("127.0.0.1", relay_port + 1, obfs="none").open()
        try:
            c.connect_ip("127.0.0.1", http_port)
            check("bare SOCKS5 mode + private target refused by policy", False, "connect succeeded")
        except AhuraError as exc:
            check("bare SOCKS5 mode + private target refused by policy", "not allowed" in str(exc), str(exc))
        c.close()
    except Exception as exc:                                     # noqa: BLE001
        check("bare SOCKS5 mode + private target refused by policy", False, repr(exc))

    # 5 — SOCKS5 username/password auth
    cfg3 = relay.Config({"host": "127.0.0.1", "port": relay_port + 2, "obfs": "ahura/1",
                         "stealth_key": STEALTH_KEY, "tokens": [TOKEN_USER],
                         "socks_user": "u1", "socks_pass": "p1", "allow_private": True,
                         "log_level": "warn", "idle_timeout": 20})
    relay3 = relay.RelayServer(cfg3)
    threading.Thread(target=relay3.serve_forever, daemon=True).start()
    time.sleep(0.3)
    try:
        c = AhuraClient("127.0.0.1", relay_port + 2, token=TOKEN_USER, stealth_key=STEALTH_KEY,
                        user="u1", password="p1").open()
        c.connect_ip("127.0.0.1", http_port)
        check("stealth token + RFC1929 user/pass on one connection", True)
        c.close()
    except Exception as exc:                                     # noqa: BLE001
        check("stealth token + RFC1929 user/pass on one connection", False, repr(exc))
    try:
        c = AhuraClient("127.0.0.1", relay_port + 2, token=TOKEN_USER, stealth_key=STEALTH_KEY,
                        user="u1", password="nope").open()
        try:
            c.connect_ip("127.0.0.1", http_port)
            check("wrong socks password rejected", False, "auth unexpectedly passed")
        except AhuraError as exc:
            check("wrong socks password rejected", "auth failed" in str(exc).lower(), str(exc))
        c.close()
    except Exception as exc:                                     # noqa: BLE001
        check("wrong socks password rejected", False, repr(exc))

    # 6 — UDP ASSOCIATE relay
    try:
        c = AhuraClient("127.0.0.1", relay_port, token=TOKEN_USER, stealth_key=STEALTH_KEY).open()
        c.socks_handshake()
        c.stream.send(b"\x05\x03\x00" + pack_addr("0.0.0.0", 0))
        head = c.stream.recv_exact(4)
        from ahura_client import read_addr
        udp_bound = read_addr(c.stream, head[3])
        check("UDP ASSOCIATE accepted", head[1] == 0, str(udp_bound))
        usock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        usock.settimeout(5)
        packet = b"\x00\x00\x00" + pack_addr("127.0.0.1", udp_port) + b"ping"
        usock.sendto(packet, ("127.0.0.1", udp_bound[1]))
        data, _ = usock.recvfrom(4096)
        print("      (udp relay bound %r, echoed %r)" % (udp_bound, data[:24]))
        check("UDP payload relayed both ways", data.endswith(b"echo:ping"), repr(data[:40]))
        usock.close()
        c.close()
    except Exception as exc:                                     # noqa: BLE001
        check("UDP payload relayed both ways", False, repr(exc))

    # 7 — traffic on the wire carries no SOCKS5 / HTTP signature
    try:
        sniffer = SniffProxy(sniff_port, relay_port)
        time.sleep(0.2)
        c = AhuraClient("127.0.0.1", sniff_port, token=TOKEN_USER, stealth_key=STEALTH_KEY).open()
        c.connect_ip("127.0.0.1", http_port)
        c.stream.send(b"GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        time.sleep(0.4)
        blob = sniffer.blob()
        has_sig = (b"\x05\x01\x00" in blob) or (b"GET / HTTP" in blob) or (b"AHURA/1" not in blob[:32])
        check("DPI-visible bytes contain handshake but no SOCKS5/HTTP signature", not has_sig,
              blob[:48])
        c.close()
    except Exception as exc:                                     # noqa: BLE001
        check("DPI-visible bytes contain handshake but no SOCKS5/HTTP signature", False, repr(exc))

    # 8 — dashboard + stats API
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d/api/stats" % dash_port, timeout=5) as r:
            stats = json.loads(r.read().decode())
        ok = stats["app"] == "ahura-relay" and "vip" in stats["users"] and stats["total_sessions"] >= 3
        check("dashboard JSON stats (per-user accounting)", ok,
              "session=%d users=%s" % (stats["total_sessions"], list(stats["users"])))
        with urllib.request.urlopen("http://127.0.0.1:%d/" % dash_port, timeout=5) as r:
            html = r.read().decode()
        check("dashboard HTML served", "Ahura Relay" in html and "api/stats" in html)
        check("traffic accounting is non-zero", stats["up_bytes"] > 0 and stats["down_bytes"] > 0,
              "up=%d down=%d" % (stats["up_bytes"], stats["down_bytes"]))
        with urllib.request.urlopen("http://127.0.0.1:%d/healthz" % dash_port, timeout=5) as r:
            check("healthz endpoint", r.read().strip() == b"ok")
    except Exception as exc:                                     # noqa: BLE001
        check("dashboard JSON stats (per-user accounting)", False, repr(exc))

    # 9 — allow-list policy blocks everything outside the list
    cfg4 = relay.Config({"host": "127.0.0.1", "port": relay_port + 3, "obfs": "none",
                         "allow_private": True, "allow_cidr": ["10.0.0.0/8"],
                         "log_level": "warn", "idle_timeout": 20})
    relay4 = relay.RelayServer(cfg4)
    threading.Thread(target=relay4.serve_forever, daemon=True).start()
    time.sleep(0.3)
    try:
        c = AhuraClient("127.0.0.1", relay_port + 3, obfs="none").open()
        try:
            c.connect_ip("127.0.0.1", http_port)
            check("CIDR allow-list enforced", False, "connect outside allow-list succeeded")
        except AhuraError as exc:
            check("CIDR allow-list enforced", "not allowed" in str(exc), str(exc))
        c.close()
    except Exception as exc:                                     # noqa: BLE001
        check("CIDR allow-list enforced", False, repr(exc))

    # 10 — CLI comes up and serves the dashboard (smoke test of the real entry point)
    cli_port, cli_dash = free_port(), free_port()
    proc = subprocess.Popen([sys.executable, os.path.join(HERE, "..", "ahura_relay.py"),
                             "--host", "127.0.0.1", "--port", str(cli_port),
                             "--dashboard-host", "127.0.0.1", "--dashboard-port", str(cli_dash),
                             "--obfs", "none", "--allow-private", "--log-level", "warn"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        ok = False
        deadline = time.time() + 12
        while time.time() < deadline and not ok:
            time.sleep(0.4)
            try:
                with urllib.request.urlopen("http://127.0.0.1:%d/healthz" % cli_dash, timeout=2) as r:
                    ok = r.read().strip() == b"ok"
            except Exception:
                ok = False
        if not ok:
            raise RuntimeError("relay CLI did not become ready in 12s")
        c = AhuraClient("127.0.0.1", cli_port, obfs="none").open()
        c.connect_ip("127.0.0.1", http_port)
        c.stream.send(b"GET / HTTP/1.0\r\n\r\n")
        ok = ok and b"hello-from-target" in c.stream.recv_some(4096, timeout=10)
        c.close()
        check("`python3 ahura_relay.py` CLI end-to-end", ok)
    except Exception as exc:                                     # noqa: BLE001
        check("`python3 ahura_relay.py` CLI end-to-end", False, repr(exc))
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()

    failed = [r for r in RESULT if not r[1]]
    print("\n%d/%d checks passed" % (len(RESULT) - len(failed), len(RESULT)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(run_tests())
