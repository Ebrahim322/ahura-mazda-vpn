# The AHURA/1 wire protocol

Two implementations exist and must agree byte for byte:

* `server/ahura_relay.py` (server) and `server/tools/ahura_client.py`
  (reference client) — verified by `server/tools/self_test.py`,
* `android/…/core/Obfs.kt` + `core/Socks5.kt` (the Android client).

Both were written against this document; when you change one, change the others
and run the tests.

```
        client                                    relay
          │  TCP connect to relay:1080             │
          │───────────────────────────────────────►│
          │  "AHURA/1 <nonce_hex> <token>\n"       │   (plain ASCII, ≤256 B)
          │───────────────────────────────────────►│
          │                                        │  verify token, derive keys
          │  "AHURA/1 OK\n"       (or ERR …)       │
          │◄───────────────────────────────────────│
          │                                        │
          │  ⟨every byte from here on is framed and encrypted⟩
          │   2-byte BE length + ciphertext        │
          │◄──────────────────────────────────────►│
          │  … SOCKS5 greeting / CONNECT / UDP ASSOCIATE inside the cipher …
```

## 1. Handshake

Client, immediately after the TCP connection is established, sends one line:

```
AHURA/1 <nonce_hex> <token>\n
```

* `nonce_hex` — 16 random bytes, lowercase hex (32 characters), **fresh per
  connection**.  Reusing a nonce would reuse keystream; do not.
* `token` — the shared token configured on the relay (`--token`).  When the
  relay has no tokens configured, any value is accepted (an open relay).

The relay answers, still in clear text:

```
AHURA/1 OK\n                    → proceed with the encrypted stream
AHURA/1 ERR <reason>\n          → close; reason ∈ {bad-handshake, bad-nonce, unauthorized}
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
