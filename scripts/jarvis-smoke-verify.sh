#!/usr/bin/env bash
# End-to-end smoke verification of the live Jarvis stack. Read-only except for
# creating one throwaway Obsidian note (in the real vault) to prove unified search.
# Prints PASS/FAIL per check; exit 0 only if all critical checks pass.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GW="${JARVIS_API_BASE:-https://10.113.0.176}"; HOST="${JARVIS_API_HOST:-api.jarvis.local}"
USER_LOGIN="${JARVIS_TEST_USER:-test1111}"; USER_PASS="${JARVIS_TEST_PASS:-test1111}"
pass=0; fail=0
ok(){ printf '  \033[0;32m[PASS]\033[0m %s\n' "$1"; pass=$((pass+1)); }
no(){ printf '  \033[0;31m[FAIL]\033[0m %s\n' "$1"; fail=$((fail+1)); }
chk(){ local label="$1" got="$2" want="$3"; [ "$got" = "$want" ] && ok "$label ($got)" || no "$label (got=$got want=$want)"; }

TOKEN="$(curl -sk -m10 -H "Host: $HOST" "$GW/api/v1/security/auth/login" \
  -H 'Content-Type: application/json' -d "{\"username\":\"$USER_LOGIN\",\"password\":\"$USER_PASS\"}" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)"
A=(-sk -m15 -H "Host: $HOST" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

echo "== Jarvis smoke verify =="

# 1. stack health
"$ROOT/jarvis" health >/dev/null 2>&1 && ok "jarvis health READY" || no "jarvis health"

# 2. host-model-daemon endpoint (placeholder regression guard) — read-only; fails loudly with the fix
if "$ROOT/scripts/jarvis-host-endpoint-check.sh" >/dev/null 2>&1; then
  ok "host-model-daemon endpoint wired + reachable"
else
  no "host-model-daemon endpoint = PLACEHOLDER (14B brain unreachable from cluster)"
  printf '         \033[0;33mfix:\033[0m ./scripts/jarvis-host-endpoint-check.sh --fix   (or ./scripts/jarvis-final-check.sh --repair)\n'
fi

# 3. /status/report
chk "/status/report" "$(curl "${A[@]}" -o /dev/null -w '%{http_code}' "$GW/api/v1/status/report")" "200"

# 4. llm-service chat (14B brain)
MODEL="$(curl "${A[@]}" -m45 -X POST "$GW/api/v1/llm/chat" -d '{"sessionId":"smoke","messages":[{"role":"user","content":"ответь одним словом: жив /no_think"}]}' 2>/dev/null | python3 -c 'import json,sys;print(json.load(sys.stdin).get("model",""))' 2>/dev/null)"
[ -n "$MODEL" ] && ok "llm chat model=$MODEL" || no "llm chat (no model in reply)"

# 5. memory semantic search
chk "memory /search" "$(curl "${A[@]}" -H 'X-User-Id: 2' -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/memory/search" -d '{"query":"jarvis","topK":2}')" "200"

# 6. unified search finds an Obsidian note (create -> index -> search)
MARK="smoke-$(date +%s)"
"$ROOT/jarvis" obsidian note "Smoke $MARK" --body "unified search marker $MARK" --folder memory >/dev/null 2>&1
SECRET="$(grep '^SERVICE_JWT_SECRET=' "$HOME/.jarvis/wake.env" 2>/dev/null | cut -d= -f2-)"
SERVICE_JWT_SECRET="$SECRET" python3 "$ROOT/scripts/jarvis-obsidian.py" index --limit 50 >/dev/null 2>&1
FOUND="$(curl "${A[@]}" -H 'X-User-Id: 2' -X POST "$GW/api/v1/memory/search/unified" -d "{\"query\":\"$MARK\",\"topK\":5}" 2>/dev/null | python3 -c 'import json,sys
d=json.load(sys.stdin); print(sum(1 for r in d.get("results",[]) if r.get("source")=="obsidian"))' 2>/dev/null)"
[ "${FOUND:-0}" -ge 1 ] && ok "unified search returns obsidian note ($FOUND)" || no "unified search obsidian hit ($FOUND)"

# 7. planner LLM endpoint (active or graceful 503)
PC="$(curl "${A[@]}" -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/planner/llm/recommend" -d '{"userId":"2","context":"smoke"}')"
{ [ "$PC" = "200" ] || [ "$PC" = "503" ]; } && ok "planner LLM (active/graceful=$PC)" || no "planner LLM ($PC)"

# 8. Obsidian unit tests
python3 "$ROOT/scripts/tests/test_jarvis_obsidian.py" >/dev/null 2>&1 && ok "obsidian unit tests" || no "obsidian unit tests"

echo "== result: $pass passed, $fail failed =="
[ "$fail" -eq 0 ]
