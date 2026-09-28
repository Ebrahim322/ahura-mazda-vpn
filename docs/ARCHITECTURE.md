# Architecture

## Data path

```
                       ┌──────────────────────── Android device ────────────────────────┐
   app socket ──► kernel IP stack ──► (routes installed by the app) ──► TUN device
                                                                            │
                                                        Tunnel.readLoop (one thread)
                                                                            │
                        ┌────────────────────────── Packet.parse ──────────┴──────────┐
                        ▼                                                             ▼
                ruleSet.actionFor(dst)                                       ruleSet.actionFor(dst)
                        │                                                             │
        PROXY ──────────┴──► TcpStack.onPacket ──► TcpFlow (per flow, 2 threads)      │
        BLOCK ──────────────► RST back to the app                    │              │
        DIRECT ─────────────► RST + warning (should not be routed)   │              │
                                                                     ▼              ▼
                                            ObfsStream + Socks5Client (AHURA/1)  UdpRelay
                                                                     │              │
                                                                     └──────┬───────┘
                                                                            ▼
                                                          relay VPS: SOCKS5 + dashboard
```

## Components (Android)

| File | Responsibility |
|---|---|
| `core/Packet.kt` | IPv4/IPv6 + TCP/UDP parse and build, checksums, MSS option parsing, sequence arithmetic. |
| `core/Obfs.kt` | The AHURA/1 client: handshake, HMAC-SHA256 counter-mode cipher, record framing, plus a pass-through mode for bare SOCKS5. |
| `core/Socks5.kt` | RFC 1928/1929 client: greeting, user/pass, CONNECT (IP literals only), UDP ASSOCIATE, UDP header pack/parse. |
| `core/Rules.kt` | `Prefix`/`IpRule`/`RuleSet`, private-range detection, prefix complement (range → CIDR), route computation. |
| `core/TcpStack.kt` | Userspace TCP endpoints: SYN/SYN-ACK, in-order data, windows, retransmission, FIN/RST, idle timeouts. |
| `core/UdpRelay.kt` | One SOCKS5 UDP association per flow, with a pre-connect queue. |
| `core/Tunnel.kt` | The engine: TUN read loop, per-packet routing decision, stats, connector implementations. |
| `core/Prefs.kt` | Settings + relay profiles (`ahura://` links). |
| `core/Stats.kt`, `core/Log.kt` | Counters and the in-app log ring the UI reads. |
| `service/AhuraVpnService.kt` | `VpnService`: builds the TUN, installs routes (and `excludeRoute` on Android 13+), per-app rules, foreground notification. |
| `MainActivity.kt` | The UI. |

## Why the routing rules decide the *routes*

A `VpnService` can only see what the kernel routes into its TUN device.  So the
rule set does double duty:

1. `RuleSet.tunnelRoutes()` computes the CIDR list handed to
   `VpnService.Builder.addRoute()`.  Anything not in that list keeps using the
   device's normal connection — that is how *direct* rules work without any
   per-packet magic.
2. Inside the tunnel, `RuleSet.actionFor(dst)` decides `PROXY` (carry it),
   `BLOCK` (RST it) or `DIRECT` (should not be here; refuse it loudly rather
   than leak it silently in the clear).

For a full tunnel with "bypass local networks" the interesting case appears:
`0.0.0.0/0` is routed, but private ranges must stay outside.

* Android 13+ → `Builder.excludeRoute(prefix)`, exact.
* Older Android → the app computes the **complement** of the bypassed prefixes
  (`NetworkMath.complement`, a standard range→CIDR decomposition) and routes
  that instead, so e.g. `0.0.0.0/0` minus the private blocks becomes a handful
  of public prefixes and the private ones are never captured at all.

## TCP endpoint design

The device is always the active opener, so each `TcpFlow` is a passive opener:

1. SYN arrives → `TcpStack` allocates the flow and connects the relay
   (SOCKS5 CONNECT to the literal destination IP).
2. On success → SYN-ACK with our ISN and our MSS; upstream data may start
   flowing immediately.
3. Steady state: a **reader** thread (relay → queue → device) and a **pump**
   thread (device queue → relay, retransmission timer, FIN/close logic).  The
   TUN reader thread never blocks on network I/O; segments are only queued.
4. `FIN` in either direction half-closes that side; the flow is torn down when
   both are closed and everything has drained.  RST tears down both sides.

The pump implements:

* in-flight cap (`MAX_IN_FLIGHT`) and the peer's advertised window, whichever is
  smaller,
* retransmission of the oldest unacknowledged segment with exponential back-off
  (1 s → 8 s) and a give-up limit of 8 tries,
* zero-window probing (re-send the last byte) so a peer that closes its window
  cannot wedge the flow forever,
* an idle timeout (180 s) so abandoned flows do not accumulate.

Out-of-order segments are not buffered: the expected sequence is re-ACKed and
the device retransmits.  On a TUN device that is a local loopback-quality path,
so the cost is negligible; the code stays much smaller than a full reassembly
queue would need.

## Reliability work that came out of testing

`server/tools/self_test.py` exercises the relay with the reference client.  Two
real bugs it caught, both worth remembering when reading the relay code:

1. **Blocking byte pump dead-lock.**  A loop that reads from A and writes to B
   (and vice versa) wedges permanently as soon as both peers stop reading at the
   same moment: A's buffer fills, B's buffer fills, nobody drains.  The relay
   now drives both sockets with one selector loop and an explicit pending-write
   buffer per direction, so back-pressure stops reading from the fast side
   instead of dead-locking.
2. **Decoded-but-unsent bytes.**  Bytes already decrypted (or already queued)
   must be flushed on every wake-up, not only when fresh socket data arrives —
   otherwise the tail of a transfer waits for an event that will never come.
   The pump has an explicit `drain()` step for exactly this.

## Testing strategy

| Layer | How it is verified |
|---|---|
| Wire protocol (AHURA/1, SOCKS5, UDP headers) | `server/tools/self_test.py` with a second, independent implementation (Python client) — including a 300 KB cipher round-trip and a byte-level check that no SOCKS5/HTTP signature is visible on the wire. |
| Relay behaviour against the internet | `server/tools/live_probe.py` performs real HTTP requests through the tunnel. |
| Relay dashboard | JSON + HTML assertions in the self-test. |
| Android client | Build + install (no emulator in this environment).  The protocol-critical parts are the parts the Python tests pin down; the UDP header pack/parse, prefix math and packet builders are small, dependency-free functions that mirror the tested Python code. |
