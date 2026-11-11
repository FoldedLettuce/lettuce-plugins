#!/usr/bin/env bash
# Downloads the newest release jars from GitHub and swaps them into a server's plugins folder.
#
#   deploy/update-plugins.sh <plugins-dir> <Plugin> [<Plugin> ...]
#   e.g. deploy/update-plugins.sh /var/lib/pterodactyl/volumes/<uuid>/plugins LettuceGames LettuceRestart
#
# Needs: curl, jq, and a token that can read the repo (fine-grained PAT, "Contents: read").
# Put it in deploy/.env (never committed):   GITHUB_TOKEN=ghp_xxx
# Run it while the server is stopped, or let LettuceRestart restart the server afterwards.
set -euo pipefail
cd "$(dirname "$0")"
[ -f .env ] && set -a && . ./.env && set +a
REPO="${REPO:-FoldedLettuce/lettuce-plugins}"
: "${GITHUB_TOKEN:?Set GITHUB_TOKEN in deploy/.env}"
DIR="${1:?plugins dir}"; shift
[ $# -gt 0 ] || { echo "List at least one plugin name"; exit 1; }

REL=$(curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" \
      "https://api.github.com/repos/$REPO/releases/latest")
TAG=$(echo "$REL" | jq -r .tag_name)
echo "Latest release: $TAG"

for P in "$@"; do
  ASSET=$(echo "$REL" | jq -r --arg p "$P-" '.assets[] | select(.name | startswith($p)) | "\(.id) \(.name)"' | head -n1)
  [ -n "$ASSET" ] || { echo "No $P jar in release $TAG"; exit 1; }
  ID=${ASSET%% *}; NAME=${ASSET#* }
  if [ -f "$DIR/$NAME" ]; then echo "$NAME already installed"; continue; fi
  curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/octet-stream" \
       -o "/tmp/$NAME" "https://api.github.com/repos/$REPO/releases/assets/$ID"
  rm -f "$DIR/$P"-*.jar
  mv "/tmp/$NAME" "$DIR/$NAME"
  echo "Installed $NAME"
done
