#!/usr/bin/env bash
#
# Plotted :: post-deploy verification
#
#     ops/deploy/verify.sh https://plotted-api-xxxxx.run.app
#
# Three checks in a deliberate order, because each one tells you something the
# previous cannot. Running them out of order means diagnosing the wrong layer:
# an empty /providers looks like a broken deployment when it is actually a
# migration that never ran.

set -uo pipefail

host=${1:-}
[[ -n $host ]] || { echo "Usage: verify.sh <api-host>"; exit 1; }
host=${host%/}

red()   { printf '\033[31m%s\033[0m\n' "$1"; }
amber() { printf '\033[33m%s\033[0m\n' "$1"; }
green() { printf '\033[32m%s\033[0m\n' "$1"; }

fail=0

echo "1. Is it alive, and is the database attached?"
# -f would swallow the body on a 503, and the body is the whole point: the
# aggregate can be DOWN for a component that does not matter.
health=$(curl -sS --max-time 30 "$host/actuator/health" 2>/dev/null || echo '')
if [[ $health == *'"db":{"status":"UP"'* || $health == *'"db": {'*'"status": "UP"'* ]]; then
  green "   Database connected"
elif [[ -z $health ]]; then
  red "   No response from /actuator/health"
  fail=1
else
  red "   Database not UP. Nothing else will work until it is."
  printf '   %s\n' "$health"
  fail=1
fi

if [[ $health == *'"status":"UP"'* ]]; then
  green "   Overall UP"
elif [[ $health == *'"redis":{"status":"DOWN"'* || $health == *'"redis": {'*'"status": "DOWN"'* ]]; then
  # Worth saying plainly rather than letting it read as a failure. Redis has one
  # caller, the rate limiter, and it fails open or closed by policy. It is kept
  # out of the readiness group precisely so it cannot stop traffic.
  amber "   Overall DOWN, but only because Redis is unreachable."
  printf '   That is survivable and expected without a Redis instance: rate\n'
  printf '   limiting degrades, nothing else does. Readiness is unaffected.\n'
else
  amber "   Overall not UP -- read the components above."
fi

echo
echo "2. Is it routing and enforcing auth?"
# /api/v1/providers requires authentication, so 401 is the *healthy* answer.
#
# An earlier version of this script read it as reference data and reported
# "migrations did not run" on a perfectly good deployment -- a check that was red
# when nothing was wrong, which is how a verification script teaches people to
# ignore it. The status code is the signal here, not the body.
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$host/api/v1/providers" 2>/dev/null || echo '000')
case $code in
  401) green "   401 from a protected endpoint -- routing and security are live" ;;
  000) red "   No response. The service is not reachable."; fail=1 ;;
  5*)  red "   $code from /api/v1/providers. Check the startup logs."; fail=1 ;;
  *)   amber "   Unexpected $code from a protected endpoint." ;;
esac

echo
echo "3. Does the demo path work end to end?"
# This is the real migration check. It writes a user, a watchlist and two
# subscriptions, so a success proves the schema exists rather than merely that
# something answered on port 443.
demo=$(curl -fsS --max-time 60 -X POST "$host/api/v1/demo/session" 2>/dev/null || echo '')
if [[ -z $demo ]]; then
  amber "   No response. 404 is expected when PLOTTED_DEMO_ENABLED is not true."
  printf '   With demo mode on, this failing means the schema is incomplete --\n'
  printf '   it writes across users, watchlists and subscriptions.\n'
elif [[ $demo == *'"catalogueIsEmpty":true'* ]]; then
  amber "   Account created, catalogue empty."
  printf '   The seed has not run. Redeploy once with PLOTTED_SEED_ENABLED=true,\n'
  printf '   wait for it, then set it back -- it re-pulls on every cold start.\n'
else
  green "   Demo session created against a seeded catalogue"
fi

echo
if (( fail )); then
  red "Something above needs attention before this is usable."
  exit 1
fi
green "Deployment verified."
