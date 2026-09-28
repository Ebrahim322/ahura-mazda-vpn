#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Ahura Relay — one-line installer
#
#   curl -fsSL https://raw.githubusercontent.com/Ebrahim322/ahura-mazda-vpn/main/server/install.sh | sudo bash
#
# What it does, in order:
#   1. checks root, python3, systemd
#   2. creates an unprivileged system user for the relay
#   3. downloads server/ahura_relay.py (stdlib only — no pip, no venv)
#   4. writes /etc/ahura-relay/config.json with a fresh stealth key + token
#   5. installs systemd/ahura-relay.service and starts it
#   6. opens the relay port in ufw / firewalld / nftables (unless --no-firewall)
#   7. prints a ready-to-import ahura:// link for the Android app
#
# Options (all optional):
#   --port 1080            relay port (default 1080)
#   --dashboard-port 8080  local dashboard port (default 8080, bound to 127.0.0.1)
#   --token phone-1        device token (default: generated)
#   --stealth-key HEX      shared secret (default: generated)
#   --obfs any|tls|ahura/1|none   framing accepted (default any)
#   --public-host IP       host that goes into the import link (default: autodetect)
#   --no-firewall          do not touch firewall rules
#   --uninstall            remove service, files and firewall rule
#   --branch NAME          git branch to download from (default: main)
# ---------------------------------------------------------------------------
set -euo pipefail

REPO="${AHURA_REPO:-https://raw.githubusercontent.com/Ebrahim322/ahura-mazda-vpn}"
BRANCH="${AHURA_BRANCH:-main}"
PREFIX="/opt/ahura-relay"
CONF_DIR="/etc/ahura-relay"
CONF="$CONF_DIR/config.json"
UNIT="/etc/systemd/system/ahura-relay.service"
SERVICE="ahura-relay"
RUN_USER="ahura"
PORT="1080"
DASH_PORT="8080"
TOKEN=""
STEALTH_KEY=""
OBFS="any"
PUBLIC_HOST=""
FIREWALL=1
UNINSTALL=0

log()  { printf '\033[1;36m[ahura]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[ahura]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[ahura]\033[0m %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --port)            PORT="${2:?}"; shift 2 ;;
        --dashboard-port)  DASH_PORT="${2:?}"; shift 2 ;;
        --token)           TOKEN="${2:?}"; shift 2 ;;
        --stealth-key)     STEALTH_KEY="${2:?}"; shift 2 ;;
        --obfs)            OBFS="${2:?}"; shift 2 ;;
        --public-host)     PUBLIC_HOST="${2:?}"; shift 2 ;;
        --branch)          BRANCH="${2:?}"; shift 2 ;;
        --no-firewall)     FIREWALL=0; shift ;;
        --uninstall)       UNINSTALL=1; shift ;;
        -h|--help)         sed -n '2,30p' "$0"; exit 0 ;;
        *)                 die "unknown option: $1" ;;
    esac
done

[ "$(id -u)" = "0" ] || die "run as root (prefix with sudo)"

# --- helpers ---------------------------------------------------------------
rand_hex() {  # rand_hex BYTES
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex "$1"
    else
        python3 -c "import secrets,sys; print(secrets.token_hex(int(sys.argv[1])))" "$1"
    fi
}

fetch() {  # fetch REMOTE_PATH DEST
    local url="$REPO/$BRANCH/$1"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$url" -o "$2"
    elif command -v wget >/dev/null 2>&1; then
        wget -qO "$2" "$url"
    else
        die "neither curl nor wget is installed"
    fi
}

open_port() {
    local port="$1"
    [ "$FIREWALL" = "1" ] || { log "firewall untouched (--no-firewall)"; return 0; }
    if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
        ufw allow "$port"/tcp >/dev/null && log "ufw: opened tcp/$port"
    elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
        firewall-cmd --permanent --add-port="$port"/tcp >/dev/null && firewall-cmd --reload >/dev/null \
            && log "firewalld: opened tcp/$port"
    elif command -v nft >/dev/null 2>&1 && nft list ruleset >/dev/null 2>&1; then
        nft add rule inet filter input tcp dport "$port" accept 2>/dev/null \
            && log "nftables: opened tcp/$port" || warn "nftables rule could not be added — open tcp/$port yourself"
    else
        warn "no active ufw/firewalld/nftables found — if your VPS has a cloud firewall, open tcp/$port there"
    fi
}

close_port() {
    local port="$1"
    [ "$FIREWALL" = "1" ] || return 0
    if command -v ufw >/dev/null 2>&1; then ufw delete allow "$port"/tcp >/dev/null 2>&1 || true; fi
    if command -v firewall-cmd >/dev/null 2>&1; then
        firewall-cmd --permanent --remove-port="$port"/tcp >/dev/null 2>&1 && firewall-cmd --reload >/dev/null 2>&1 || true
    fi
}

# --- uninstall -------------------------------------------------------------
if [ "$UNINSTALL" = "1" ]; then
    log "removing the relay"
    if [ -f "$CONF" ]; then
        PORT="$(python3 -c "import json;print(json.load(open('$CONF')).get('port',1080))" 2>/dev/null || echo 1080)"
    fi
    systemctl disable --now "$SERVICE" >/dev/null 2>&1 || true
    rm -f "$UNIT"; systemctl daemon-reload >/dev/null 2>&1 || true
    rm -rf "$PREFIX" "$CONF_DIR"
    id -u "$RUN_USER" >/dev/null 2>&1 && userdel "$RUN_USER" 2>/dev/null || true
    close_port "$PORT"
    log "removed. (firewall rule for tcp/$PORT closed if it was ours)"
    exit 0
fi

# --- preconditions ---------------------------------------------------------
command -v python3 >/dev/null 2>&1 || {
    log "installing python3"
    if command -v apt-get >/dev/null 2>&1; then apt-get update -qq && apt-get install -y -qq python3
    elif command -v dnf >/dev/null 2>&1; then dnf install -y python3
    elif command -v yum >/dev/null 2>&1; then yum install -y python3
    elif command -v apk >/dev/null 2>&1; then apk add --no-cache python3
    else die "python3 is missing and I do not know this package manager"
    fi
}
python3 -c "import sys; sys.exit(0 if sys.version_info >= (3, 7) else 1)" \
    || die "python3 >= 3.7 required (found $(python3 -V 2>&1))"

HAVE_SYSTEMD=0
if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then HAVE_SYSTEMD=1; fi

[ -n "$STEALTH_KEY" ] || STEALTH_KEY="$(rand_hex 16)"
[ -n "$TOKEN" ] || TOKEN="phone-$(rand_hex 2)"
case "$OBFS" in any|tls|ahura/1|none) ;; *) die "--obfs must be any, tls, ahura/1 or none" ;; esac

log "installing the relay into $PREFIX"
install -d -m 0755 "$PREFIX" "$CONF_DIR"
fetch "server/ahura_relay.py" "$PREFIX/ahura_relay.py"
chmod 0644 "$PREFIX/ahura_relay.py"
python3 -m py_compile "$PREFIX/ahura_relay.py" || die "the downloaded relay did not compile — wrong branch?"

if ! id -u "$RUN_USER" >/dev/null 2>&1; then
    useradd --system --home-dir "$PREFIX" --shell /usr/sbin/nologin "$RUN_USER" 2>/dev/null \
        || useradd -r -d "$PREFIX" -s /sbin/nologin "$RUN_USER"
fi

umask 077
cat > "$CONF" <<JSON
{
  "host": "0.0.0.0",
  "port": $PORT,
  "dashboard_host": "127.0.0.1",
  "dashboard_port": $DASH_PORT,
  "obfs": "$OBFS",
  "stealth_key": "$STEALTH_KEY",
  "tokens": ["$TOKEN"],
  "public_host": "$PUBLIC_HOST",
  "allow_private": false,
  "max_connections": 4096,
  "max_per_ip": 96,
  "idle_timeout": 600,
  "log_level": "info"
}
JSON
chown -R "$RUN_USER":"$RUN_USER" "$PREFIX" "$CONF_DIR" 2>/dev/null || true
chmod 0600 "$CONF"

if [ "$HAVE_SYSTEMD" = "1" ]; then
    cat > "$UNIT" <<UNITEOF
[Unit]
Description=Ahura Relay (SOCKS5 + AHURA/1 obfuscation + dashboard)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$RUN_USER
ExecStart=/usr/bin/env python3 $PREFIX/ahura_relay.py --config $CONF
Restart=always
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ProtectHome=true
AmbientCapabilities=CAP_NET_BIND_SERVICE

[Install]
WantedBy=multi-user.target
UNITEOF
    systemctl daemon-reload
    systemctl enable --now "$SERVICE" >/dev/null
    log "service $SERVICE started"
else
    warn "systemd is not running here (container?) — starting the relay in the background"
    mkdir -p /var/log/ahura-relay
    nohup python3 "$PREFIX/ahura_relay.py" --config "$CONF" >/var/log/ahura-relay/relay.log 2>&1 &
    log "started with nohup (log: /var/log/ahura-relay/relay.log)"
fi

open_port "$PORT"

# --- verify ----------------------------------------------------------------
ok=0
for _ in $(seq 1 25); do
    sleep 0.4
    if python3 - "$DASH_PORT" <<'PY' >/dev/null 2>&1
import sys, urllib.request
urllib.request.urlopen("http://127.0.0.1:%s/healthz" % sys.argv[1], timeout=2).read()
PY
    then ok=1; break; fi
done
[ "$ok" = "1" ] && log "dashboard answers on 127.0.0.1:$DASH_PORT" \
               || warn "dashboard did not answer yet — check: journalctl -u $SERVICE -n 50"

HOST="$PUBLIC_HOST"
if [ -z "$HOST" ]; then
    HOST="$(python3 - <<'PY'
import socket
try:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(("1.1.1.1", 53))
    print(s.getsockname()[0]); s.close()
except OSError:
    print("")
PY
)"
fi
[ -n "$HOST" ] || HOST="YOUR_SERVER_IP"

cat <<TXT

────────────────────────────────────────────────────────────
 آهورا مزدا — رله روی این سرور آماده است / relay is ready
────────────────────────────────────────────────────────────
  Listen         : 0.0.0.0:$PORT  (obfs=$OBFS)
  Dashboard      : http://127.0.0.1:$DASH_PORT  (only local)
  Config         : $CONF
  Logs           : journalctl -u $SERVICE -f
  Restart        : systemctl restart $SERVICE

  Stealth key    : $STEALTH_KEY
  Device token   : $TOKEN

  Import link (send it to yourself, then tap it on the phone):

    ahura://relay@$HOST:$PORT?key=$STEALTH_KEY&token=$TOKEN&obfs=$OBFS&name=vps

  In the app: Servers → Import (paste the link) → Connect.
  Test from a PC:
    python3 ahura_relay.py is on the server; the reference client is at
    server/tools/ahura_client.py --host $HOST --port $PORT --key $STEALTH_KEY \\
        --token $TOKEN --obfs $OBFS --target 1.1.1.1:80 --probe
────────────────────────────────────────────────────────────
TXT
