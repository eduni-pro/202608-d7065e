#!/usr/bin/env bash
#
# Start the demo profile from nothing: tear the stack down, wipe every byte of on-disk state,
# rebuild the empty directory skeleton, and bring the ten containers back up on a new epoch.
#
#   usage: tools/fresh-demo.sh
#
# WHY THIS EXISTS, WHEN `make down && make up` IS TWO WORDS SHORTER
#
# Because `down -v` no longer leaves a clean slate. It removes named volumes, and this compose
# project declares none: the broker's spool, the journal's whole PostgreSQL cluster and the two
# ADR-0012 state documents are BIND MOUNTS under project/storage/, so they survive a teardown and
# the next run starts on top of them. Three things therefore carry across a `down -v` / `up`:
#
#   * storage/timescaledb/data — every earlier run's rows. tools/capture-demo-run.sh works around
#     this by scoping its assertions with `received_at >= run_start`; the machine this was written
#     on had 341 rows an unscoped query would have accepted as the current run's.
#   * storage/fault-monitor/state.json and storage/supervisor/state.json — the latch and the pinned
#     damper beliefs. The latch is the headline property: a confirmed fault stays confirmed until a
#     technician clears it, and ADR-0012 made it survive a restart on purpose. That is exactly what
#     a fresh demo must not inherit, or room A115 is red before the plant has drawn a breath.
#   * storage/mosquitto/data — the broker's spool. Wiped here for completeness rather than for
#     effect: every client connects with a clean session under a randomised id and nothing
#     publishes retained messages, so there is no session to restore. deploy/mosquitto/mosquitto.conf
#     says why persistence is on anyway.
#
# THE TWO TRAPS THIS SCRIPT EXISTS TO NOT FALL INTO
#
#   * REAL_EPOCH is declared `:?` in docker-compose.demo.yml, so compose refuses to interpolate
#     that file at all without it — `down`, `ps` and `logs` included. The value used for teardown
#     below is a throwaway; the one that reaches a container is generated in its own step, AFTER
#     the wipe, immediately before `up`, because the shell evaluates `date` before it execs compose
#     and an epoch taken earlier would be as stale as everything between it and `up`.
#   * --force-recreate, because BuildSim holds the entire plant IN MEMORY with no volume. `up -d`
#     recreates only the containers whose image or configuration changed, which never includes
#     BuildSim, so a demo following a demo computes its first ticks against the previous run's
#     damper positions while the monitor judges them against the commands on the bus. Those two
#     disagree, and the monitor is right to say so: RUNBOOK section 4.2 has the run where that cost
#     a confirmed STUCK_DAMPER at simulated 08:20 against an onset of 09:15.
#
# WHAT IT DELIBERATELY DOES NOT DO
#
#   * It does not build. Images and jars are the slow half and RUNBOOK section 4.1 keeps them out
#     of the live run; if the sources have moved since the last build, run that section first.
#     `up` will build an image that does not exist at all, and that build fails on a `target/`
#     emptied by `mvn clean` — the error names Docker and the cause is Maven.
#   * It does not touch a BuildSim started outside compose. The lab quickstart's `go run ./cmd
#     start` holds port 9090 and is not part of this project, so nothing here can stop it; that is
#     also why BUILDSIM_PORT defaults to 9091 here rather than to compose's own 9090.
#
# Every variable the demo reads is honoured if it is already exported — FAULT_SPEC, ZONE_COUNT,
# BUILDSIM_PORT, DASHBOARD_PORT — so the RUNBOOK section 6 variants work through this script too.
set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd -- "$HERE/.." && pwd)   # the project root, wherever the clone lives, never $PWD
cd "$ROOT"

die() { printf 'fresh-demo: %s\n' "$*" >&2; exit 1; }
say() { printf '==> %s\n' "$*"; }

[[ -f docker-compose.yml && -f docker-compose.demo.yml ]] \
  || die "no compose files beside $ROOT; this script must stay in project/tools/"

dc() { docker compose -f docker-compose.yml -f docker-compose.demo.yml "$@"; }

export BUILDSIM_PORT="${BUILDSIM_PORT:-9091}"
export DASHBOARD_PORT="${DASHBOARD_PORT:-8080}"
ROOMS="${ZONE_COUNT:-6}"

# ---- the guard on the one rm -rf in this repository's tooling ----------------------------------
# It is pointed at a path derived from the script's own location, and it still checks what it is
# about to delete rather than trusting that. storage/ is gitignored, so the only tracked files in
# it are the zero-byte .gitkeep placeholders that carry the empty directories into a clone. If that
# ever stops being true — someone commits a fixture, a seed, an exported transcript — this script
# would eat it, so it refuses instead and says what it found.
STORAGE="$ROOT/storage"
[[ -d $STORAGE && ! -L $STORAGE ]] || die "$STORAGE is not a directory"
UNEXPECTED=$(git -C "$ROOT" ls-files storage | grep -v '/\.gitkeep$' | grep -v '^storage/\.gitkeep$' || true)
if [[ -n $UNEXPECTED ]]; then
  die "storage/ holds tracked files that are not .gitkeep placeholders, and this script deletes the whole directory:
$UNEXPECTED"
fi

# ---- 1. stop everything this compose project is running ---------------------------------------
# The epoch here is discarded: `down` needs the variable to be present, not to be meaningful.
say "stopping the stack"
REAL_EPOCH=thrown-away-by-teardown dc down -v --remove-orphans

# ---- 2. wipe the on-disk state, then rebuild the skeleton empty --------------------------------
WAS=$(du -sh "$STORAGE" 2>/dev/null | cut -f1 || echo '?')
say "removing storage/ ($WAS)"
rm -rf "$STORAGE"

# git holds the empty-directory skeleton: checking the tracked .gitkeep files back out recreates
# storage/ and its subdirectories AND leaves the working tree clean, which matters because a
# container that recreates a bind-mount directory for itself leaves the placeholder staged as
# deleted and the next `git status` dirty for no reason anyone will remember.
git -C "$ROOT" checkout HEAD -- storage

# The bind-mount sources come from compose itself rather than from a list in this file, so a new
# service with a new volume needs no edit here. storage/timescaledb/data is the one directory git
# does not carry — nothing commits a placeholder into a PostgreSQL cluster's PGDATA — and it has to
# exist before `up`, or Docker creates it itself, as root, outside this user's ownership.
while read -r dir; do
  [[ -n $dir ]] && mkdir -p "$ROOT/${dir#./}"
done < <(grep -o '\./storage/[A-Za-z0-9_./-]*' docker-compose.yml | sort -u)

say "skeleton: $(find "$STORAGE" -type d -mindepth 1 | sed "s|$STORAGE/||" | sort | tr '\n' ' ')"

# ---- 3. one epoch for all seven project-authored processes, generated here and nowhere else ----
REAL_EPOCH="$(date -u +%Y-%m-%dT%H:%M:%SZ)"   # separately, so a failing date cannot be masked
export REAL_EPOCH
T0=$(date +%s)
say "starting on REAL_EPOCH=$REAL_EPOCH"
dc up -d --force-recreate

# ---- 4. verify, because a wiped PGDATA is the one thing here that can fail silently ------------
# PostgreSQL runs deploy/timescaledb/init.sql only when PGDATA is empty, which the wipe above
# guarantees — so this run either created the six hypertables or the demo will publish into a
# journal with nowhere to put it and every query will come back empty mid-demo. The expected table
# names are read out of init.sql, so they cannot drift from it.
TABLES=$(grep -o '^CREATE TABLE [a-z_]*' deploy/timescaledb/init.sql | awk '{print $3}' | sort)
WANT=$(printf '%s\n' "$TABLES" | wc -l | tr -d ' ')
IN_LIST=$(printf '%s\n' "$TABLES" | sed "s/.*/'&'/" | paste -sd, -)
GOT=$(dc exec -T timescaledb psql -U ventilation -d ventilation -t -A \
        -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ($IN_LIST);" \
        2>/dev/null | tr -d '[:space:]' || true)
[[ "$GOT" == "$WANT" ]] || die "the journal came up with ${GOT:-no} of $WANT tables; init.sql did not run against the empty PGDATA, so nothing this run publishes will be queryable"

RUNNING=$(dc ps --services --filter status=running | wc -l | tr -d ' ')
DECLARED=$(dc config --services | wc -l | tr -d ' ')
[[ "$RUNNING" == "$DECLARED" ]] || say "WARNING: $RUNNING of $DECLARED services running; see 'make logs'"

# The dashboard answering with every room is the cheapest proof that the chain from the plant
# through the broker is carrying data. It costs real seconds and real seconds are simulated
# minutes here, so it is bounded tightly and the offset it cost is printed below.
for _ in $(seq 1 20); do
  LIVE=$(curl -s --max-time 2 "http://127.0.0.1:$DASHBOARD_PORT/api/state" 2>/dev/null \
    | python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("rooms",[])))' 2>/dev/null || echo 0)
  [[ "$LIVE" == "$ROOMS" ]] && break
  sleep 1
done
[[ "$LIVE" == "$ROOMS" ]] || say "WARNING: the dashboard reports $LIVE of $ROOMS rooms after 20 s; the chain from the plant through the broker is not carrying every room yet"

# ---- 5. where the run now stands --------------------------------------------------------------
OFF=$(( $(date +%s) - T0 ))
cat <<SUMMARY

    REAL_EPOCH    $REAL_EPOCH
    simulated     2026-10-09T07:45:00Z + one minute per real second, now about +${OFF} min
    fault         ${FAULT_SPEC:-STUCK_DAMPER:A115:0.15:T+5400 (compose default)}
    rooms live    $LIVE of $ROOMS
    journal       $GOT of $WANT tables, empty
    containers    $RUNNING of $DECLARED running

    BuildSim 3D   http://127.0.0.1:$BUILDSIM_PORT/?layer=state&floor=level0
    Dashboard     http://127.0.0.1:$DASHBOARD_PORT

    Onset lands at +90 s real; verification above cost ${OFF} s of that. SUSPECT is around +160 s
    and the confirmation 61-72 simulated minutes after the onset. Tear down before +336 s: past
    there this profile confirms a second room with no fault in it (RUNBOOK section 4.8).
SUMMARY
