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
# log path. No terminal required — safe to wire to a desktop-entry Exec=.
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

# ---- 0. self-heal a stale k3s node-ip (DHCP changed the machine IP) ----
# If the k3s config pins a node-ip that is no longer on any interface, k3s
# crash-loops ("failed to find interface with specified node ip"). Repoint it
# (and /etc/hosts) at the current primary IP before we touch the service.
CFG="/etc/rancher/k3s/config.yaml"
CUR_IP="$(ip -4 addr show scope global 2>/dev/null | grep -oE 'inet (10|192)\.[0-9.]+' | awk '{print $2; exit}')"
[ -n "$CUR_IP" ] || CUR_IP="$(ip route get 1.1.1.1 2>/dev/null | grep -oE 'src [0-9.]+' | awk '{print $2}')"
if [ -n "$CUR_IP" ] && [ -f "$CFG" ]; then
  PINNED="$(grep -oE 'node-ip:[[:space:]]*[0-9.]+' "$CFG" 2>/dev/null | grep -oE '[0-9.]+$' | head -1)"
  if [ -n "$PINNED" ] && [ "$PINNED" != "$CUR_IP" ] && ! ip -4 addr show 2>/dev/null | grep -q "inet ${PINNED}/"; then
    step "IP машины сменился (${PINNED}→${CUR_IP}) — правлю конфиг k3s…"
    log "self-heal node-ip ${PINNED} -> ${CUR_IP}"
    sudo -n cp "$CFG" "${CFG}.bak.oneclick" 2>/dev/null || true
    sudo -n sed -i "s/${PINNED}/${CUR_IP}/g" "$CFG" 2>/dev/null || true
    sudo -n sed -i "s/${PINNED}/${CUR_IP}/g" /etc/hosts 2>/dev/null || true
    sudo -n systemctl restart k3s >>"$LOG" 2>&1 || true
  fi
fi

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

# ---- 2. host brain + host-model-daemon endpoint (k3s-native) ----
step "Мозг и голос (Qwen/Piper)…"
systemctl --user start "jarvis-llm@18080.service" >>"$LOG" 2>&1 || true
# The selectorless host-model-daemon Service/Endpoints point cluster pods at the host
# brain (:18080). If the node IP changed, that endpoint goes stale and llm-service can't
# reach the brain (→ api-gateway/orchestrator init-waits hang). Repoint it, k3s-native.
NODE_IP="$($K get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null | awk '{print $1}')"
if [ -n "$NODE_IP" ]; then
  EP_IP="$($K get endpoints host-model-daemon -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null)"
  if [ -n "$EP_IP" ] && [ "$EP_IP" != "$NODE_IP" ]; then
    step "Обновляю endpoint мозга (${EP_IP}→${NODE_IP})…"
    log "patch host-model-daemon endpoint ${EP_IP} -> ${NODE_IP}"
    $K patch endpoints host-model-daemon --type=json \
      -p="[{\"op\":\"replace\",\"path\":\"/subsets/0/addresses/0/ip\",\"value\":\"${NODE_IP}\"}]" >>"$LOG" 2>&1 || true
    SLICE="$($K get endpointslice -l kubernetes.io/service-name=host-model-daemon -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)"
    [ -n "$SLICE" ] && $K patch endpointslice "$SLICE" --type=json \
      -p="[{\"op\":\"replace\",\"path\":\"/endpoints/0/addresses/0\",\"value\":\"${NODE_IP}\"}]" >>"$LOG" 2>&1 || true
    $K rollout restart deploy/llm-service >>"$LOG" 2>&1 || true
  fi
  # host-tts-daemon (Piper :18090) + its egress NetworkPolicy must ALSO track the
  # node IP, or voice-gateway can't reach Piper and TTS stays "degraded". Mirror the
  # model-daemon heal (this is why TTS broke after an IP change / reboot).
  TTS_EP="$($K get endpoints host-tts-daemon -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null)"
  if [ -n "$TTS_EP" ] && [ "$TTS_EP" != "$NODE_IP" ]; then
    step "Обновляю endpoint голоса (TTS ${TTS_EP}→${NODE_IP})…"
    log "patch host-tts-daemon endpoint + netpol ${TTS_EP} -> ${NODE_IP}"
    $K patch endpoints host-tts-daemon --type=json \
      -p="[{\"op\":\"replace\",\"path\":\"/subsets/0/addresses/0/ip\",\"value\":\"${NODE_IP}\"}]" >>"$LOG" 2>&1 || true
    TSLICE="$($K get endpointslice -l kubernetes.io/service-name=host-tts-daemon -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)"
    [ -n "$TSLICE" ] && $K patch endpointslice "$TSLICE" --type=json \
      -p="[{\"op\":\"replace\",\"path\":\"/endpoints/0/addresses/0\",\"value\":\"${NODE_IP}\"}]" >>"$LOG" 2>&1 || true
    $K patch networkpolicy voice-gateway-egress-tts --type=json \
      -p="[{\"op\":\"replace\",\"path\":\"/spec/egress/0/to/0/ipBlock/cidr\",\"value\":\"${NODE_IP}/32\"}]" >>"$LOG" 2>&1 || true
    $K rollout restart deploy/voice-gateway >>"$LOG" 2>&1 || true
  fi
fi

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
