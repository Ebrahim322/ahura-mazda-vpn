# Ahura Mazda VPN

**An IP-based, self-hosted filter breaker for Android** — a real `VpnService`
tunnel that carries TCP and UDP to a relay server you own, with a stealth layer
(`AHURA/1`) so the traffic does not look like SOCKS5 on the wire.

```
 Android app  ──[ TUN: raw IP packets ]──►  TCP stack / UDP relay
      ▲                                          │
      │                                          ▼
   routes by IP / CIDR                    SOCKS5 + AHURA/1 (one TCP connection per flow)
      │                                          │
      └──────────────────────────────────────────►  your relay (server/ahura_relay.py)
                                                    │
                                                    ▼  the open internet
```

No root, no third-party VPN SDK, no domain-based anything: **every routing
decision is made on the destination IP**, and the device never resolves a name
for the traffic it tunnels.  That is deliberate: poisoned DNS, a transparent
proxy or a domain blocklist cannot break or unveil the tunnel, because the app
never asks a local resolver which IP belongs to which name.

---

## What is in it

| Feature | Where |
|---|---|
| **TLS-lookalike obfuscation** — every record is wrapped in a `17 03 03 …` TLS frame with random padding, and the first bytes of a session are a textbook `16 03 01 …` ClientHello. The compact `ahura/1` framing and raw SOCKS5 are still there for interop. | `core/Obfs.kt`, `server/ahura_relay.py` (`--obfs any\|tls\|ahura/1\|none`) |
| **Multiple relays + auto-select + failover** — every relay in the list is probed for real (handshake + SOCKS5 greeting); the app connects to the fastest one, re-tests every two minutes and moves to the next relay if the active one dies. | `core/Probe.kt`, `service/AhuraVpnService.kt`, the *سرورها* tab |
| **One-line server install** — `curl … \| sudo bash` sets up the user, key, token, systemd unit and firewall, then prints a ready-to-tap `ahura://` link. | `server/install.sh` |
| **Installable APK** — GitHub Actions builds it on every push; tagged releases attach the APK. | `.github/workflows/build-apk.yml` |
| Everything else that was already here: IP/CIDR routing rules, per-app rules, UDP, IPv4+IPv6, Persian UI, live dashboard. | — |

---

## Why it is built this way (the short version)

| Problem on filtered networks | What Ahura does about it |
|---|---|
| DNS poisoning / DNS blocking | The device resolves nothing for tunneled flows; the relay connects to the literal IP taken from the packet header. DNS itself is carried as plain UDP **through** the tunnel to the resolver you choose. |
| Fingerprinting of proxy protocols | `AHURA/1`: a token handshake + HMAC-SHA256 counter-mode stream cipher with 8 KiB records. No `05 01 00 …` SOCKS5 signature, no readable payload. |
| Per-app or per-range leaks | Routing rules are CIDRs: `proxy`, `direct` (bypass) and `block`. On Android 13+ bypassed ranges are excluded exactly (`excludeRoute`); older versions get the *complement* routes, so bypassed networks never enter the tunnel. |
| Kill switch / leaks when the tunnel dies | Traffic that is routed into the tunnel has nowhere else to go: if the relay is gone, flows are refused instead of leaking in the clear. |
| UDP being blocked or ignored | UDP goes through SOCKS5 `UDP ASSOCIATE` on the same relay connection, one association per flow. |

Honest limitations are listed at the bottom — this is a working tool, not magic.

---

## Repository layout

```
android/                 Android app (Kotlin, framework-only, one dependency)
  app/src/main/java/com/ahuramazda/vpn/
    core/                Packet, Obfs (AHURA/1), Socks5, TcpStack, UdpRelay, Tunnel, Rules, Prefs
    service/             AhuraVpnService (VpnService), quick-settings tile, boot receiver
    MainActivity.kt      the whole UI (framework widgets, Persian by default, English included)
server/
  ahura_relay.py         the relay: SOCKS5 + AHURA/1 (compact + TLS-lookalike) + policy + dashboard
  install.sh             one-line installer: user, key, token, systemd unit, firewall, import link
  ahura-relay.service    the unit the installer writes (kept here for reference)
  tools/ahura_client.py  reference client (the protocol's second implementation)
  tools/self_test.py     24 end-to-end protocol/relay tests
  tools/live_probe.py    drives the relay against the real internet
docs/PROTOCOL.md         the wire format, byte for byte
docs/ARCHITECTURE.md     how the client is put together and why
.github/workflows/       builds the APK in the cloud (artifact + release attachment)
```

---

## 1. Server: put the relay on a VPS

**The one-liner** (Debian/Ubuntu/Fedora/Alpine with systemd; root required):

```bash
curl -fsSL https://raw.githubusercontent.com/Ebrahim322/ahura-mazda-vpn/main/server/install.sh | sudo bash
```

It creates an unprivileged `ahura` user, downloads the relay, generates the
stealth key and a token, writes `/etc/ahura-relay/config.json`, installs and
starts `ahura-relay.service`, opens the relay port in ufw/firewalld/nftables,
and prints:

```
    ahura://relay@203.0.113.9:1080?key=<hex>&token=phone-ab12&obfs=any&name=vps
```

Send that link to yourself in any messenger, tap it on the phone (or paste it
with *درون‌ریزی* in the Servers tab) — the app has everything it needs.
Handy flags: `--port`, `--token`, `--stealth-key`, `--obfs any|tls|ahura/1|none`,
`--public-host`, `--dashboard-port`, `--no-firewall`, `--uninstall`, `--branch`.

**Manually**, if you prefer to see every step — requirements are `python3` (3.8+)
and nothing else:

```bash
# on the VPS
git clone <this repo> && cd ahura-mazda-vpn

# generate the shared stealth key (32 hex chars) the app will use too
KEY=$(python3 -c "import secrets;print(secrets.token_hex(16))")
echo "stealth key: $KEY"

sudo python3 server/ahura_relay.py \
  --host 0.0.0.0 --port 1080 \
  --dashboard-port 8080 \
  --stealth-key "$KEY" \
  --token phone-1 --token laptop-2
```

Then open `http://VPS-IP:8080/` for the live dashboard (Persian, RTL): traffic
graph, active sessions, per-token accounting, destination ranking, error
counters and the server log.

Handy options:

```bash
python3 server/ahura_relay.py --help
--obfs any|tls|ahura/1|none  # "any" (default) accepts both encrypted framings,
                             # "tls" = TLS-lookalike only, "none" = bare SOCKS5
--public-host 203.0.113.9    # host used in the printed ahura:// import link
--allow-cidr 1.1.1.0/24    # only relay to these networks
--deny-cidr 10.0.0.0/8
--allow-port 443 --allow-port 80
--allow-private            # permit LAN targets (off by default)
--max-connections 2048 --max-per-ip 64
--config relay.json        # everything above can come from a file
```

A systemd unit and an example config are in `server/`.

### Verify the relay (no phone needed)

```bash
python3 server/tools/self_test.py      # 24 checks: handshake, TLS framing, auth, TCP, UDP, policy, dashboard
python3 server/tools/live_probe.py     # real HTTP through the tunnel (needs internet)
python3 server/tools/ahura_client.py --host 127.0.0.1 --port 1080 \
    --key "$KEY" --token phone-1 --target 1.1.1.1:443 --probe
```

---

## 2. Android app: install or build

**Easiest path — grab the APK that CI built:**

```
https://github.com/Ebrahim322/ahura-mazda-vpn/releases/latest
```

(or *Actions → Build APK → latest run → artifact `ahura-mazda-debug-apk`).
It is signed with the debug key, so Android will ask you to allow installation
from an unknown source; that is normal for sideloaded, self-hosted software.

**Build it yourself** — Android Studio, or Gradle 8.9 + JDK 17:

```bash
cd android
gradle wrapper --gradle-version 8.9   # once, if you do not have the wrapper jar
./gradlew assembleDebug               # or just open the folder in Android Studio
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`minSdk 24`, `targetSdk 34`, Kotlin + framework APIs only (no AndroidX, no
Compose, no Play services) — the APK is small and the build needs no exotic
dependency resolution.

In the app:

1. **سرورها (Servers) → درون‌ریزی** pastes the `ahura://…` link the installer
   printed (or → **افزودن** to type host, port, stealth key and token by hand;
   the obfuscation spinner offers **TLS-lookalike** (default, recommended),
   compact `ahura/1`, or raw SOCKS5).  The same tab has the install command in
   one tap (**دستور نصب سرور**) and **تست همه سرورها**, which probes every relay
   and shows its handshake time in the list.  With *انتخاب خودکار سریع‌ترین
   سرور* ticked, the app always connects to the fastest relay that answered and
   switches to the next one if it stops answering (checked every 2 minutes).
2. **قواعد آی‌پی (IP rules)**: pick *all traffic* or *only the listed IPs*;
   the rule editor takes one CIDR per line:
   `8.8.8.8/32` (proxy), `!192.168.0.0/16` (bypass), `block 5.5.5.5/32` (drop).
   The "test a routing decision" box shows immediately what the engine would do
   with any address you type.
3. Tap **اتصال**.  The quick-settings tile and the boot receiver can also bring
   the tunnel up.

Server links are shareable: `ahura://phone-1@1.2.3.4:1080?key=<hex>&obfs=tls`
(copy/import buttons in the Servers tab; tapping the link in a messenger opens
the app and adds the relay).

---

## 3. What is in the app

* **Full tunnel or IP list** routing, IPv4 + IPv6, MTU/DNS/UDP toggles.
* **Per-app rules**: all apps, only selected apps, or everything except them.
* **Bypass local networks** without leaking them (exact excluding on Android
  13+, complement-routes below that).
* **Live statistics**: upload/download, active TCP/UDP flows, total
  connections, errors, uptime.
* **Multi-relay with auto-select and failover**: probes every relay (handshake
  + SOCKS5 greeting, never a target connection), ranks them by handshake time,
  and re-tests the active one every two minutes while connected.
* **Health check**: performs the real AHURA/1 handshake against the relay and
  reports the latency; the result becomes that relay's latency entry.
* **Log view** with copy/clear, export/import of the whole configuration.
* Persian UI by default, English included.

---

## 4. Tests

The Android client cannot run on a CI machine, so the *protocol* is pinned down
by the Python side, which is a second, independent implementation of the same
wire format:

```bash
$ python3 server/tools/self_test.py
PASS obfs handshake + SOCKS5 CONNECT + HTTP response
PASS bad token rejected
PASS 300 KB round-trip through cipher (integrity)
PASS bare SOCKS5 mode + private target refused by policy
PASS stealth token + RFC1929 user/pass on one connection
PASS UDP payload relayed both ways
PASS TCP mode 'tls': handshake + CONNECT + HTTP works
PASS TCP mode 'tls': first bytes are a TLS ClientHello header
PASS TCP mode 'tls': payload frames use the TLS app-data header
PASS TCP mode 'tls': padding randomises every frame size
PASS TCP mode 'tls': codec round-trips 11 KB across record boundaries
PASS TCP mode 'tls': compact client refused by a tls-only server
PASS v1.0.0 clients (three-field handshake) still accepted
PASS DPI-visible bytes contain handshake but no SOCKS5/HTTP signature
PASS dashboard JSON stats (per-user accounting)
PASS CIDR allow-list enforced
PASS `python3 ahura_relay.py` CLI end-to-end
24/24 checks passed
```

The 300 KB round-trip test is not decoration: it is what caught a real
dead-lock in the relay's byte pump (a blocking read/send loop wedges as soon as
both peers stop reading) and a second bug where already-decoded bytes waited
for a socket event that never came.

---

## 5. Limitations, stated plainly

* `AHURA/1` is **obfuscation with a shared secret**, not a hardened
  anti-censorship protocol.  The `tls` mode makes the *shape* of the traffic
  look like TLS 1.2 records (ClientHello header, `17 03 03` app-data frames,
  randomised lengths) and defeats naive pattern matching, but it is not a real
  TLS handshake: a censor that actively probes the port, or that fingerprints
  the certificate-less, self-signed-less exchange, could still tell.  Keep
  HTTPS inside the tunnel (everything does anyway).
* Auto-select/failover costs one handshake per relay when the tunnel starts and
  every two minutes while it runs; the probes are protected sockets and never
  touch the tunnel, but they are visible as short-lived connections to your
  relay — from your own IP, which is expected for a self-hosted relay.
* Switching relays mid-session resets live connections (the TUN is rebuilt).
  Auto-select does it only when the active relay stopped answering.
* The TCP stack does not buffer out-of-order segments: it re-ACKs the expected
  sequence and lets the device retransmit.  On the TUN loopback path this is
  rare, and it costs efficiency, never correctness.
* IPv4 fragments and non-trivial IPv6 extension headers are dropped.
* `excludeRoute` (exact bypass) needs Android 13+; older devices use computed
  complement routes, which is equivalent for the usual "bypass LAN" case.
* UDP flows are capped (64 by default) and idle-time out; DNS-heavy usage is
  fine, torrent-like UDP is not the target.
* The app does not and cannot hide *that* a VPN is active — Android shows the
  VPN key in the status bar.

## License

No license is declared yet in this repository — add one before redistributing.
