#!/usr/bin/env bash
#
# Plotted :: pre-deploy environment check
#
# Answers "will this boot, and will it do anything useful" before a deploy
# rather than during one. Every check here corresponds to a failure that is
# otherwise discovered from a log after the fact:
#
#   * a missing JWT secret refuses to start, which at least fails loudly
#   * a missing TMDB token starts fine and then cannot seed or refresh
#     availability, which fails quietly and looks like an empty catalogue
#   * a development JWT secret in production is a security hole that boots
#
# The TMDB one is the reason this exists. It was empty in .env for the whole
# of development and nothing noticed, because nothing on a machine without a
# database ever asks TMDB for anything.
#
#     ops/deploy/check-env.sh                 # checks the current shell
#     source .env && ops/deploy/check-env.sh  # checks a .env file
#
# Exit 0 means deployable. Exit 1 means something would have gone wrong.

set -uo pipefail

fail=0
warn=0

red()   { printf '\033[31m%s\033[0m\n' "$1"; }
amber() { printf '\033[33m%s\033[0m\n' "$1"; }
green() { printf '\033[32m%s\033[0m\n' "$1"; }

require() {
  local name=$1 why=$2
  local value=${!name:-}
  if [[ -z $value ]]; then
    red   "  MISSING  $name"
    printf '           %s\n' "$why"
    fail=1
  else
    green "  set      $name (${#value} chars)"
  fi
}

optional() {
  local name=$1 why=$2
  local value=${!name:-}
  if [[ -z $value ]]; then
    amber "  unset    $name"
    printf '           %s\n' "$why"
    warn=1
  else
    green "  set      $name=$value"
  fi
}

echo "Required to boot"
require PLOTTED_DB_URL      "No database. Flyway cannot migrate and nothing starts."
require PLOTTED_DB_USER     "No database user."
require PLOTTED_DB_PASSWORD "No database password."
require PLOTTED_JWT_SECRET  "SecurityConfig refuses to start with the development default outside dev."

echo
echo "Required to do anything useful"
require TMDB_READ_ACCESS_TOKEN \
  "Without this the app boots, serves empty screens, and logs 'TMDB is not configured'
           when asked to seed. Free from themoviedb.org: Settings > API > Read Access Token."

echo
echo "Decisions worth making on purpose"
optional PLOTTED_DEMO_ENABLED \
  "Unset means no demo. Set true only if you want an unauthenticated endpoint that writes."
optional PLOTTED_SNAPSHOT_ENABLED \
  "Unset means no nightly availability history. Plot Armour needs months of it and a night
           not collected cannot be recovered -- but it only fires while an instance is alive,
           so on a scale-to-zero host set a minimum instance too or it silently never runs."
optional PLOTTED_SEED_ENABLED \
  "Unset means the catalogue stays empty. Set true for the first boot, then set it back:
           it re-pulls the whole seed on every cold start."

# A development secret that reaches production is worse than a missing one,
# because it boots. SecurityConfig catches the exact default; this catches the
# shape of the mistake more broadly.
if [[ -n ${PLOTTED_JWT_SECRET:-} ]]; then
  echo
  if (( ${#PLOTTED_JWT_SECRET} < 32 )); then
    red "  WEAK     PLOTTED_JWT_SECRET is only ${#PLOTTED_JWT_SECRET} characters."
    printf '           Generate one with: openssl rand -base64 48\n'
    fail=1
  fi
  if [[ $PLOTTED_JWT_SECRET == *dev* || $PLOTTED_JWT_SECRET == *change* || $PLOTTED_JWT_SECRET == *example* ]]; then
    red "  UNSAFE   PLOTTED_JWT_SECRET looks like a placeholder."
    fail=1
  fi
fi

echo
if (( fail )); then
  red "Not deployable. Fix the MISSING and UNSAFE lines above."
  exit 1
fi
if (( warn )); then
  amber "Deployable, with the unset options above behaving as described."
else
  green "Deployable."
fi
