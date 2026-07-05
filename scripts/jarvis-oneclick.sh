#!/usr/bin/env bash
# =============================================================================
# jarvis-oneclick.sh — SINGLE-CLICK startup for the Jarvis desktop icon.
#
# One click brings up the whole stack, then opens the Control Center:
#   1. start k3s if it is not running (passwordless sudo)
#   2. ensure the host brain (Qwen :18080) + repair its cluster endpoint
#   3. recover the jarvis-prod pods if the gateway is not Ready
#      (targeted pod-recreate via jarvis-recover-after-reboot.sh — never
#       `apply`/`jarvis up`, so it can't stomp movie tags)
#   4. wait for the api-gateway to report healthy
#   5. launch the desktop GUI (prebuilt jar) against the k3s ingress
#
# Progress is shown in a zenity splash; errors pop a zenity dialog with the
# log path. No terminal required — safe to wire to a .desktop Exec=.
# =============================================================================
set -uo pipefail

ROOT="/home/kwaqa/Jarvis/Jarvis2.0"
cd "$ROOT" || exit 1

LOG_DIR="$HOME/.jarvis/logs"; mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/oneclick-$(date +%Y%m%d-%H%M%S 2>/dev/null || echo run).log"
K="sudo -n k3s kubectl -n jarvis-prod"

have(){ command -v "$1" >/dev/null 2>&1; }
log(){ echo "[$(date +%T 2>/dev/null)] $*" >>"$LOG"; }

# ---- progress splash (zenity --pulsate, text updated via a FIFO) ----
PIPE=""
if have zenity && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
  PIPE="$(mktemp -u)"; mkfifo "$PIPE" 2>/dev/null || PIPE=""
  if [ -n "$PIPE" ]; then
    zenity --progress --pulsate --auto-close --no-cancel \
      --title="Jarvis" --width=470 --text="Запуск Jarvis…" < "$PIPE" >/dev/null 2>&1 &
    ZPID=$!
    exec 3>"$PIPE"
  fi
fi
step(){
  log "$1"
  if [ -n "$PIPE" ]; then echo "# $1" >&3 2>/dev/null || true
  elif have notify-send; then notify-send "Jarvis" "$1" 2>/dev/null || true; fi
}
close_splash(){ if [ -n "$PIPE" ]; then exec 3>&- 2>/dev/null || true; rm -f "$PIPE" 2>/dev/null || true; PIPE=""; fi; }
fail(){
  log "FAIL: $1"; close_splash
  if have zenity && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
    zenity --error --title="Jarvis" --width=470 --text="$1\n\nЛог: $LOG" >/dev/null 2>&1
  elif have notify-send; then notify-send -u critical "Jarvis — ошибка" "$1 (лог: $LOG)"; fi
  exit 1
}

log "=== one-click start ==="

# ---- 1. k3s ----
step "Проверка кластера k3s…"
reach_cluster(){ local n="$1"; for _ in $(seq 1 "$n"); do $K get ns >/dev/null 2>&1 && return 0; sleep 2; done; return 1; }
if ! systemctl is-active --quiet k3s; then
  step "Запуск k3s…"
  sudo -n systemctl start k3s >>"$LOG" 2>&1 || fail "Не удалось запустить k3s. Проверьте sudo."
fi
# The unit can report 'active' while the API server has crashed (e.g. OOM under
# load) — 6443 refuses connections. Detect that and restart k3s.
if ! reach_cluster 15; then
  step "Перезапуск k3s (API не отвечает)…"
  sudo -n systemctl restart k3s >>"$LOG" 2>&1 || true
  reach_cluster 60 || fail "k3s API не отвечает даже после перезапуска."
fi

# ---- 2. host brain + endpoint ----
step "Мозг и голос (Qwen/Piper)…"
systemctl --user start "jarvis-llm@18080.service" >>"$LOG" 2>&1 || true
[ -f scripts/jarvis-host-endpoint-check.sh ] && bash scripts/jarvis-host-endpoint-check.sh --fix >>"$LOG" 2>&1 || true

# ---- 3. recover pods if the gateway is not Ready ----
ready="$($K get deploy api-gateway -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)"
if ! [[ "$ready" =~ ^[1-9] ]]; then
  step "Восстановление сервисов (≈1–2 мин)…"
  if [ -f scripts/product/jarvis-recover-after-reboot.sh ]; then
    bash scripts/product/jarvis-recover-after-reboot.sh >>"$LOG" 2>&1 || log "recover exited nonzero (continuing)"
  fi
fi

# ---- 4. wait for gateway health ----
step "Ожидание готовности сервисов…"
NODE_IP="$($K get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null | awk '{print $1}')"
[ -n "$NODE_IP" ] || NODE_IP="10.113.0.176"
healthy=false
for _ in $(seq 1 60); do
  if curl -sk -m5 -H 'Host: api.jarvis.local' "https://$NODE_IP/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    healthy=true; break
  fi
  sleep 3
done
$healthy && log "gateway healthy" || log "gateway health not confirmed after ~180s; launching UI anyway"

# ---- 5. launch the desktop GUI ----
step "Запуск интерфейса…"
sleep 1
close_splash
exec bash "$ROOT/scripts/jarvis-desktop-app.sh"
