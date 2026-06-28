#!/bin/bash
# discover-aroma.sh — find AROMA file-server instances on the local network
#
# Usage:
#   ./discover-aroma.sh [OPTIONS]
#
# Options:
#   -p PORT        Port to scan (default: 8080)
#   -u USERNAME    HTTP Basic Auth username (default: admin)
#   -w PASSWORD    HTTP Basic Auth password (default: password)
#   -s SUBNET      Override subnet, e.g. 192.168.1  (auto-detected by default)
#   -t TIMEOUT     Per-host connect timeout in seconds (default: 0.5)
#   -j JOBS        Max parallel probes (default: 50)
#   -T SECS        mDNS listen window in seconds (default: 4)
#   -m             mDNS only — no network scan fallback
#   -S             Scan only — skip mDNS
#   -q             Quiet — print only discovered URLs, no progress
#   -h             Show this help
#
# Default behaviour: try mDNS first (instant); if nothing found within -T
# seconds, fall back to a full subnet scan automatically.
#
# Examples:
#   ./discover-aroma.sh                  # auto: mDNS then scan
#   ./discover-aroma.sh -m               # mDNS only
#   ./discover-aroma.sh -S               # subnet scan only
#   ./discover-aroma.sh -p 9090 -u alice -w secret
#   ./discover-aroma.sh -s 10.0.0 -q

set -euo pipefail

# ── defaults ─────────────────────────────────────────────────────────────────
PORT=8080
USERNAME="admin"
PASSWORD="password"
SUBNET=""
TIMEOUT="0.5"
JOBS=50
MDNS_TIMEOUT=4
MDNS_ONLY=0
SCAN_ONLY=0
QUIET=0

# ── argument parsing ──────────────────────────────────────────────────────────
usage() {
    sed -n '2,/^$/p' "$0" | sed 's/^# \?//'
    exit 0
}

while getopts ":p:u:w:s:t:j:T:mSqh" opt; do
    case $opt in
        p) PORT="$OPTARG" ;;
        u) USERNAME="$OPTARG" ;;
        w) PASSWORD="$OPTARG" ;;
        s) SUBNET="$OPTARG" ;;
        t) TIMEOUT="$OPTARG" ;;
        j) JOBS="$OPTARG" ;;
        T) MDNS_TIMEOUT="$OPTARG" ;;
        m) MDNS_ONLY=1 ;;
        S) SCAN_ONLY=1 ;;
        q) QUIET=1 ;;
        h) usage ;;
        :) echo "Option -$OPTARG requires an argument." >&2; exit 1 ;;
        \?) echo "Unknown option: -$OPTARG" >&2; exit 1 ;;
    esac
done

# All progress/status output goes to stderr so it never pollutes the
# FOUND-line stream that gets written to $RESULTS.
log()  { [ "$QUIET" -eq 0 ] && echo "$*" >&2 || true; }
info() { [ "$QUIET" -eq 0 ] && echo "  $*" >&2 || true; }

# ── subnet auto-detection ─────────────────────────────────────────────────────
detect_subnet() {
    local ip=""
    if command -v route &>/dev/null && route -n get default &>/dev/null 2>&1; then
        local iface
        iface=$(route -n get default 2>/dev/null | awk '/interface:/{print $2}' | head -1)
        [ -n "$iface" ] && ip=$(ipconfig getifaddr "$iface" 2>/dev/null || true)
    fi
    if [ -z "$ip" ] && command -v ip &>/dev/null; then
        ip=$(ip route get 1.1.1.1 2>/dev/null \
            | awk '/src/{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}' | head -1)
    fi
    if [ -z "$ip" ] && command -v hostname &>/dev/null; then
        ip=$(hostname -I 2>/dev/null | awk '{print $1}')
    fi
    [ -n "$ip" ] && echo "$ip" | awk -F. '{print $1"."$2"."$3}' || echo ""
}

# ── mDNS discovery ────────────────────────────────────────────────────────────
# Prints "FOUND: http://IP:PORT  (mDNS)" lines to stdout.
# Tries dns-sd (macOS), avahi-browse (Linux), then embedded Python3.

discover_mdns_dnssd() {
    # dns-sd outputs one "Add" line per discovered service then blocks.
    # We run it for MDNS_TIMEOUT seconds and collect service names, then
    # resolve each one to get host + port.
    log "Querying mDNS via dns-sd (${MDNS_TIMEOUT}s)..."

    local names
    names=$(timeout "$MDNS_TIMEOUT" dns-sd -B _aroma._tcp local. 2>/dev/null \
        | awk '/Add/{print $NF}' || true)

    [ -z "$names" ] && return 1

    while IFS= read -r name; do
        [ -z "$name" ] && continue
        local resolve
        resolve=$(timeout 2 dns-sd -L "$name" _aroma._tcp local. 2>/dev/null || true)

        # Extract host and port from: "Name can be reached at host.local.:PORT"
        local host port
        host=$(echo "$resolve" | grep -oE 'reached at [^ ]+' | awk '{print $3}' | sed 's/\.$//' | head -1)
        port=$(echo "$resolve" | grep -oE 'reached at [^:]+:[0-9]+' | grep -oE ':[0-9]+' | tr -d ':' | head -1)

        [ -z "$host" ] && continue
        [ -z "$port" ] && port="$PORT"

        # Resolve .local hostname to IP
        local ip
        ip=$(dns-sd -G v4 "$host.local." 2>/dev/null | timeout 2 awk '/Add/{print $6; exit}' || true)
        [ -z "$ip" ] && ip="$host"  # use hostname directly if resolution fails

        echo "FOUND: http://$ip:$port  (mDNS/dns-sd — $name)"
    done <<< "$names"
    return 0
}

discover_mdns_avahi() {
    log "Querying mDNS via avahi-browse (${MDNS_TIMEOUT}s)..."
    # Parsable format: =;iface;proto;name;type;domain;host;addr;port;txt
    local results
    results=$(timeout "$MDNS_TIMEOUT" avahi-browse _aroma._tcp \
        --resolve --terminate --parsable 2>/dev/null \
        | grep '^=' || true)

    [ -z "$results" ] && return 1

    while IFS=';' read -r _ _ _ name _ _ _ addr port _; do
        [ -z "$addr" ] && continue
        echo "FOUND: http://$addr:$port  (mDNS/avahi — $name)"
    done <<< "$results"
    return 0
}

discover_mdns_python() {
    command -v python3 &>/dev/null || return 1
    log "Querying mDNS via Python3 (${MDNS_TIMEOUT}s)..."

    python3 - "$MDNS_TIMEOUT" <<'PYEOF'
import socket, struct, sys, time, re

MDNS_ADDR = "224.0.0.251"
MDNS_PORT = 5353
TIMEOUT   = float(sys.argv[1]) if len(sys.argv) > 1 else 4.0
SERVICE   = b"\x07_aroma\x04_tcp\x05local\x00"

def build_ptr_query():
    # Standard mDNS PTR query for _aroma._tcp.local.
    qname = SERVICE
    return (
        b"\x00\x00"      # transaction ID (0 for mDNS)
        b"\x00\x00"      # flags: standard query
        b"\x00\x01"      # QDCOUNT = 1
        b"\x00\x00"      # ANCOUNT = 0
        b"\x00\x00"      # NSCOUNT = 0
        b"\x00\x00"      # ARCOUNT = 0
        + qname
        + b"\x00\x0c"    # QTYPE  = PTR (12)
        + b"\x00\x01"    # QCLASS = IN  (1)
    )

def parse_name(data, offset):
    """Parse a DNS name from data at offset, return (name, new_offset)."""
    parts = []
    visited = set()
    while offset < len(data):
        length = data[offset]
        if length == 0:
            offset += 1
            break
        if (length & 0xC0) == 0xC0:          # pointer
            ptr = ((length & 0x3F) << 8) | data[offset + 1]
            if ptr in visited:
                break
            visited.add(ptr)
            part, _ = parse_name(data, ptr)
            parts.append(part)
            offset += 2
            break
        else:
            offset += 1
            parts.append(data[offset:offset + length].decode(errors="replace"))
            offset += length
    return ".".join(parts), offset

def parse_response(data):
    """Return list of (name, addr, port) tuples found in an mDNS packet."""
    if len(data) < 12:
        return []
    an_count = struct.unpack(">H", data[4:6])[0]
    ar_count = struct.unpack(">H", data[10:12])[0]
    offset = 12

    # Skip questions
    qd_count = struct.unpack(">H", data[2:4])[0]
    for _ in range(qd_count):
        _, offset = parse_name(data, offset)
        offset += 4  # QTYPE + QCLASS

    names, addrs, ports = set(), {}, {}

    for _ in range(an_count + ar_count):
        if offset >= len(data):
            break
        rname, offset = parse_name(data, offset)
        if offset + 10 > len(data):
            break
        rtype, rclass, ttl, rdlen = struct.unpack(">HHIH", data[offset:offset + 10])
        offset += 10
        rdata = data[offset:offset + rdlen]
        offset += rdlen

        if rtype == 12:   # PTR → service instance name
            target, _ = parse_name(rdata, 0)
            names.add(target)
        elif rtype == 33:  # SRV → host + port
            if rdlen >= 6:
                port = struct.unpack(">H", rdata[4:6])[0]
                host, _ = parse_name(rdata, 6)
                ports[rname] = (host, port)
        elif rtype == 1 and rdlen == 4:  # A → IPv4
            ip = ".".join(str(b) for b in rdata)
            addrs[rname] = ip

    results = []
    for name in names:
        host, port = ports.get(name, (None, None))
        if host:
            # Strip trailing dot, look up IP
            host_key = host.rstrip(".")
            ip = addrs.get(host_key, addrs.get(host_key + ".local", host_key))
            results.append((name, ip, port))
    return results

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
sock.settimeout(0.5)

# Join the mDNS multicast group on all interfaces
try:
    mreq = struct.pack("4sL", socket.inet_aton(MDNS_ADDR), socket.INADDR_ANY)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
except Exception:
    pass

sock.sendto(build_ptr_query(), (MDNS_ADDR, MDNS_PORT))

found = {}
deadline = time.time() + TIMEOUT
while time.time() < deadline:
    try:
        data, addr = sock.recvfrom(4096)
    except socket.timeout:
        continue
    for name, host, port in parse_response(data):
        key = (host, port)
        if key not in found:
            found[key] = name
            ip = addr[0] if host == addr[0] else host
            p  = port if port else 8080
            print(f"FOUND: http://{ip}:{p}  (mDNS/python — {name})", flush=True)

sock.close()
PYEOF
}

discover_mdns() {
    local found=0

    if command -v dns-sd &>/dev/null; then
        if discover_mdns_dnssd; then found=1; fi
    elif command -v avahi-browse &>/dev/null; then
        if discover_mdns_avahi; then found=1; fi
    else
        if discover_mdns_python; then found=1; fi
    fi

    return $((1 - found))   # 0 = found something, 1 = nothing
}

# ── subnet scan ───────────────────────────────────────────────────────────────
probe_host() {
    local host="$1"
    local url="http://$host:$PORT/_aroma_diag"

    local body http_code
    body=$(curl -s \
        --max-time "$TIMEOUT" --connect-timeout "$TIMEOUT" \
        -u "$USERNAME:$PASSWORD" \
        -w "\n%{http_code}" \
        "$url" 2>/dev/null) || return

    http_code=$(echo "$body" | tail -1)
    local content
    # 'head -n -1' is GNU-only; awk prints all lines except the last (BSD-safe)
    content=$(echo "$body" | awk 'NR>1{print prev} {prev=$0}')

    if [ "$http_code" = "200" ] && echo "$content" | grep -q "AROMA diagnostics"; then
        echo "FOUND: http://$host:$PORT  (scan — authenticated)"
        return
    fi

    if [ "$http_code" = "401" ]; then
        echo "FOUND: http://$host:$PORT  (scan — wrong credentials, server present)"
        return
    fi

    # Last resort: check root HTML for the file manager title
    if curl -s --max-time "$TIMEOUT" --connect-timeout "$TIMEOUT" \
            -u "$USERNAME:$PASSWORD" "http://$host:$PORT/" 2>/dev/null \
            | grep -q "AROMA File Manager"; then
        echo "FOUND: http://$host:$PORT  (scan — root page match)"
    fi
}

export -f probe_host
export PORT USERNAME PASSWORD TIMEOUT

discover_scan() {
    if [ -z "$SUBNET" ]; then
        SUBNET=$(detect_subnet)
        if [ -z "$SUBNET" ]; then
            echo "ERROR: Could not detect local subnet. Use -s SUBNET (e.g. -s 192.168.1)" >&2
            exit 1
        fi
    fi

    log "Scanning $SUBNET.1–$SUBNET.254 on port $PORT..."

    local active=0
    for i in $(seq 1 254); do
        local host="$SUBNET.$i"
        ( result=$(probe_host "$host"); [ -n "$result" ] && echo "$result" ) &
        active=$((active + 1))
        if [ "$active" -ge "$JOBS" ]; then
            wait; active=0
        fi
    done
    wait
}

# ── main ──────────────────────────────────────────────────────────────────────
log ""
log "AROMA Discovery"
log "  Port    : $PORT"
log "  User    : $USERNAME"

RESULTS=$(mktemp)

if [ "$SCAN_ONLY" -eq 1 ]; then
    log "  Mode    : subnet scan only"
    log ""
    discover_scan | tee -a "$RESULTS"
elif [ "$MDNS_ONLY" -eq 1 ]; then
    log "  Mode    : mDNS only"
    log ""
    discover_mdns | tee -a "$RESULTS" || true
else
    log "  Mode    : mDNS first, then subnet scan if needed"
    log ""
    # Capture only FOUND lines from mDNS (log messages go to stderr now)
    MDNS_OUT=$(mktemp)
    discover_mdns > "$MDNS_OUT" || true
    cat "$MDNS_OUT" | tee -a "$RESULTS"

    if [ ! -s "$RESULTS" ]; then
        log ""
        log "Nothing found via mDNS — falling back to subnet scan..."
        log ""
        discover_scan | tee -a "$RESULTS"
    fi
    rm -f "$MDNS_OUT"
fi

# grep -c exits 1 when count is 0, which would trigger || echo 0 and
# produce "0\n0". Use || true so grep's own "0" output is kept as-is.
COUNT=$(grep -c "^FOUND:" "$RESULTS" 2>/dev/null || true)
rm -f "$RESULTS"

log ""
if [ "$COUNT" -eq 0 ]; then
    log "No AROMA servers found."
    log ""
    log "Tips:"
    log "  • Make sure the server is running (tap Start Server in AROMA)"
    log "  • Both devices must be on the same WiFi network"
    log "  • Try a different port: -p PORT"
    log "  • Try different credentials: -u USER -w PASS"
    log "  • Force a full scan: -S"
    exit 1
else
    log "Done — $COUNT server(s) found."
    exit 0
fi
