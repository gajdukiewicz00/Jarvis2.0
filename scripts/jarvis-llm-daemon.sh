#!/usr/bin/env bash
# =============================================================================
# jarvis-llm-daemon.sh — manage the local Qwen llama.cpp model daemon (:18080).
# =============================================================================
# Durability: installs a systemd --user service so the daemon restarts on
# crash and (with lingering enabled) on boot. A nohup fallback is provided for
# environments without user systemd.
#
# Only the MAIN channel (:18080) is managed by default. Coding (:18081) and
# router (:18082) channels start ONLY when their model files exist AND the
# corresponding env toggles are set — they are NOT started otherwise.
#
# Commands:
#   install     install + enable the systemd --user unit (does not stop a
#               manually-started daemon already on the port)
#   uninstall   stop + disable + remove the unit
#   start       start via systemd (fallback: nohup) — no-op if already healthy
#   stop        stop via systemd (fallback: pkill of the managed process)
#   status      show systemd/process status
#   health      curl the daemon /health on each configured port
#   logs        tail the daemon logs
#
# Config (env, with defaults):
#   JARVIS_LLM_BIN     /home/kwaqa/llama.cpp/build/bin/llama-server
#   JARVIS_LLM_MODEL   /home/kwaqa/.jarvis/models/llm/qwen2.5-3b-instruct-q4_k_m.gguf
#   JARVIS_LLM_HOST    0.0.0.0
#   JARVIS_LLM_PORT    18080
#   JARVIS_LLM_CTX     8192
#   JARVIS_LLM_THREADS 6
#   JARVIS_LLM_CODING_MODEL / JARVIS_LLM_ROUTER_MODEL  (optional; enable 18081/18082)
# =============================================================================
set -uo pipefail

JARVIS_LLM_BIN="${JARVIS_LLM_BIN:-/home/kwaqa/llama.cpp/build/bin/llama-server}"
JARVIS_LLM_MODEL="${JARVIS_LLM_MODEL:-/home/kwaqa/.jarvis/models/llm/qwen3-14b-q4_k_m.gguf}"
JARVIS_LLM_HOST="${JARVIS_LLM_HOST:-0.0.0.0}"
JARVIS_LLM_PORT="${JARVIS_LLM_PORT:-18080}"
JARVIS_LLM_CTX="${JARVIS_LLM_CTX:-8192}"
JARVIS_LLM_THREADS="${JARVIS_LLM_THREADS:-6}"
# Max-settings GPU offload. -ngl 99 puts all layers on the RTX 5070. Flash-attn
# and KV-cache quantization can be added here once verified for the installed
# llama.cpp build (they let context grow without spilling VRAM).
JARVIS_LLM_EXTRA_ARGS="${JARVIS_LLM_EXTRA_ARGS:--ngl 99 -fa on -ctk q8_0 -ctv q8_0}"
JARVIS_LLM_CODING_MODEL="${JARVIS_LLM_CODING_MODEL:-}"
JARVIS_LLM_ROUTER_MODEL="${JARVIS_LLM_ROUTER_MODEL:-}"

UNIT_DIR="${HOME}/.config/systemd/user"
UNIT_NAME="jarvis-llm@.service"
LOG_DIR="${HOME}/.jarvis/logs"
mkdir -p "$LOG_DIR"

have_systemd_user() { systemctl --user show-environment >/dev/null 2>&1; }

health_port() {
  local port="$1"
  local code; code="$(curl -s -o /dev/null -m 4 -w '%{http_code}' "http://127.0.0.1:${port}/health" 2>/dev/null)"
  printf '%s' "${code:-000}"
}

write_unit() {
  mkdir -p "$UNIT_DIR"
  cat > "${UNIT_DIR}/${UNIT_NAME}" <<EOF
[Unit]
Description=Jarvis local LLM (llama.cpp) channel on port %i
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
# %i is the port; model/host/ctx/extra come from the per-instance EnvironmentFile.
EnvironmentFile=%h/.jarvis/llm-%i.env
# \$EXTRA (no braces) is word-split by systemd into separate args (e.g. -ngl 99).
ExecStart=${JARVIS_LLM_BIN} -m \${MODEL} --host \${HOST} --port %i -c \${CTX} -t \${THREADS} --no-webui \$EXTRA
Restart=on-failure
RestartSec=3
StandardOutput=append:${LOG_DIR}/llama-%i.log
StandardError=append:${LOG_DIR}/llama-%i.log

[Install]
WantedBy=default.target
EOF
  echo "wrote ${UNIT_DIR}/${UNIT_NAME}"
}

write_env() {
  local port="$1" model="$2"
  cat > "${HOME}/.jarvis/llm-${port}.env" <<EOF
MODEL=${model}
HOST=${JARVIS_LLM_HOST}
CTX=${JARVIS_LLM_CTX}
THREADS=${JARVIS_LLM_THREADS}
EXTRA=${JARVIS_LLM_EXTRA_ARGS}
EOF
}

enabled_channels() {
  # echo "port model" lines for channels that should run
  echo "${JARVIS_LLM_PORT} ${JARVIS_LLM_MODEL}"
  if [[ -n "$JARVIS_LLM_CODING_MODEL" && -f "$JARVIS_LLM_CODING_MODEL" ]]; then
    echo "18081 ${JARVIS_LLM_CODING_MODEL}"
  fi
  if [[ -n "$JARVIS_LLM_ROUTER_MODEL" && -f "$JARVIS_LLM_ROUTER_MODEL" ]]; then
    echo "18082 ${JARVIS_LLM_ROUTER_MODEL}"
  fi
}

cmd_install() {
  [[ -x "$JARVIS_LLM_BIN" ]] || { echo "❌ llama-server not found/executable: $JARVIS_LLM_BIN"; exit 1; }
  [[ -f "$JARVIS_LLM_MODEL" ]] || { echo "❌ model not found: $JARVIS_LLM_MODEL"; exit 1; }
  have_systemd_user || { echo "❌ user systemd unavailable; use '$0 start' (nohup fallback) instead"; exit 1; }
  write_unit
  while read -r port model; do
    write_env "$port" "$model"
    systemctl --user enable "jarvis-llm@${port}.service" >/dev/null 2>&1
    echo "enabled jarvis-llm@${port} (model=$(basename "$model"))"
  done < <(enabled_channels)
  systemctl --user daemon-reload
  echo "✅ installed. Start with: $0 start"
  echo "ℹ  for start-on-boot without login:  loginctl enable-linger $USER"
}

cmd_uninstall() {
  have_systemd_user || { echo "(no user systemd)"; exit 0; }
  while read -r port _; do
    systemctl --user disable --now "jarvis-llm@${port}.service" >/dev/null 2>&1
  done < <(enabled_channels)
  rm -f "${UNIT_DIR}/${UNIT_NAME}"
  systemctl --user daemon-reload
  echo "✅ uninstalled unit (manual nohup daemons, if any, are untouched)"
}

cmd_start() {
  if [[ "$(health_port "$JARVIS_LLM_PORT")" == "200" ]]; then
    echo "✅ already healthy on :${JARVIS_LLM_PORT} (not double-starting)"; return 0
  fi
  if have_systemd_user && [[ -f "${UNIT_DIR}/${UNIT_NAME}" ]]; then
    while read -r port _; do systemctl --user start "jarvis-llm@${port}.service"; done < <(enabled_channels)
    echo "started via systemd --user"
  else
    echo "systemd unit not installed; nohup fallback on :${JARVIS_LLM_PORT}"
    # shellcheck disable=SC2086  # EXTRA_ARGS is intentionally word-split
    nohup "$JARVIS_LLM_BIN" -m "$JARVIS_LLM_MODEL" --host "$JARVIS_LLM_HOST" \
      --port "$JARVIS_LLM_PORT" -c "$JARVIS_LLM_CTX" -t "$JARVIS_LLM_THREADS" --no-webui $JARVIS_LLM_EXTRA_ARGS \
      > "${LOG_DIR}/llama-${JARVIS_LLM_PORT}.log" 2>&1 &
    echo "nohup pid=$!"
  fi
}

cmd_stop() {
  if have_systemd_user && [[ -f "${UNIT_DIR}/${UNIT_NAME}" ]]; then
    while read -r port _; do systemctl --user stop "jarvis-llm@${port}.service"; done < <(enabled_channels)
    echo "stopped via systemd --user"
  fi
  # also stop any matching nohup/manual process on the main model
  pkill -f "llama-server .*${JARVIS_LLM_MODEL}" 2>/dev/null && echo "stopped manual llama-server" || true
}

cmd_status() {
  echo "binary: $JARVIS_LLM_BIN"
  echo "model:  $JARVIS_LLM_MODEL"
  if have_systemd_user && [[ -f "${UNIT_DIR}/${UNIT_NAME}" ]]; then
    while read -r port _; do
      systemctl --user --no-pager --lines=0 status "jarvis-llm@${port}.service" 2>/dev/null \
        | sed -n '1,3p'
    done < <(enabled_channels)
  else
    pgrep -af "llama-server" | grep -v grep || echo "(no llama-server process)"
  fi
}

cmd_health() {
  local ok=0
  while read -r port _; do
    local code; code="$(health_port "$port")"
    echo "channel :${port} /health -> HTTP ${code}"
    [[ "$code" == "200" ]] || ok=1
  done < <(enabled_channels)
  return $ok
}

cmd_logs() { tail -n "${2:-40}" "${LOG_DIR}/llama-${JARVIS_LLM_PORT}.log" 2>/dev/null || echo "(no log yet)"; }

case "${1:-}" in
  install)   cmd_install ;;
  uninstall) cmd_uninstall ;;
  start)     cmd_start ;;
  stop)      cmd_stop ;;
  status)    cmd_status ;;
  health)    cmd_health ;;
  logs)      cmd_logs "$@" ;;
  *) sed -n '2,38p' "$0"; exit 2 ;;
esac
