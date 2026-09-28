# The AHURA/1 wire protocol

Two implementations exist and must agree byte for byte:

* `server/ahura_relay.py` (server) and `server/tools/ahura_client.py`
  (reference client) — verified by `server/tools/self_test.py`,
* `android/…/core/Obfs.kt` + `core/Socks5.kt` (the Android client).

Both were written against this document; when you change one, change the others
and run the tests.  Version 1.1.0 added the framing `mode` field and the
TLS-lookalike framing; 1.0.0 handshakes (three fields) are still accepted and
mean `mode = ahura/1`.

```
        client                                    relay
          │  TCP connect to relay:1080             │
          │───────────────────────────────────────►│
          │  "AHURA/1 <nonce_hex> <token> <mode>\n"│  (plain ASCII, ≤256 B)
          │  — or the same line inside a TLS record │
          │───────────────────────────────────────►│
          │                                        │  verify token + mode, derive keys
          │  "AHURA/1 OK <mode>\n"   (or ERR …)   │
          │◄───────────────────────────────────────│
          │                                        │
          │  ⟨every byte from here on is framed and encrypted⟩
          │   ahura/1 mode:  2-byte BE length + ciphertext
          │   tls mode:      17 03 03 |len| <len16> ciphertext <random padding>
          │◄──────────────────────────────────────►│
          │  … SOCKS5 greeting / CONNECT / UDP ASSOCIATE inside the cipher …
```

## 1. Handshake

Client, immediately after the TCP connection is established, sends one line:

```
AHURA/1 <nonce_hex> <token> <mode>\n          (mode ∈ {ahura/1, tls})
AHURA/1 <nonce_hex> <token>\n                 (v1.0.0 form → mode = ahura/1)
```

* `nonce_hex` — 16 random bytes, lowercase hex (32 characters), **fresh per
  connection**.  Reusing a nonce would reuse keystream; do not.
* `token` — the shared token configured on the relay (`--token`).  When the
  relay has no tokens configured, any value is accepted (an open relay).
* `mode` — the framing used from this point on.  `ahura/1` is the compact
  framing; `tls` means every record travels inside a TLS-shaped frame, and the
  greeting itself must arrive inside one (see §3.1).  A client asking for a
  framing it is not actually speaking is rejected — the streams would drift
  apart, and that must never be papered over.

The relay answers, still in clear text (or inside a TLS frame, in `tls` mode):

```
AHURA/1 OK <mode>\n            → proceed with the encrypted stream
AHURA/1 ERR <reason>\n         → close; reason ∈ {bad-handshake, bad-mode,
                                 mode-not-allowed, bad-nonce, unauthorized}
```

The `OK` line is intentionally *outside* the cipher: it is the switch point of
the protocol, carries no secret, and lets a client distinguish "wrong key or
token" from "the server is speaking something else entirely".

## 2. Key derivation

```
stealth_key : the shared secret (32 hex chars are decoded to 16 raw bytes,
              anything else is used as UTF-8 text)

master  = HMAC-SHA256(stealth_key, "ahura/v1" ‖ nonce )
key_c2s = HMAC-SHA256(master,      "c2s")
key_s2c = HMAC-SHA256(master,      "s2c")
```

## 3. Record framing and cipher

Every byte after the handshake, in **both** directions, travels in records:

```
<uint16 big-endian length> <length bytes of ciphertext>
```

* `length` is 1..8192 (plaintext bytes per record on the client side).
* ciphertext = plaintext XOR keystream, where keystream block *i* is
  `HMAC-SHA256(key_dir, uint64_be(i))` (32 bytes per block; the counter
  advances for every 32 bytes consumed, and is **not** reset per record).

Both directions keep separate keys and separate counters.  Because the keys are
per-connection (fresh nonce) and the counter advances with the byte count, the
two sides stay in sync as long as they process the same plaintext.

### 3.1 `tls` mode — TLS-lookalike framing

Each record from §3 is carried inside one TLS record:

```
17 03 03 <uint16 outer_len>  |  <uint16 inner_len> ciphertext <0..64 random bytes>
```

* The five-byte header is exactly what TLS 1.2 application data looks like.
* The random padding goes *after* the length-prefixed record, so the outer
  length varies on every frame while the receiver still knows where the real
  data ends (padding before the record would be ambiguous).  Records are ≤ 8258
  bytes on the wire.
* The **greeting** is framed the same way but with a ClientHello header and a
  handshake content type: `16 03 01 <len16> | AHURA/1 … <token> tls\n <pad>`.
  The relay's reply uses `16 03 03 … ` (ServerHello shape).  This is why a
  session in `tls` mode opens with bytes a censor sees as TLS 1.2 — no
  `AHURA/1` string in the first packet, no SOCKS5 signature anywhere.
* What it is *not*: a real TLS handshake (no certificates, no key exchange).
  It defeats pattern matching and payload inspection; it does not defeat an
  adversary that actively probes the relay port or fingerprints the exchange
  as a whole.

Server side, `--obfs tls` accepts only that framing, `--obfs ahura/1` only the
compact one, and `--obfs any` (default) accepts both on the same port — the
greeting's first byte (0x16 vs ASCII) tells them apart.

With `--obfs none` (server) and `obfs = none` (app) this whole section is
skipped: no handshake, no framing, no cipher — plain SOCKS5, for interop with
other clients.

## 4. What runs inside the cipher

Ordinary SOCKS5 (RFC 1928), with two specific choices:

* **CONNECT always uses a literal IP address** (`ATYP=0x01` or `0x04`), never a
  domain (`ATYP=0x03`).  The tunnel already knows the destination from the IP
  header, so no name is resolved on the device.  This is the core of the
  IP-based design: the relay never has to trust a device-side resolver, and a
  poisoned resolver cannot influence the destination.
* **UDP ASSOCIATE** is used for datagrams.  Client → relay datagrams carry the
  RFC 1928 UDP header:

```
+----+------+------+----------+----------+----------+
|RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
+----+------+------+----------+----------+----------+
  2B    1B     1B     var        2B        var
```

  `FRAG` must be `0` (fragmentation is not supported by either side).  Replies
  from the relay use the same header with the true source address.

Optional username/password authentication (RFC 1929) can be layered on top —
the relay advertises method `0x02` when `--socks-user` is set.

## 5. Relay policy (what the server refuses)

* private / loopback / link-local / reserved / multicast destinations are
  refused unless `--allow-private`,
* `--allow-cidr` / `--deny-cidr` / `--allow-port` gate the destinations,
* per-IP and total connection caps,
* the policy check happens **after** the client's address is resolved, so a
  client cannot bypass it with a name.
