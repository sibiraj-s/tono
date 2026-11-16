#!/usr/bin/env bash
# Copies the local release key into the GitHub repo's Actions secrets, so the
# Release APK workflow signs with the same key as the app on your phone.
# Usage: scripts/set-release-secrets.sh [owner/repo]   (defaults to the current repo)
set -euo pipefail
cd "$(dirname "$0")/.."

props=keystore.properties
[ -f "$props" ] || { echo "No $props here. Create the release key first (README › Release build)." >&2; exit 1; }
get() { grep -E "^$1=" "$props" | head -1 | cut -d= -f2-; }

store=$(get storeFile)
[ -f "$store" ] || { echo "Keystore '$store' not found." >&2; exit 1; }

repo=${1:-}

base64 < "$store" | tr -d '\n' | gh secret set KEYSTORE_BASE64 ${repo:+--repo "$repo"}
# printf: no trailing newline sneaks into the secret.
printf '%s' "$(get storePassword)" | gh secret set KEYSTORE_PASSWORD ${repo:+--repo "$repo"}
printf '%s' "$(get keyAlias)"      | gh secret set KEY_ALIAS ${repo:+--repo "$repo"}
printf '%s' "$(get keyPassword)"   | gh secret set KEY_PASSWORD ${repo:+--repo "$repo"}
echo "Done: the release workflow can now sign APKs."
