#!/usr/bin/env bash
# File-ownership gate. Usage: scripts/check-ownership.sh WP-nn [base-ref]
# Compares the changed paths since <base-ref> (default: tag wave-start, or HEAD if absent)
# against the paths allowed for that WP in docs/ownership.tsv.
set -uo pipefail
cd "$(dirname "$0")/.."
WP="${1:?usage: check-ownership.sh WP-nn [base-ref]}"
BASE="${2:-}"
if [ -z "$BASE" ]; then
  if git rev-parse -q --verify refs/tags/wave-start >/dev/null; then BASE="wave-start"; else BASE="HEAD"; fi
fi
OWNED="docs/ownership.tsv"
[ -f "$OWNED" ] || { echo "OWNERSHIP: no $OWNED"; exit 1; }

ALLOWED="$(awk -F'\t' -v wp="$WP" '$1==wp {print $2}' "$OWNED" | sort -u)"
if [ -z "$ALLOWED" ]; then echo "OWNERSHIP: $WP owns nothing in $OWNED"; exit 1; fi

CHANGED="$(git diff --name-only "$BASE" -- . 2>/dev/null; git ls-files --others --exclude-standard | sed 's/^/UNTRACKED /')"
VIOL=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  f="${f#UNTRACKED }"
  case "$f" in
    app/schemas/*|build/*|.gradle/*|*.lock) continue ;;
  esac
  if ! echo "$ALLOWED" | grep -qxF "$f"; then
    echo "OWNERSHIP: VIOLATION $WP touched $f"
    VIOL=1
  fi
done <<< "$CHANGED"

[ "$VIOL" -eq 0 ] && echo "OWNERSHIP: OK ($WP)"
exit "$VIOL"
