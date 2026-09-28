# Ahura Relay

A dependency-free (stdlib only) SOCKS5 relay with the `AHURA/1` stealth layer,
target policy and a live web dashboard.  It is the server half of Ahura Mazda
VPN; see `../docs/PROTOCOL.md` for the wire format.

## Run

```bash
KEY=$(python3 -c "import secrets;print(secrets.token_hex(16))")

python3 ahura_relay.py \
  --host 0.0.0.0 --port 1080 \
  --dashboard-host 0.0.0.0 --dashboard-port 8080 \
  --stealth-key "$KEY" \
  --token phone-1 --token "laptop:laptop-token" \
  --log-level info
```

`--token LABEL:TOKEN` gives the connection a readable name in the dashboard and
statistics.  Without `--token` the relay accepts any token (fine for a first
test, not for a public VPS).  `--stealth-key` may also come from the
`AHURA_STEALTH_KEY` environment variable; if you omit it the relay generates one
and prints it (it will change on restart, so keep it).

Endpoints:

| Path | What |
|---|---|
| `/` | dashboard (Persian/RTL, auto-refreshing: traffic graph, sessions, per-token accounting, errors, log tail) |
| `/api/stats` | the same data as JSON |
| `/healthz` | `ok` |

## Options

```
--obfs {ahura/1,none}      stealth layer, or plain SOCKS5 for other clients
--socks-user/--socks-pass  require RFC 1929 username/password too
--allow-private            allow private/loopback/reserved targets (default: refused)
--allow-cidr 1.1.1.0/24    restrict relayable destinations (repeatable)
--deny-cidr  10.0.0.0/8
--allow-port 443           restrict ports (repeatable)
--max-connections 2048     total cap
--max-per-ip 64            per source-IP cap
--idle-timeout 600         seconds without traffic before a session is dropped
--config relay.json        JSON file for any of the above
--log-level debug          also logs every pump wake-up state (troubleshooting)
```

## Production

```bash
sudo cp ahura-relay.service /etc/systemd/system/
sudo systemctl edit ahura-relay      # set the stealth key + tokens
sudo systemctl enable --now ahura-relay
sudo systemctl status ahura-relay
journalctl -u ahura-relay -f
```

Also consider: a firewall rule for `1080` (TCP), `8080` only from your own IP,
and a provider that tolerates this kind of traffic.  The relay is a plain
TCP/UDP forwarder with a policy layer — it does not log payloads, and the log
ring keeps only the last 200 lines in memory.

## Tests

```bash
python3 tools/self_test.py     # 17 checks, ~15 s, no internet needed
python3 tools/live_probe.py    # real HTTP through the tunnel (needs internet)
python3 tools/ahura_client.py --host 127.0.0.1 --port 1080 --key "$KEY" \
    --token phone-1 --target 1.1.1.1:443 --probe
```
