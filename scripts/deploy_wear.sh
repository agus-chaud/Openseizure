#!/usr/bin/env bash
# =============================================================================
# deploy_wear.sh — Compila e instala el módulo :wear en el Samsung Watch 8
# =============================================================================
#
# USO:
#   ./scripts/deploy_wear.sh                    # build debug (flavor companion) + install
#   ./scripts/deploy_wear.sh --tests-only       # solo correr tests sin instalar
#   ./scripts/deploy_wear.sh --release          # build release (requiere keystore)
#
# PRE-REQUISITOS:
#   - Watch conectado via ADB (ver connect_watch.sh)
#   - ANDROID_HOME o JAVA_HOME configurados (Android Studio lo hace automático)
#
# =============================================================================

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

MODE="${1:-}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log_info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# ─── Solo tests (sin dispositivo) ────────────────────────────────────────────
if [[ "$MODE" == "--tests-only" ]]; then
  log_info "Corriendo tests unitarios + Robolectric (sin dispositivo)..."
  ./gradlew :wear:test --info 2>&1 | tail -30
  log_ok "Tests completados."
  exit 0
fi

# ─── Verificar que hay un watch conectado ────────────────────────────────────
WATCH_DEVICE=$(adb devices | grep -v "List of devices" | grep "device$" | awk '{print $1}' | head -1)

if [[ -z "$WATCH_DEVICE" ]]; then
  log_error "No hay dispositivos ADB conectados."
  log_info  "Primero: ./scripts/connect_watch.sh <IP-DEL-RELOJ>"
  exit 1
fi

log_info "Dispositivo detectado: $WATCH_DEVICE"

# ─── Build ────────────────────────────────────────────────────────────────────
# The :wear module has a `transport` flavor dimension (companion | osdDirect). Deploying always
# builds the COMPANION flavor explicitly: the flavor-less outputs (wear/build/outputs/apk/debug/,
# .../release/) are no longer produced, and a stale APK there must never be installed.
if [[ "$MODE" == "--release" ]]; then
  log_info "Build RELEASE (companion) del módulo :wear..."
  BUILD_TASK=":wear:assembleCompanionRelease"
  APK_DIR="wear/build/outputs/apk/companion/release"
else
  log_info "Build DEBUG (companion) del módulo :wear..."
  BUILD_TASK=":wear:assembleCompanionDebug"
  APK_DIR="wear/build/outputs/apk/companion/debug"
fi

# Remove any previous APK so one that survives can only have come from this build (Gradle
# re-packages when its output is missing).
rm -f "$APK_DIR"/*.apk
# Timestamp marker taken BEFORE the build: the APK must be newer than this.
BUILD_STAMP="$(mktemp)"
trap 'rm -f "$BUILD_STAMP"' EXIT
./gradlew "$BUILD_TASK"

# Exactly one .apk is expected (name is derived by AGP, e.g. wear-companion-debug.apk, or
# wear-companion-release-unsigned.apk when no keystore is configured). Anything else is an error.
shopt -s nullglob
APKS=("$APK_DIR"/*.apk)
shopt -u nullglob
if [[ ${#APKS[@]} -ne 1 ]]; then
  log_error "Se esperaba exactamente 1 APK en $APK_DIR y hay ${#APKS[@]}."
  exit 1
fi
APK_PATH="${APKS[0]}"

if [[ ! -f "$APK_PATH" ]]; then
  log_error "APK no encontrado en: $APK_PATH"
  exit 1
fi
if [[ ! "$APK_PATH" -nt "$BUILD_STAMP" ]]; then
  log_error "APK desactualizado (más viejo que este build): $APK_PATH — no se instala."
  exit 1
fi
if [[ "$APK_PATH" == *unsigned* ]]; then
  log_error "APK release sin firmar ($APK_PATH): falta keystore.properties — no se puede instalar."
  exit 1
fi

APK_SIZE=$(wc -c < "$APK_PATH")
log_ok "APK generado: $APK_PATH ($(echo "$APK_SIZE / 1024" | bc) KB)"

# ─── Verificar que el modelo no está comprimido en el APK ─────────────────────
log_info "Verificando que cnn_v024.tflite NO está comprimido en el APK..."
# aapt2 dump badging muestra si el asset está comprimido
# Alternativa: usar unzip -v para ver el método de compresión
TFLITE_ENTRY=$(unzip -v "$APK_PATH" 2>/dev/null | grep "tflite" || true)
if [[ -z "$TFLITE_ENTRY" ]]; then
  log_warn "No se encontró .tflite en el APK — verificar que el modelo está en wear/src/main/assets/"
elif echo "$TFLITE_ENTRY" | grep -q "Stored"; then
  log_ok "cnn_v024.tflite está sin comprimir en el APK (Stored) ✓"
else
  log_warn "ATENCIÓN: el .tflite puede estar comprimido. Verificar aaptOptions en build.gradle.kts"
  echo "  Entry info: $TFLITE_ENTRY"
fi

# ─── Instalar en el watch ─────────────────────────────────────────────────────
log_info "Instalando en $WATCH_DEVICE..."
adb -s "$WATCH_DEVICE" install -r "$APK_PATH"

log_ok "APK instalado exitosamente."

# ─── Lanzar la app ────────────────────────────────────────────────────────────
log_info "Lanzando SeizureGuard en el watch..."
adb -s "$WATCH_DEVICE" shell am start -n "com.seizureguard.wear/.MainActivity" 2>/dev/null || \
  log_warn "No se pudo lanzar automáticamente — abrila manualmente en el watch."

echo ""
log_info "Para ver logs en tiempo real:"
echo "  adb -s $WATCH_DEVICE logcat -s SeizureGuard:D TFLiteModelLoader:D"
echo ""
log_info "Para correr tests instrumented en el watch:"
echo "  ./gradlew :wear:connectedAndroidTest"
