#!/usr/bin/env bash
#
# Plotted :: deploy the API to Cloud Run
#
# Everything scriptable about a first deploy, in the order that fails cheapest
# first. It refuses to start if the environment is wrong, so a bad deploy is
# caught in two seconds rather than after a five-minute image build.
#
#     export PLOTTED_DB_URL=... PLOTTED_DB_USER=... PLOTTED_DB_PASSWORD=...
#     export PLOTTED_JWT_SECRET="$(openssl rand -base64 48)"
#     export TMDB_READ_ACCESS_TOKEN=...
#     ops/deploy/deploy.sh
#
# WHAT THIS DOES NOT DO, ON PURPOSE
#
# It does not create accounts, enter payment details, or set a billing budget.
# Those need a person, and the billing alert in particular should be set before
# the first deploy rather than after -- the failure mode of a free tier is not a
# hard stop.
#
# It also does not run the database preflight. That is `ops/deploy/preflight.sql`
# and it belongs before this, against the database, once: if a managed Postgres
# refuses `btree_gist` the exclusion constraints silently do not exist, and the
# fix is never to fence them out.

set -euo pipefail

REGION=${PLOTTED_REGION:-northamerica-northeast1}
SERVICE=${PLOTTED_SERVICE:-plotted-api}
MAX_INSTANCES=${PLOTTED_MAX_INSTANCES:-3}
# Zero by default, which is free and means the nightly snapshot never fires.
# Set to 1 when you want Plot Armour's history to start accumulating.
MIN_INSTANCES=${PLOTTED_MIN_INSTANCES:-0}

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(cd "$here/../.." && pwd)

echo "== Checking the environment before building anything =="
"$here/check-env.sh"

echo
echo "== Preconditions =="
command -v gcloud >/dev/null || { echo "gcloud is not installed."; exit 1; }
gcloud config get-value project >/dev/null 2>&1 || { echo "No gcloud project set: gcloud config set project <id>"; exit 1; }
echo "  project  $(gcloud config get-value project 2>/dev/null)"
echo "  region   $REGION"
echo "  service  $SERVICE"
echo "  scaling  min=$MIN_INSTANCES max=$MAX_INSTANCES"

if [[ $MIN_INSTANCES == 0 ]]; then
  printf '\n\033[33m  Note: at min-instances=0 the nightly snapshot never runs.\033[0m\n'
  printf '  Spring @Scheduled only fires while an instance is alive, so on a\n'
  printf '  scale-to-zero service it is silently never invoked -- which looks\n'
  printf '  exactly like it running and finding nothing. Plot Armour needs that\n'
  printf '  history and a night not collected cannot be recovered.\n'
  printf '  Set PLOTTED_MIN_INSTANCES=1 when the budget allows.\n'
fi

echo
echo "== Deploying =="
# Secrets go as env vars here because Secret Manager needs the secrets created
# first, which is a person's job. Once they exist, prefer:
#   --set-secrets PLOTTED_JWT_SECRET=plotted-jwt-secret:latest,...
# An env var set on the command line is in your shell history and in the
# revision description, and both are readable by anyone with view access.
gcloud run deploy "$SERVICE" \
  --source "$root" \
  --region "$REGION" \
  --allow-unauthenticated \
  --min-instances "$MIN_INSTANCES" \
  --max-instances "$MAX_INSTANCES" \
  --set-env-vars "SPRING_PROFILES_ACTIVE=prod" \
  --set-env-vars "PLOTTED_DB_URL=$PLOTTED_DB_URL" \
  --set-env-vars "PLOTTED_DB_USER=$PLOTTED_DB_USER" \
  --set-env-vars "PLOTTED_DB_PASSWORD=$PLOTTED_DB_PASSWORD" \
  --set-env-vars "PLOTTED_JWT_SECRET=$PLOTTED_JWT_SECRET" \
  --set-env-vars "TMDB_READ_ACCESS_TOKEN=$TMDB_READ_ACCESS_TOKEN" \
  --set-env-vars "PLOTTED_DEMO_ENABLED=${PLOTTED_DEMO_ENABLED:-true}" \
  --set-env-vars "PLOTTED_SNAPSHOT_ENABLED=${PLOTTED_SNAPSHOT_ENABLED:-false}" \
  --set-env-vars "PLOTTED_SEED_ENABLED=${PLOTTED_SEED_ENABLED:-false}"

host=$(gcloud run services describe "$SERVICE" --region "$REGION" --format='value(status.url)')
echo
echo "== Verifying, in the order that tells you the most =="
"$here/verify.sh" "$host"
