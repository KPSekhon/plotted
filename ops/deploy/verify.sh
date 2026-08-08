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

echo "1. Is it alive?"
health=$(curl -fsS --max-time 30 "$host/actuator/health" 2>/dev/null || echo '')
if [[ $health == *'"status":"UP"'* ]]; then
  green "   UP"
else
  red "   Not UP: ${health:-no response}"
  printf '   Read the components. Redis being DOWN is expected and harmless --\n'
  printf '   it has one caller (the rate limiter) and is deliberately kept out of\n'
  printf '   the readiness group, so it cannot stop traffic.\n'
  fail=1
fi

echo
echo "2. Did the migrations run?"
providers=$(curl -fsS --max-time 30 "$host/api/v1/providers" 2>/dev/null || echo '')
count=$(printf '%s' "$providers" | grep -o '"slug"' | wc -l | tr -d ' ')
if [[ ${count:-0} -gt 0 ]]; then
  green "   $count providers from reference data"
else
  red "   No providers. This is reference data from the migrations, so an empty"
  printf '   answer means Flyway did not run -- check the startup logs for the\n'
  printf '   extension-permission failure before anything else.\n'
  fail=1
fi

echo
echo "3. Does the demo path work end to end?"
demo=$(curl -fsS --max-time 60 -X POST "$host/api/v1/demo/session" 2>/dev/null || echo '')
if [[ -z $demo ]]; then
  amber "   No response. 404 is expected when PLOTTED_DEMO_ENABLED is not true."
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
