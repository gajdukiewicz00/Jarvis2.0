#!/usr/bin/env bash
# =============================================================================
# jarvis-parse-bank.sh — parse a bank push notification into a transaction draft.
#   Banking Push Parser epic — deterministic + local-only (no external LLM).
#
#   scripts/jarvis-parse-bank.sh "Płatność kartą 23,99 PLN Lidl"
#   scripts/jarvis-parse-bank.sh --store "Payment 12.99 USD Amazon"   # save if HIGH-confidence
# =============================================================================
set -uo pipefail
GW="${JARVIS_API_BASE:-https://10.113.0.176}"; HOST="${JARVIS_API_HOST:-api.jarvis.local}"
USER_LOGIN="${JARVIS_TEST_USER:-test1111}"; USER_PASS="${JARVIS_TEST_PASS:-test1111}"
STORE=false; TXT=""
for a in "$@"; do case "$a" in --store) STORE=true ;; -h|--help) sed -n '2,9p' "$0"; exit 0 ;; *) TXT="${TXT:+$TXT }$a" ;; esac; done
[ -n "${TXT// }" ] || { echo 'usage: scripts/jarvis-parse-bank.sh [--store] "notification text"'; exit 2; }

J=(-sk -H "Host: $HOST" -H 'Content-Type: application/json')
TOKEN="$(curl "${J[@]}" -m10 "$GW/api/v1/security/auth/login" -d "{\"username\":\"$USER_LOGIN\",\"password\":\"$USER_PASS\"}" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)"
BODY="$(python3 -c 'import json,sys;print(json.dumps({"text":sys.argv[1],"store":sys.argv[2]=="true"}))' "$TXT" "$STORE")"
curl "${J[@]}" -m20 -H "Authorization: Bearer $TOKEN" -H 'X-User-Id: 2' -X POST "$GW/api/v1/life/finance/parse-notification" -d "$BODY" 2>/dev/null \
  | python3 -c '
import json,sys
d=json.load(sys.stdin)
col={"HIGH":"\033[0;32m","MEDIUM":"\033[0;33m","LOW":"\033[0;31m"}.get(d.get("confidence"),"")
g=d.get
print("  %s%s\033[0m  %s  %s %s" % (col, g("confidence"), g("type"), g("amount"), g("currency")))
print("  merchant : %s" % g("merchant"))
print("  category : %s" % g("category"))
print("  card     : %s" % g("cardMask"))
print("  dedupKey : %s" % g("dedupKey"))
print("  review?  : %s (low/medium go to manual inbox, not finances)" % g("needsReview"))
if g("storedId"): print("  STORED   : transaction id=%s" % g("storedId"))
if g("notes"): print("  notes    : %s" % g("notes"))'
