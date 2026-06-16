#!/bin/bash
# ============================================================
# DocMind — Stop All Services
# ============================================================
# Stops everything in reverse order:
#   1. Angular UI
#   2. API Gateway
#   3. DocMind API
#   4. Eureka Server
#   5. Docker containers (optional — pass --docker to also stop)
#
# Usage:
#   ./stop.sh              # Stop Java/UI services only
#   ./stop.sh --docker     # Also stop Docker containers
#   ./stop.sh --all        # Same as --docker
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

LOG_DIR="$SCRIPT_DIR/.logs"

# ── Colors ──────────────────────────────────────────────────
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC}  $1"; }
ok()    { echo -e "${GREEN}[  OK]${NC}  $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $1"; }

# ── Helper: kill process tree from a PID file ───────────────
stop_service() {
  local name=$1 pidfile="$LOG_DIR/$2.pid"
  if [ -f "$pidfile" ]; then
    local pid
    pid=$(cat "$pidfile")
    if kill -0 "$pid" 2>/dev/null; then
      # Kill the process group so child JVMs also die
      kill -- -"$(ps -o pgid= -p "$pid" | tr -d ' ')" 2>/dev/null || kill "$pid" 2>/dev/null
      ok "Stopped $name (PID $pid)"
    else
      warn "$name was not running (stale PID $pid)"
    fi
    rm -f "$pidfile"
  else
    warn "No PID file for $name — trying port-based kill"
  fi
}

# ── Helper: kill anything on a given port ───────────────────
kill_port() {
  local port=$1 name=$2
  local pids
  pids=$(lsof -ti :"$port" 2>/dev/null)
  if [ -n "$pids" ]; then
    echo "$pids" | xargs kill -9 2>/dev/null
    ok "Killed $name on port $port"
  fi
}

# ============================================================
# STEP 1 — Angular UI (port 4200)
# ============================================================
info "Stopping Angular UI..."
stop_service "Angular UI" "ui"
kill_port 4200 "Angular UI"

# ============================================================
# STEP 2 — API Gateway (port 8080)
# ============================================================
info "Stopping API Gateway..."
stop_service "API Gateway" "gateway"
kill_port 8080 "API Gateway"

# ============================================================
# STEP 3 — DocMind API (port 8081)
# ============================================================
info "Stopping DocMind API..."
stop_service "DocMind API" "docmind-api"
kill_port 8081 "DocMind API"

# ============================================================
# STEP 4 — Eureka Server (port 8761)
# ============================================================
info "Stopping Eureka Server..."
stop_service "Eureka Server" "eureka"
kill_port 8761 "Eureka Server"

# ============================================================
# STEP 5 — Docker (optional)
# ============================================================
if [[ "$1" == "--docker" || "$1" == "--all" ]]; then
  info "Stopping Docker containers..."
  docker compose down
  ok "Docker containers stopped"
else
  info "Docker containers left running (use ./stop.sh --docker to stop them too)"
fi

# ============================================================
# Done
# ============================================================
echo ""
echo -e "${GREEN}═══════════════════════════════════════════════════${NC}"
echo -e "${GREEN}   DocMind — All Services Stopped${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════${NC}"
echo ""
