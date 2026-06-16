#!/bin/bash
# ============================================================
# DocMind — Start All Services
# ============================================================
# Starts the full stack in the correct order:
#   1. Docker containers  (ChromaDB, Ollama)
#   2. Eureka Server      (port 8761)
#   3. DocMind API         (port 8081)
#   4. API Gateway         (port 8080)
#   5. Angular UI          (port 4200)
#
# Usage:
#   chmod +x start.sh
#   ./start.sh
# ============================================================

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

LOG_DIR="$SCRIPT_DIR/.logs"
mkdir -p "$LOG_DIR"

# ── Colors ──────────────────────────────────────────────────
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

info()  { echo -e "${CYAN}[INFO]${NC}  $1"; }
ok()    { echo -e "${GREEN}[  OK]${NC}  $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $1"; }
fail()  { echo -e "${RED}[FAIL]${NC}  $1"; }

# ── Load .env ───────────────────────────────────────────────
if [ -f .env ]; then
  export $(grep -v '^#' .env | grep -v '^\s*$' | xargs)
  ok "Loaded .env"
else
  warn ".env not found — make sure OPENAI_API_KEY is exported"
fi

# ── Helper: wait for a port to become available ─────────────
wait_for_port() {
  local port=$1 name=$2 timeout=${3:-60}
  local elapsed=0
  while ! lsof -i :"$port" -sTCP:LISTEN >/dev/null 2>&1; do
    sleep 1
    elapsed=$((elapsed + 1))
    if [ $elapsed -ge $timeout ]; then
      fail "$name did not start on port $port within ${timeout}s"
      return 1
    fi
  done
  ok "$name is UP on port $port"
}

# ============================================================
# STEP 1 — Docker Containers (ChromaDB + Ollama)
# ============================================================
info "Starting Docker containers (ChromaDB, Ollama)..."
docker compose up -d chromadb ollama
ok "Docker containers started"

# Wait for ChromaDB to be reachable
info "Waiting for ChromaDB (port 8000)..."
wait_for_port 8000 "ChromaDB" 30

# ============================================================
# STEP 2 — Build (skip tests for speed)
# ============================================================
info "Building project (skipping tests)..."
./mvnw clean package -DskipTests -q
ok "Build complete"

# ============================================================
# STEP 3 — Eureka Server (port 8761)
# ============================================================
info "Starting Eureka Server..."
nohup ./mvnw spring-boot:run -pl docmind-eureka-server \
  > "$LOG_DIR/eureka.log" 2>&1 &
echo $! > "$LOG_DIR/eureka.pid"
wait_for_port 8761 "Eureka Server" 60

# ============================================================
# STEP 4 — DocMind API (port 8081)
# ============================================================
info "Starting DocMind API (ingestion + query)..."
nohup ./mvnw spring-boot:run -pl docmind-api \
  > "$LOG_DIR/docmind-api.log" 2>&1 &
echo $! > "$LOG_DIR/docmind-api.pid"
wait_for_port 8081 "DocMind API" 60

# ============================================================
# STEP 5 — API Gateway (port 8080)
# ============================================================
info "Starting API Gateway..."
nohup ./mvnw spring-boot:run -pl docmind-gateway \
  > "$LOG_DIR/gateway.log" 2>&1 &
echo $! > "$LOG_DIR/gateway.pid"
wait_for_port 8080 "API Gateway" 60

# ============================================================
# STEP 6 — Angular UI (port 4200)
# ============================================================
info "Starting Angular UI..."
cd docmind-ui
nohup npx ng serve > "$LOG_DIR/ui.log" 2>&1 &
echo $! > "$LOG_DIR/ui.pid"
cd "$SCRIPT_DIR"
wait_for_port 4200 "Angular UI" 60

# ============================================================
# Summary
# ============================================================
echo ""
echo -e "${GREEN}═══════════════════════════════════════════════════${NC}"
echo -e "${GREEN}   DocMind — All Services Running${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════${NC}"
echo -e "   ChromaDB        →  http://localhost:8000"
echo -e "   Ollama           →  http://localhost:11434"
echo -e "   Eureka Server    →  http://localhost:8761"
echo -e "   DocMind API      →  http://localhost:8081"
echo -e "   API Gateway      →  http://localhost:8080"
echo -e "   Angular UI       →  http://localhost:4200"
echo -e "   Swagger UI       →  http://localhost:8080/swagger-ui.html"
echo -e "${GREEN}═══════════════════════════════════════════════════${NC}"
echo -e "   Logs dir: ${LOG_DIR}"
echo -e "   Stop all: ${CYAN}./stop.sh${NC}"
echo ""
