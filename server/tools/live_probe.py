#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""live_probe.py — proves the relay works against the real internet.

Starts an Ahura relay in-process on a random port, then performs a real HTTP
request through the tunnel (AHURA/1 + SOCKS5 CONNECT) and prints status,
latency and throughput — in both encrypted framings, the compact `ahura/1` one
and the new TLS-lookalike `tls` one.  Requires outbound internet access.

    python3 server/tools/live_probe.py [host:port ...]
"""
from __future__ import annotations

import os
import socket
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

import ahura_relay as relay                                     # noqa: E402
from ahura_client import AhuraClient, AhuraError                # noqa: E402

STEALTH_KEY = "8f1c0b7a5d2e4f6a9b3c1d0e2f4a6b8c"
TOKEN = "live-probe"
DEFAULT_TARGETS = ["github.com:80", "pypi.org:80", "registry.npmjs.org:80"]
MODES = ["tls", "ahura/1"]


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def resolve(host: str, port: int) -> str:
    return socket.getaddrinfo(host, port, proto=socket.IPPROTO_TCP)[0][4][0]


def main(argv=None) -> int:
    targets = sys.argv[1:] or DEFAULT_TARGETS
    port = free_port()
    cfg = relay.Config({
        "host": "127.0.0.1", "port": port,
        "dashboard_host": "127.0.0.1", "dashboard_port": free_port(),
        "obfs": "any", "stealth_key": STEALTH_KEY, "tokens": [TOKEN],
        "allow_private": False, "log_level": "warn", "idle_timeout": 60,
    })
    server = relay.RelayServer(cfg)
    threading.Thread(target=server.serve_forever, name="relay-live", daemon=True).start()
    time.sleep(0.3)
    print("relay on 127.0.0.1:%d — probing the real internet through it\n" % port)

    failures = 0
    for target in targets:
        host, port_s = target.rsplit(":", 1)
        try:
            ip = resolve(host, int(port_s))
        except OSError as exc:
            print("SKIP %-24s (cannot resolve here: %s)" % (target, exc))
            continue
        for mode in MODES:
            t0 = time.time()
            try:
                c = AhuraClient("127.0.0.1", port, token=TOKEN, stealth_key=STEALTH_KEY,
                                obfs=mode).open()
                handshake_ms = (time.time() - t0) * 1000
                t1 = time.time()
                c.connect_ip(ip, int(port_s))
                connect_ms = (time.time() - t1) * 1000
                request = ("GET / HTTP/1.0\r\nHost: %s\r\nUser-Agent: ahura-live-probe/1.0\r\n"
                           "Connection: close\r\nAccept: */*\r\n\r\n" % host).encode()
                t2 = time.time()
                c.stream.send(request)
                reply = bytearray()
                while len(reply) < 4096:
                    chunk = c.stream.recv_some(4096, timeout=6)
                    if not chunk:
                        break
                    reply.extend(chunk)
                    if b"\r\n\r\n" in reply:
                        break
                rtt_ms = (time.time() - t2) * 1000
                c.close()
                first_line = bytes(reply).split(b"\r\n")[0].decode("latin-1", "replace")
                ok = first_line.startswith("HTTP/")
                print("%s %-6s %-24s handshake=%.0fms connect=%.0fms first-byte=%.0fms reply=%dB  %s" % (
                    "PASS" if ok else "FAIL", mode, target, handshake_ms, connect_ms, rtt_ms,
                    len(reply), first_line[:40]))
                if not ok:
                    failures += 1
            except (AhuraError, OSError) as exc:
                failures += 1
                print("FAIL %-6s %-24s %s" % (mode, target, exc))

    stats = relay.STATS.snapshot()
    print("\ntotals: sessions=%d up=%dB down=%dB errors=%s" % (
        stats["total_sessions"], stats["up_bytes"], stats["down_bytes"], stats["errors"]))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
