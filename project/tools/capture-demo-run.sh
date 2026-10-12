#!/bin/zsh
# One documented end-to-end demo-profile run with both UIs captured.
# Offsets are real seconds from REAL_EPOCH; simulated time is START_AT + offset minutes (TIME_SCALE=60).
#
# The run ASSERTS its outcome; see "the assertion" near the bottom. A transcript nothing checks
# is a transcript that cannot fail, and this one is cited as evidence.
#
# set -e is on, so every best-effort capture below carries `|| true` explicitly. That is not
# noise: a screenshot, a curl against a UI that is not up yet, a grep that matches nothing and a
# kill of a process that has already exited are all expected to fail during a run, and none of
# them is a reason to abandon it. What must stop the run is `dc up` failing, and that is the one
# command left unguarded.
set -euo pipefail
HERE=${0:a:h}
OUT=${CAPTURE_ROOT:-${TMPDIR:-/tmp}/d7065e-demo-capture}
mkdir -p "$OUT"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
cd "$HERE/.."   # the project root, wherever the clone lives

CAP="$OUT/run-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CAP/shots"; echo "$CAP" > "$OUT/CAPDIR"

# zsh does not word-split unquoted parameters, so compose flags must never live in a variable.
dc() { docker compose -f docker-compose.yml -f docker-compose.demo.yml "$@"; }
# A failing export query still writes its error into the transcript and the run continues, which
# is how the fan_power column mistake noted further down was found. The assertions use QV.
Q()  { dc exec -T timescaledb psql -U ventilation -d ventilation -A -F'|' -c "$1" 2>&1 || true; }
# One value, header and whitespace stripped. A query that fails and a query that matched nothing
# both come back empty, which every assertion reads as "not proven".
QV() { dc exec -T timescaledb psql -U ventilation -d ventilation -t -A -c "$1" 2>/dev/null \
         | tr -d '[:space:]' || true; }

# Two CDP endpoints on two warm profiles, so a dual-UI moment captures both near-simultaneously.
# A cold --user-data-dir hangs for minutes on this host; a warm one is ~1 s.
pkill -f "remote-debugging-port=922" 2>/dev/null || true; sleep 1
# Under set -e an aborted run would otherwise leave two headless browsers behind.
trap 'pkill -f "remote-debugging-port=922" 2>/dev/null || true' EXIT
"$CHROME" --headless=new --user-data-dir="$OUT/profile-buildsim" --no-first-run --no-default-browser-check \
  --remote-debugging-port=9222 --remote-allow-origins='*' >/dev/null 2>&1 &
"$CHROME" --headless=new --user-data-dir="$OUT/profile-dashboard" --no-first-run --no-default-browser-check \
  --remote-debugging-port=9223 --remote-allow-origins='*' >/dev/null 2>&1 &
for p in 9222 9223; do
  for i in $(seq 1 40); do curl -s --max-time 1 "http://127.0.0.1:$p/json/version" >/dev/null 2>&1 && break; sleep 0.5; done
done
BSHOT() { ( cd "$HERE" && CDP_PORT=9222 node ./screenshot.js "$1" "$2" 1600 1000 2500 ) >>"$CAP/shots.log" 2>&1 || true; }
DSHOT() { ( cd "$HERE" && CDP_PORT=9223 node ./screenshot.js "$1" "$2" 1500 1500 900 ) >>"$CAP/shots.log" 2>&1 || true; }

export BUILDSIM_PORT=9091
# compose refuses every subcommand without REAL_EPOCH, so export it, never prefix it.
export REAL_EPOCH="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
T0=$(date +%s)
off(){ echo $(( $(date +%s) - T0 )); }
at() { while [ "$(off)" -lt "$1" ]; do sleep 1; done; }
log(){ echo "[+$(off)s] $*" | tee -a "$CAP/RUN.log"; }
DASH=http://127.0.0.1:8080
BSIM=http://127.0.0.1:9091

{ echo "REAL_EPOCH=$REAL_EPOCH"; echo "T0_epoch=$T0"; echo "T0_local=$(date)";
  echo "SEED=42 TIME_SCALE=60 START_AT=2026-10-09T07:45:00Z (compose defaults)";
  echo "FAULT_SPEC=${FAULT_SPEC:-STUCK_DAMPER:A115:0.15:T+5400} (onset simulated 09:15 = +90 s real at the default)";
  echo "BUILDSIM_PORT=9091"; echo "commit=$(git rev-parse HEAD)";
  echo "java=$(java -version 2>&1 | head -1)";
  echo "chrome=$(curl -s http://127.0.0.1:9222/json/version | python3 -c 'import json,sys;print(json.load(sys.stdin).get("Browser"))')"; } > "$CAP/02-run-config.txt"

# The room this run will be judged on, read from the same variable the compose overlay reads so
# the assertions near the bottom cannot drift from the scenario that was actually injected. It
# reaches a query, so it is checked here, at the one boundary it enters the script by.
FAULT_ZONE=$(echo "${FAULT_SPEC:-STUCK_DAMPER:A115:0.15:T+5400}" | cut -d: -f2)
case "$FAULT_ZONE" in
  (""|*[^A-Za-z0-9]*) echo "FAULT_SPEC names no usable room: ${FAULT_SPEC:-}" >&2; exit 2 ;;
esac

log "starting stack"
dc up -d > "$CAP/03-up.log" 2>&1
dc ps > "$CAP/03-ps.txt" 2>&1 || true

( while [ "$(off)" -lt 325 ]; do o=$(off)
    curl -s --max-time 2 "$DASH/api/state" > "$CAP/state-$(printf '%04d' $o).json" 2>/dev/null || true
    sleep 1; done ) &
POLL=$!
dc exec -T mosquitto mosquitto_sub -h 127.0.0.1 -t 'faults/+' -t 'strategy/+' -t 'residuals/+' -v \
  > "$CAP/mqtt-bus.log" 2>&1 &
MQTT=$!
dc logs -f --timestamps > "$CAP/containers.log" 2>&1 &
LOGS=$!
log "pollers up"

# The instant this run began, taken from the JOURNAL'S OWN CLOCK. The assertions at the bottom are
# scoped by received_at against it, and not by decided_at, for two reasons that each make an
# unscoped query worthless: storage/timescaledb/data is a bind mount, so the journal survives
# `down -v` and carries every earlier run's rows; and decided_at is SIMULATED time from a fixed
# START_AT, so it is the same instant in every run from this overlay. Without this bound a
# confirmation from an earlier run would satisfy the assertion and it would pass whatever this run
# did — and the journal on the machine this was written on held 341 such rows.
#
# Waiting for the database happens here, after the pollers are backgrounded, so a slow first boot
# costs the bound a few seconds rather than costing the dashboard snapshots their first seconds.
# The scenario's confirmation is around +120 s, so anything resolved during startup bounds it.
RUN_START=""
for i in $(seq 1 30); do
  RUN_START=$(QV "SELECT to_char(now() AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US')||'Z';")
  [ -n "$RUN_START" ] && break; sleep 1
done
log "judging room $FAULT_ZONE from journal instant ${RUN_START:-unavailable}"

# ---- C1 — the baseline. Wait for six live rooms rather than trusting a fixed offset. ----
at 20
for i in $(seq 1 25); do
  n=$(curl -s --max-time 2 "$DASH/api/state" | python3 -c "import json,sys;d=json.load(sys.stdin);print(len(d.get('rooms',[])))" 2>/dev/null || echo 0)
  [ "$n" = "6" ] && break; sleep 1
done
log "C1 baseline (rooms=$n)"
DSHOT "$DASH" "$CAP/shots/C1-dashboard.png" & PD=$!
BSHOT "$BSIM/?layer=state&floor=level0" "$CAP/shots/C1-buildsim-state.png" & PB=$!
wait $PD $PB || true
curl -s "$DASH/api/state" > "$CAP/C1-state.json" || true
DSHOT "$DASH/q/health/ready" "$CAP/shots/C1-health-ready.png"

# ---- +80 s  simulated 09:05 — the inner loop working, before it is seen failing ----
at 80; log "pre-onset commands"
Q "SELECT zone, count(*), to_char(min(issued_at),'HH24:MI') first, to_char(max(issued_at),'HH24:MI') last FROM commands GROUP BY zone ORDER BY zone;" > "$CAP/04-commands-pre-onset.txt"

# ---- +92 s  simulated 09:15 — onset. Invisible in both UIs; the log is the ground truth ----
at 92; log "onset window"
dc logs physics-simulator 2>&1 | grep -i -E "fault|inject|stuck|onset" | tail -20 > "$CAP/05-onset.log" || true

# ---- C2  +135 s  simulated 10:00 — the detector deliberately NOT firing ----
at 135; log "C2 detector not firing"
DSHOT "$DASH" "$CAP/shots/C2-dashboard.png"
curl -s "$DASH/api/state" > "$CAP/C2-state.json" || true

# ---- C3  +158 s  simulated 10:23 — CONFIRMED / STUCK_DAMPER, plus the work order ----
at 158; log "C3 confirmed"
DSHOT "$DASH" "$CAP/shots/C3-dashboard.png" & PD=$!
BSHOT "$BSIM/?layer=state&floor=level0" "$CAP/shots/C3-buildsim-state.png" & PB=$!
wait $PD $PB || true
curl -s "$DASH/api/state" > "$CAP/C3-state.json" || true
curl -s "$BSIM/api/alerts" > "$CAP/C3-alerts.json" || true

# ---- C4  +190 s  simulated 10:55 — the supervisor acting; the other five untouched ----
at 190; log "C4 compensating"
DSHOT "$DASH" "$CAP/shots/C4-dashboard.png" & PD=$!
BSHOT "$BSIM/?layer=state&floor=level0" "$CAP/shots/C4-buildsim-state.png" & PB=$!
wait $PD $PB || true
curl -s "$DASH/api/state" > "$CAP/C4-state.json" || true

# ---- +235 s  the four-layer sweep. Only possible since the room-layer collection fix. ----
at 235; log "four-layer sweep"
for L in co2 residual cusum state; do
  BSHOT "$BSIM/?layer=$L&floor=level0" "$CAP/shots/L-$L.png"
done
curl -s "$BSIM/api/room-layers" > "$CAP/06-room-layers.json" || true
python3 -c "import json;d=json.load(open('$CAP/06-room-layers.json'));print('layers on the wire:',[x['id'] for x in d],'zones each:',[len(x['values']) for x in d])" >> "$CAP/RUN.log" 2>&1 || true

# ---- C5  +300 s  simulated 12:45 — the pair to C1: residual back near zero, A115 still red ----
at 300; log "C5 the argument"
DSHOT "$DASH" "$CAP/shots/C5-dashboard.png" & PD=$!
BSHOT "$BSIM/?layer=state&floor=level0" "$CAP/shots/C5-buildsim-state.png" & PB=$!
wait $PD $PB || true
curl -s "$DASH/api/state" > "$CAP/C5-state.json" || true
CASE=$(python3 -c "
import json,re
s=open('$CAP/C5-state.json').read()
m=re.findall(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}',s)
print(m[0] if m else '')" 2>/dev/null || true)
echo "caseId=$CASE" >> "$CAP/RUN.log"
[ -n "$CASE" ] && curl -s "$DASH/api/cases/$CASE" > "$CAP/07-case.json" && \
  python3 -c "
import json;d=json.load(open('$CAP/07-case.json'))
ev=d.get('events',d if isinstance(d,list) else [])
print('case events:',len(ev))" >> "$CAP/RUN.log" 2>&1 || true

# ---- +312 s  export everything. The journal has no volume and dies with down -v. ----
at 312; log "exporting"
Q "SELECT to_char(decided_at,'HH24:MI') t, zone, state, classification FROM (SELECT *, lag(state) OVER (PARTITION BY zone ORDER BY decided_at) prev, lag(classification) OVER (PARTITION BY zone ORDER BY decided_at) pc FROM decisions) d WHERE prev IS DISTINCT FROM state OR pc IS DISTINCT FROM classification ORDER BY decided_at, zone;" > "$CAP/08-state-timeline.txt"
Q "SELECT 'readings' k, count(*) FROM readings UNION ALL SELECT 'decisions', count(*) FROM decisions UNION ALL SELECT 'commands', count(*) FROM commands UNION ALL SELECT 'strategies', count(*) FROM strategies UNION ALL SELECT 'fan_power', count(*) FROM fan_power;" > "$CAP/09-totals.txt"
Q "SELECT DISTINCT ON (zone) zone, to_char(decided_at,'HH24:MI') t, state, classification FROM decisions ORDER BY zone, decided_at DESC;" > "$CAP/10-final-states.txt"
Q "SELECT DISTINCT ON (zone) zone, to_char(observed_at,'HH24:MI') t, round(ppm::numeric,1) ppm FROM readings ORDER BY zone, observed_at DESC;" > "$CAP/11-final-readings.txt"
# fan_power has no speed column: the AHU publishes watts, and the speed is the supervisor's
# command, which lands in strategies. Asking for speed here failed on every earlier run too,
# which is why no transcript before this one carried a fan figure.
Q "SELECT to_char(observed_at,'HH24:MI') t, ahu, round(watts::numeric,1) watts FROM fan_power ORDER BY observed_at DESC LIMIT 5;" > "$CAP/12-fan.txt"
# strategies is keyed on changed_at; decided_at is the decisions table's column.
Q "SELECT to_char(changed_at,'HH24:MI') t, zone, mode, trimmed_position, case_id FROM strategies ORDER BY changed_at LIMIT 8;" > "$CAP/13-strategies.txt"
Q "SELECT zone, count(*) FROM commands GROUP BY zone ORDER BY zone;" > "$CAP/14-commands.txt"
dc ps > "$CAP/15-ps-final.txt" 2>&1 || true

# ---- the assertion. Everything above is a transcript; this is the part that can fail. ----
# It runs before teardown because `dc down -v` takes the container the queries go through with it.
# Two claims, both about the room the fault was injected into, both scoped to this run:
#   1. the detector reached a latched diagnosis for it  — CONFIRMED or COMPENSATING;
#   2. the supervisor then acted on that diagnosis      — DEGRADED or COMPENSATING.
# The second is a separate process reached through the broker, so together they assert the whole
# chain the demo is shown for rather than only that the monitor said something.
#
# What is reported and NOT asserted is how many OTHER rooms latched. Within the scenario window
# that is zero and it is the R-7 claim, but docker-compose.demo.yml documents a second room
# confirming later from an un-closed flow belief, so pinning it here would make the script fail
# for a known and recorded defect instead of for a broken demo.
log "asserting the outcome"
FAILURES=()
if [ -z "$RUN_START" ]; then
  FAILURES+=("the journal never answered, so nothing about this run could be checked")
  LATCHED=""; ACTED=""; OTHERS="?"
else
  LATCHED=$(QV "SELECT count(*) FROM decisions WHERE zone = '$FAULT_ZONE' AND state IN ('CONFIRMED','COMPENSATING') AND received_at >= '$RUN_START'::timestamptz;")
  ACTED=$(QV "SELECT count(*) FROM strategies WHERE zone = '$FAULT_ZONE' AND mode IN ('DEGRADED','COMPENSATING') AND received_at >= '$RUN_START'::timestamptz;")
  OTHERS=$(QV "SELECT count(DISTINCT zone) FROM decisions WHERE zone <> '$FAULT_ZONE' AND state IN ('CONFIRMED','COMPENSATING') AND received_at >= '$RUN_START'::timestamptz;")
  [ "${LATCHED:-0}" -gt 0 ] 2>/dev/null || FAILURES+=("expected at least one decisions row for the injected room $FAULT_ZONE with state CONFIRMED or COMPENSATING, written since $RUN_START; found ${LATCHED:-none}. The run captured no diagnosis, so its transcript is not evidence that the detector works.")
  [ "${ACTED:-0}" -gt 0 ] 2>/dev/null || FAILURES+=("expected at least one strategies row for $FAULT_ZONE with mode DEGRADED or COMPENSATING, written since $RUN_START; found ${ACTED:-none}. The monitor's diagnosis reached no supervisor, so the chain is broken after the broker.")
fi
{ echo "run_start=$RUN_START"; echo "injected_room=$FAULT_ZONE";
  echo "latched_decisions_for_injected_room=${LATCHED:-none} (expected >= 1)";
  echo "supervisor_strategy_changes_for_injected_room=${ACTED:-none} (expected >= 1)";
  echo "other_rooms_latched=$OTHERS (reported, not asserted; see the script)";
  echo "failures=${#FAILURES[@]}"; } > "$CAP/18-assertions.txt"
cat "$CAP/18-assertions.txt" >> "$CAP/RUN.log"

# ---- teardown. Hard stop before +336 s, the documented spurious A116A confirmation. ----
log "teardown"
kill $POLL $MQTT $LOGS 2>/dev/null || true
dc down -v > "$CAP/16-down.log" 2>&1 || true
pkill -f "remote-debugging-port=922" 2>/dev/null || true
{ echo "REAL_EPOCH=$REAL_EPOCH"; echo "final real offset=$(off)s";
  echo "nominal simulated end = 07:45 + $(off) min"; } > "$CAP/17-drift.txt"
log "done; artefacts in $CAP"
ls "$CAP/shots" >> "$CAP/RUN.log" || true

# ---- the verdict. After teardown, so a failed run still leaves nothing of itself behind. ----
if [ ${#FAILURES[@]} -eq 0 ]; then
  log "PASS: $FAULT_ZONE reached a latched diagnosis and the supervisor acted on it; $OTHERS other rooms latched"
  exit 0
fi
for f in "${FAILURES[@]}"; do log "FAIL: $f"; done
log "FAIL: ${#FAILURES[@]} assertion(s) failed; transcript kept in $CAP"
exit 1
