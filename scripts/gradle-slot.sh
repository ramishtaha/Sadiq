#!/usr/bin/env bash
# Serialise Gradle invocations across the machine. The box has 4 cores, 24 GB and NO swap.
# Usage: scripts/gradle-slot.sh ./gradlew :app:assembleDebug --console=plain
set -uo pipefail
SLOTS="${GRADLE_SLOTS:-3}"
LOCKDIR="${TMPDIR:-/tmp}/saduq-gradle-slots"
mkdir -p "$LOCKDIR"
# Pick a free slot with flock; wait up to 30 min.
for i in $(seq 1 "$SLOTS"); do
  if flock -n "$LOCKDIR/slot$i.lock" -c "$*"; then
    exit $?
  fi
done
# All busy: block on slot 1 (flock waits) then run.
flock "$LOCKDIR/slot1.lock" -c "$*"
