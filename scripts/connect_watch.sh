#!/usr/bin/env bash
# =============================================================================
# connect_watch.sh — Conecta al Samsung Galaxy Watch 8 via ADB Wireless
# =============================================================================
#
# USO:
#   ./scripts/connect_watch.sh                  # busca el watch en la red local
#   ./scripts/connect_watch.sh 192.168.1.42     # conecta a IP específica
#   ./scripts/connect_watch.sh 192.168.1.42 5555  # IP + puerto específico
#
# PRE-REQUISITOS EN EL WATCH:
#   1. Ajustes → Acerca del reloj → Información de software
#      → Tocar "Número de compilación" 7 veces
#      → "Modo desarrollador activado"
#
#   2. Ajustes → Opciones de desarrollador
#      → Depuración ADB → ON
#      → Depuración inalámbrica → ON
#      → Anotar la IP y puerto que aparecen en pantalla
#
# =============================================================================

set -euo pipefail

WATCH_IP="${1:-}"
WATCH_PORT="${2:-5555}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ─── Colores para output ────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
log_ok()      { echo -e "${GREEN}[OK]${NC}    $*"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error()   { echo -e "${RED}[ERROR]${NC} $*"; }

# ─── Verificar ADB instalado ─────────────────────────────────────────────────
if ! command -v adb &>/dev/null; then
  log_error "adb no encontrado."
  log_info  "Instalalo con Android Studio → SDK Manager → SDK Tools → Android SDK Platform-Tools"
  log_info  "O agregá \$ANDROID_HOME/platform-tools a tu PATH"
  exit 1
fi

ADB_VERSION=$(adb version | head -1)
log_info "ADB disponible: $ADB_VERSION"

# ─── Buscar watch automáticamente si no se pasó IP ───────────────────────────
if [[ -z "$WATCH_IP" ]]; then
  log_info "No se especificó IP. Buscando dispositivos ADB ya conectados..."

  EXISTING=$(adb devices | grep -v "List of devices" | grep "device$" | awk '{print $1}' | head -1)
  if [[ -n "$EXISTING" ]]; then
    log_ok "Dispositivo ya conectado: $EXISTING"
    echo ""
    echo "  Device:  $EXISTING"
    echo "  Status:  $(adb -s "$EXISTING" get-state 2>/dev/null || echo 'unknown')"
    echo ""
    log_info "Para desplegar la app: ./scripts/deploy_wear.sh"
    exit 0
  fi

  echo ""
  log_warn "No se detectó ningún dispositivo conectado."
  echo ""
  echo "  ¿Cómo obtener la IP del watch?"
  echo "  ─────────────────────────────────────────────────────────────────"
  echo "  En el reloj:"
  echo "  Ajustes → Opciones de desarrollador → Depuración inalámbrica"
  echo "  Aparece: IP Address: 192.168.x.x    Port: 5555"
  echo "  ─────────────────────────────────────────────────────────────────"
  echo ""
  echo "  Luego corré:"
  echo "  ./scripts/connect_watch.sh <IP-DEL-RELOJ>"
  echo ""
  exit 1
fi

# ─── Conectar al watch ────────────────────────────────────────────────────────
WATCH_TARGET="${WATCH_IP}:${WATCH_PORT}"
log_info "Conectando a ${WATCH_TARGET}..."

CONNECT_OUTPUT=$(adb connect "$WATCH_TARGET" 2>&1)
echo "  → $CONNECT_OUTPUT"

if echo "$CONNECT_OUTPUT" | grep -q "connected\|already connected"; then
  log_ok "Conexión establecida: $WATCH_TARGET"
else
  log_error "No se pudo conectar. Verificá:"
  echo "  1. El watch y la PC están en la misma red WiFi"
  echo "  2. Depuración inalámbrica está ON en el watch"
  echo "  3. La IP y puerto son correctos"
  echo "  4. El firewall de Windows no bloquea el puerto $WATCH_PORT"
  echo ""
  echo "  Firewall (PowerShell como Admin):"
  echo "  New-NetFirewallRule -DisplayName 'ADB Watch' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $WATCH_PORT"
  exit 1
fi

# ─── Verificar el dispositivo conectado ──────────────────────────────────────
echo ""
log_info "Verificando dispositivo..."
sleep 1  # dar tiempo al handshake ADB

DEVICE_STATE=$(adb -s "$WATCH_TARGET" get-state 2>/dev/null || echo "unknown")
if [[ "$DEVICE_STATE" != "device" ]]; then
  log_warn "El dispositivo está en estado: $DEVICE_STATE"
  if [[ "$DEVICE_STATE" == "unauthorized" ]]; then
    echo ""
    echo "  → En el reloj debería aparecer un diálogo:"
    echo "    '¿Permitir depuración ADB desde esta PC?'"
    echo "    Seleccioná 'Siempre permitir' y tocá OK"
    echo ""
    echo "  Luego corré de nuevo: ./scripts/connect_watch.sh $WATCH_IP $WATCH_PORT"
  fi
  exit 1
fi

# ─── Info del dispositivo ─────────────────────────────────────────────────────
MODEL=$(adb -s "$WATCH_TARGET" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
ANDROID=$(adb -s "$WATCH_TARGET" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
SDK=$(adb -s "$WATCH_TARGET" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')

echo ""
echo "  ┌──────────────────────────────────────────────┐"
echo "  │  Dispositivo conectado                        │"
echo "  ├──────────────────────────────────────────────┤"
echo "  │  Modelo:   $MODEL"
echo "  │  Android:  $ANDROID  (API $SDK)"
echo "  │  ADB:      $WATCH_TARGET"
echo "  └──────────────────────────────────────────────┘"
echo ""
log_ok "Watch listo para deploy."
log_info "Siguiente paso: ./scripts/deploy_wear.sh"
