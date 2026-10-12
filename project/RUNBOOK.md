# Runbook — building, running and demonstrating the system

Everything in this file was executed on 2026-10-05 against `main` at `95113b6`, on macOS
(Darwin 27.0.0), OpenJDK 25.0.3 (Corretto), Maven 3.9.12, Docker 29.8.2. Commands that were not
run are marked as such. Where a number appears it was measured, not estimated.

The companion documents are [`README.md`](README.md) for what the system *is*,
[`REQUIREMENTS.md`](REQUIREMENTS.md) for R-1…R-7, `report/report.pdf` for the written argument,
and `aiadocs/results/T-*.md` for the evidence behind each number.

---

## 1. Plan: pick the right way to run it

There are three, and they prove different things. Running the wrong one for the question being
asked is the most common waste of time here.

| | What it is | What it proves | Needs Docker | Time |
|---|---|---|---|---|
| **A. Evidence harness** | The control classes the containers run, driven in-process against a simulated clock. `SystemHarness` wires four of them into one JVM; `evaluation` depends on five reactor modules, not all nine | R-1…R-7, with CSVs and printed tables. **This is where the report's numbers come from** | no | 157 s |
| **B. Base stack** | Ten services at real time, one simulated second per real second | that the system runs as seven project-authored processes over MQTT, REST and TimescaleDB — distribution, not behaviour | yes | hours to show anything |
| **C. Demo profile** | The same ten services at 60× | the whole fault lifecycle, on screen, in about three and a half minutes | yes | ~4 min after images |

**For an oral defence: build the images once in advance, then show C, and keep A's output open in
a second window as the evidence behind it.** B is the one to mention rather than run; its only
advantage over C is that it needs no `REAL_EPOCH`, and it would take a working day to reach the
moment C reaches in ninety seconds.

### Prerequisites, and the one conflict that will bite

```bash
java -version      # must be 25; the build sets maven.compiler.release=25
mvn -v
docker info --format '{{.ServerVersion}}'
```

The lab quickstart runs a BuildSim outside Compose on **port 9090**, and Compose wants the same
port. Check before you start, and if something already holds it, publish Compose's BuildSim
somewhere else with `BUILDSIM_PORT=9091` — every command below already does:

```bash
lsof -nP -iTCP:9090 -sTCP:LISTEN     # empty means 9090 is free
for p in 8080 1883 5432 9091; do lsof -nP -iTCP:$p -sTCP:LISTEN; done
```

Compose also publishes 1883 (Mosquitto), 5432 (TimescaleDB) and 8080 (dashboard), all bound to
`127.0.0.1`. `DASHBOARD_PORT` moves the last one.

---

## 2. Option A — the evidence harness (start here)

No containers, no broker, no database. The control classes the containers run, driven directly
against a simulated clock, which is why a seven-day replay and twenty injection runs take minutes
instead of weeks.

```bash
cd project
mvn -o clean package -DskipTests          # all ten reactor projects
java -jar evaluation/target/evaluation-1.0.0-SNAPSHOT.jar all > /tmp/harness.txt 2>&1
less /tmp/harness.txt
```

Or one experiment at a time:

```bash
java -jar evaluation/target/evaluation-1.0.0-SNAPSHOT.jar T-24 T-26
```

Valid names are `T-15 T-24 T-25 T-26 T-27 T-28 T-29 T-34b T-35 T-22`, or `all`. Each writes one
CSV into `evaluation/results/` and prints the table the report quotes.

The full set took **350 s**: T-15 52.2 s, T-24 8.6 s, T-25 15.5 s, T-26 4.8 s, T-27 0.3 s,
**T-28 121.6 s**, **T-29 96.3 s**, T-34b 9.2 s, T-35 41.5 s, T-22 0.0 s. T-28's scaling sweep and
T-29's learned contender are nearly two thirds of the run between them, so naming the other eight
explicitly turns a six-minute wait into a two-minute one when neither is the question.

> **Redirect to a file. Never pipe this jar into `head` or `grep`.** The pipe closes early, the
> JVM takes SIGPIPE, the run dies part-way through — and still exits 0. The output looks complete
> and is not. This has produced a wrong number in this project before.

The unit and integration suite is separate:

```bash
mvn -o test                                # read the per-module summary lines, not the .txt files
```

> `@Nested` test classes make Surefire's per-file `.txt` reports double-count. Maven's own
> per-module summary line is the authoritative count.

> After changing any interface `static final` primitive — anything in `Topology`,
> `MonitorParameters` and friends — you **must** `mvn clean`. `javac` inlines those constants into
> every caller, so an incremental rebuild leaves callers using the old value and the change
> appears to do nothing.

`make experiments`, `make test` and `make verify` wrap the above.

### 2.1 R-5's SQL needs a real database

`TimescaleJournalAdapter` is native PostgreSQL — a `?::jsonb` cast for a decision's evidence,
`BETWEEN` over `timestamptz` — so no in-memory substitute executes the same statements, and
`TimescaleJournalIntegrationTest` is the only place R-5's SQL runs at all: the seven inserts, the
per-zone window scans, the case window, and the one-hour evidence lookback that decides which
readings and residuals belong to a case.

```bash
make journal-test        # starts the journal, runs the test, removes the container again
```

Or, with the stack already up — the whole demo or just `docker compose up -d timescaledb` — plain
`mvn -o test` runs it, because the test uses a journal it finds and only starts one it does not.
With no journal and no `-Dr5.journal.autostart=true`, its eight tests report **skipped** with the
reason, and `mvn -o clean test` is green on a machine with no Docker at all.

Starting a container is deliberately not what `make test` does: the suite's value is that it runs in
about ten seconds with nothing installed, and a default that waits on a database — or downloads
2.4 GB of image under `mvn -o` — would trade that away.

It is safe to run against a live stack. Each test writes under a zone id and a case id generated for
that run, so its queries match only its own rows, every row carries one `source` per run, and the
run deletes them afterwards. It creates and drops no table.

| Override | Default |
|---|---|
| `-Dr5.journal.url=` | `jdbc:postgresql://localhost:5432/ventilation` |
| `-Dr5.journal.user=` / `-Dr5.journal.password=` | `ventilation` / `ventilation` |
| `-Dr5.journal.autostart=true` | `false` — skip rather than start a container |

---

## 3. Option B — the base stack at real time

```bash
cd project
mvn -o package -DskipTests
BUILDSIM_PORT=9091 docker compose build
BUILDSIM_PORT=9091 docker compose up -d
BUILDSIM_PORT=9091 docker compose ps
```

Ten services come up: three off-the-shelf (`buildsim`, `mosquitto`, `timescaledb`) and seven
written here (`physics-simulator`, `co2-sensor`, `damper-actuator`, `dcv-controller`,
`fault-monitor`, `supervisor`, `dashboard`).

- BuildSim 3D view — <http://127.0.0.1:9091/?layer=state&floor=level0> (or `co2`, `residual`, `cusum`)
- Dashboard — <http://127.0.0.1:8080>

Inject a fault without editing a file. The grammar is `KIND:TARGET:MAGNITUDE:ONSET`, several
separated by `;`, `#` starting a comment:

```bash
FAULT_SPEC='STUCK_DAMPER:A115:0.15:T+1800' BUILDSIM_PORT=9091 docker compose up -d
```

`KIND` is `STUCK_DAMPER`, `BLOCKED_FILTER` or `SENSOR_DRIFT`. `TARGET` is a room, or `PLANT` for
the plant-wide `BLOCKED_FILTER`. `ONSET` is `T+<seconds>` from the start of the run or an ISO-8601
instant. What `MAGNITUDE` means is each fault's own business — see `InjectedFault`.

Teardown is `BUILDSIM_PORT=9091 docker compose down -v`.

---

## 4. Option C — the demo, step by step

One real second carries one simulated minute, and every loop period is divided by the same 60, so
each loop still fires at the same point in *simulated* time. Nothing about a model, a threshold or
a decision rule changes.

### 4.1 Before the audience is in the room

Build the images. This is slow and has nothing to show, so do not do it live.

```bash
cd project
mvn -o clean package -DskipTests
export BUILDSIM_PORT=9091
export REAL_EPOCH="$(date -u +%Y-%m-%dT%H:%M:%SZ)"   # any value; see below
docker compose -f docker-compose.yml -f docker-compose.demo.yml build
```

Two things about that snippet, both learned by getting them wrong.

`package`, not `test` or `install`: the Dockerfiles copy jars that Maven has already built, so
they need the `package` phase to have run. If you have just run `mvn test`, or anything with
`clean` after your last `package`, `target/` holds no jars and the image build fails with
`failed to calculate checksum ... "/target/physics-simulator-1.0.0-SNAPSHOT.jar": not found`.
That error names Docker and sounds like a Docker problem; it is a Maven one.

`REAL_EPOCH` is exported here even though building an image has nothing to do with the clock.
The demo profile declares the variable with `:?`, which makes Compose refuse to *interpolate the
file at all* without it — so `build`, `ps`, `logs` and `down` all fail, not just `up`. The value
used at build time is irrelevant and is thrown away; section 4.2 generates the one that matters.

### 4.2 Start it

```bash
cd project
export BUILDSIM_PORT=9091
export REAL_EPOCH="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d
```

Three things about `REAL_EPOCH`, all of which were confirmed by running it:

1. **Generate it in the shell, in its own step, before `up`.** It anchors all seven project
   processes to one instant so they share a timeline. Taking it on the same line as `up --build`
   would make it as stale as the image build is long.
2. **`export` it, do not prefix it.** Compose declares it `:?`, so it refuses to run *any* command
   on this profile without it — including `ps`, `logs`, `down` and, least obviously, **`build`**. A `build` run without it fails on interpolation before it builds anything, and through a pipe it can still exit 0, so the next `up` quietly starts containers from stale images. Export a throwaway value for the build and the real one before `up`. Prefix it to `up` only and your
   next command fails with `required variable REAL_EPOCH is missing a value`, mid-demo.
3. It is why the simulated clock does not depend on how long the containers took to start: the
   simulator back-fills the skipped span on its first tick, in one-second steps, re-evaluating the
   occupancy schedules as it goes.

**Recreate BuildSim too, and do not take this one on trust.** `up -d` recreates only the
containers whose image or configuration changed, which is the seven project services — BuildSim,
the broker and the journal keep running, and for the broker and the journal that is what you
want. For BuildSim it is not. It holds the whole plant **in memory**, with no volume, so a
BuildSim left running from a previous demo still holds that demo's damper positions, and the
simulator reads damper state from it on every tick. The first ticks of the new run therefore
compute air against the *old* run's dampers while the monitor judges it against the commands it
can see on MQTT, and those two disagree.

Measured, on a run started this way: A115 was **confirmed `STUCK_DAMPER` at simulated 08:20**,
with an injected onset of 09:15 — a diagnosis 55 simulated minutes before the fault it names
existed. It is tempting to file that as a false positive and reach for section 5. It is not one.
BuildSim's own record gave it away: `GET /api/actuators/A115-damper` carried a timestamp 22
seconds *before* the new run's `REAL_EPOCH`, while A145's was from inside the run. A115's damper
had not been written in the new run at all, so the monitor was reading a genuine disagreement
between command and air, and naming it correctly. The plant was inconsistent; the diagnosis was
right.

So add `--force-recreate` when a demo follows another demo:

```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --force-recreate
```

Re-run with that and the same scenario behaves: all six rooms stay `HEALTHY` through 09:17, the
damper bites at 09:15, `SUSPECT` at 10:14, confirmed `STUCK_DAMPER` at 10:21 — 66 simulated
minutes, inside the 61-72 the six runs on record span. The journal keeps its rows either way,
which is why queries there are scoped by `received_at`.

**Or run one command instead of these four.** `tools/fresh-demo.sh` is this section with the
`--force-recreate` rule, the epoch ordering and the on-disk wipe already in it:

```bash
tools/fresh-demo.sh
```

It tears the stack down, deletes everything under `storage/` — the journal's cluster, the broker's
spool and the two `state.json` documents, none of which `down -v` touches — rebuilds the empty
skeleton, generates the epoch in its own step and brings the stack up force-recreated. It then
checks that the emptied `PGDATA` re-ran `init.sql`, because a journal with no tables is a demo
whose every query comes back empty, and it prints where the run stands against the +90 s onset. It
does not build: section 4.1 stays a separate step for the reason given there.

### 4.3 The four screens

| | Where | What it shows |
|---|---|---|
| Dashboard | <http://127.0.0.1:8080> | six rooms: CO₂, residual, CUSUM, monitor state, classification, control mode. Refreshes itself every 5 s |
| BuildSim 3D | <http://127.0.0.1:9091/?layer=state&floor=level0> | the building, coloured by monitor state. **Show this one.** Swap `state` for `co2`, `residual` or `cusum` to switch layers |
| Work orders | `curl -s http://127.0.0.1:9091/api/alerts` | what the supervisor told the operator, in prose |
| Live bus | `docker compose … exec mosquitto mosquitto_sub -h 127.0.0.1 -t 'faults/+' -v` | the fault events themselves as they are published |

Other useful subscriptions: `commands/+/damper` (the inner loop acting), `residuals/+`,
`strategy/+`, `readings/+/co2`.

**All four layers work now, and until recently only one did.** `PUT /api/room-layers` replaces the
whole collection, and the dashboard used to publish its four layers one at a time, so each erased
the one before it and only `state` — last in the enum — ever arrived. `?layer=co2`, `?layer=residual`
and `?layer=cusum` selected nothing, silently, because every individual PUT returned 200. Confirm
all four are present before demoing a layer other than `state`:

```bash
curl -s http://127.0.0.1:9091/api/room-layers | python3 -c \
  'import json,sys; d=json.load(sys.stdin); print(len(d), [x["id"] for x in d])'
# expect: 4 ['co2', 'residual', 'cusum', 'state']
```

`state` is still the layer to show, for the reason report §12 gives: it is the only one that keeps a
latched room hot after compensation has pulled its residual back. On `co2` a compensated A115 reads
*lower* than most of its healthy neighbours, and on `residual` it is clamped to the bottom of the
scale — which is also where a stale room is painted, so those two are indistinguishable there.

### 4.4 The timeline, as measured

From the run recorded in **`evaluation/results/T-23-demo-run-261416e.txt`** — commit `261416e`,
`REAL_EPOCH=2026-10-06T23:46:33Z`, default `FAULT_SPEC` (`STUCK_DAMPER:A115:0.15:T+5400`),
`START_AT` 2026-10-09T07:45, with the seven images rebuilt from that commit before the stack came
up. Simulated time is `07:45 + (real seconds elapsed)` minutes, so the real-second column is just
the simulated clock minus 07:45. **Every figure in this section is readable out of that file**; the
transcript section each one comes from is named beside the table.

| Real | Simulated | What happens |
|---|---|---|
| +0 s | 07:45 | empty building, every room near the 420 ppm outdoor concentration, dampers on the 0.15 floor |
| +9 s | 07:54 | the monitor's first decisions: **all six rooms `HEALTHY` / `NONE`**, and the other five stay there for the whole run |
| +20 s | 08:05 | A115 at **516.1 ppm**, residual **+46.3 ppm**, CUSUM **0.0** — nothing is wrong yet |
| +90 s | 09:15 | the injected stuck damper bites: A115's damper jams at 0.15 while the controller keeps asking for more. Invisible in both UIs; the simulator's log is the ground truth |
| +135 s | 10:00 | residual **−13.2 ppm** and CUSUM still **0.0**. The CUSUM is driven by a *different series* from the one this table and the dashboard show — measured minus the plausibility **ceiling**, not the residual (report §7.3) |
| +158 s | 10:23 | residual **+172.0 ppm**, CUSUM **72.0 ppm** — climbing, still under the 90 ppm suspicion threshold |
| +160 s | 10:25 | **A115 `SUSPECT`** — upper-arm CUSUM **124.5 ppm** passes the suspicion threshold of 90 ppm, on a **+220.0 ppm** residual |
| +162 s | 10:27 | **A115 `CONFIRMED` as `STUCK_DAMPER`** — CUSUM **294.3 ppm** against `h` = 150 ppm, held two consecutive cycles; the supervisor sets `DEGRADED` in the same simulated minute |
| +193 s | 10:58 | A115 `COMPENSATING`; the five other rooms never leave `HEALTHY` |
| +300 s | 12:45 | A115 held at **443.4 ppm**, the other five between **437.0 and 670.4 ppm**, all five with CUSUM **0.0**; AHU at **136.2 W** |

The state transitions come from the transcript's *State timeline*; the per-moment CO₂, residual,
CUSUM and wattage from *A115 at the moments section 4.4 quotes*; the five healthy rooms at +300 s
from *The five uninjected rooms at +300 s*.

> **This table is one run, not the run.** The containerised demo profile is **not reproducible**,
> even on identical `SEED`, `TIME_SCALE` and `START_AT`. Two runs taken half an hour apart on this
> same commit confirmed A115 at simulated **10:22** and **10:27**, and six runs now on record span
> **10:16 to 10:27**. The sensor model draws once per Euler sub-step and the sub-step count follows
> *real* elapsed time, so host scheduling decides how many draws land inside a simulated minute.
> Every offset in the table above can therefore shift by a few seconds and every simulated time by
> a few minutes; if your run confirms at 10:16 rather than 10:27, nothing is wrong. The **offline**
> harness in `evaluation/` *is* reproducible bit for bit, and that is where every requirement
> number comes from. `evaluation/results/T-23-demo-run.txt` is an earlier full trace, on `fee6748`.

**Detection delay: 72 simulated minutes** from onset at 09:15 to confirmation at 10:27. That is
measured from onset, so compare it with T-24's ceiling-sweep arm, which also counts from onset and
gives a **median of 180 min**; this run is a fast draw rather than a typical one. R-1's own figure
is a different quantity — T-24's closed-loop arm counts from the first *ignored command*, not from
onset, and reports **median 74 min, p95 257 min**. Do not mix the two.

Journal totals: **1806 readings, 15 decisions, 154 commands** of which **92 on A115**, and **one**
strategy change (A115 to `DEGRADED` at 10:27). The whole arc fits in about five minutes, but
everything worth watching happens between +160 s and +193 s. Expect two and a half quiet minutes
first, and fill them with section 4.3's screens rather than waiting in silence.

**`SUSPECT` is two seconds long and you will probably not see it.** A 1 Hz poll of `/api/state`
across the whole run caught A115 in `SUSPECT` in **2 of 294 usable samples**, at +160 s and +161 s.
The page refreshes every 5 s, so the state may never be drawn at all. If someone asks to see
`SUSPECT`, show them the transcript rather than waiting for the screen.

**What the classifier actually says.** This is the most defensible thing in the demo, so read it
out rather than paraphrasing. At confirmation it lists the evidence for and against each fault kind
it can name, and the block below is this run's, verbatim from `faults/A115`:

```
+ upper-arm CUSUM 124.5 ppm passed the suspicion threshold 90 ppm on residual +220.0 ppm
+ zone A115 residual +286.8 ppm, upper-arm CUSUM 294.3 ppm against h = 150 ppm
- blocked filter: 1 of 6 rooms elevated, so the residual is not common mode
- blocked filter: AHU power 101% of the affinity-law prediction at the believed speed
- sensor drift: no commanded step has been judged yet, so whether the room still answers
  its damper is unknown rather than wrong
- sensor drift: no overnight floor yet -- less than a day of history
+ stuck damper: 1 of 6 rooms are elevated, so this is not the plant starving all of them
+ stuck damper: AHU power flat within 5%, so the plant is doing what it was asked
- stuck damper: NO COMMANDED STEP HAS BEEN JUDGED, so this diagnosis rests on localisation
  and flat fan power alone. The room has not been asked to move and therefore has not failed
  to answer; a probe is requested so the third signal can arrive and either confirm or
  overturn this
+ belief: this damper is no longer believed to be where it is commanded. The model held it
  at 0.93 on the strength of the command; the last position its response agreed with, and
  its own CO2, put it at 0.15, and the shared fan splits a fixed total, so every point of
  opening credited here is air subtracted from the other five rooms. The belief is pinned
  at 0.15 until service.
+ the upper-arm CUSUM stayed above h = 150 ppm for 2 consecutive cycles
```

Two lines are worth pointing at, and **one of them has changed sign since this runbook last
printed this block**.

- **`- stuck damper: NO COMMANDED STEP HAS BEEN JUDGED`.** This section used to print
  `+ stuck damper: the commanded step produced no matching response` in its place. The signal is
  now three-valued — matched, missing, never asked — and at the moment of classification no step
  has been judged, *ever*, because a probe is only requested once a case is latched. So the
  diagnosis rests on two signals rather than three and the work order says so. **Say this out loud
  rather than hoping nobody reads the minus sign**: it is the honest version of a leaf that used to
  claim a test nobody had run, and report §7.3 carries the measurement behind it.
- **`+ belief: ... pinned at 0.15 until service`.** This is ADR-0010 doing its job: the monitor
  stops believing a stuck damper's commanded position, which is what makes the other five rooms'
  residuals trustworthy.

### 4.5 Read it back out of the journal

The database is the demo's best evidence, because it answers "how do you know?".

```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml \
  exec timescaledb psql -U ventilation -d ventilation \
  -c "select decided_at, zone, state, classification from decisions order by decided_at;"
```

The `evidence` column on `decisions` is JSONB and holds the classifier's reasoning as prose, each
line prefixed `+` for evidence for and `-` for evidence against. It is worth showing on screen —
it is the most persuasive thing in the system.

**R-5, the reconstruction, in one request.** Take the `case_id` from `decisions` (or from the work
order's `id`, which is `work-order-<caseId>`) and ask the dashboard to rebuild it:

```bash
curl -s http://127.0.0.1:8080/api/cases/<caseId>
```

On the committed run — `evaluation/results/T-23-demo-run.txt`, commit `fee6748`, case
`aecd9137-6c3d-42a8-ac11-438ef3d9d2d6` — that returned HTTP 200 and **77 248 bytes** carrying
**445 events**: every reading, residual and CUSUM value in sequence, ending on the
`FaultStateChanged` row that put A115 in `COMPENSATING`. That is R-5's claim, demonstrated live
against a real TimescaleDB.

The `caseId` is easiest to take from the journal rather than the API, because `/api/state` reports
it as null:

```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml exec -T timescaledb \
  psql -U ventilation -d ventilation -Atc "SELECT case_id FROM strategies LIMIT 1;"
```

### 4.6 Clear the case, which is the other half of latching

A confirmed case does not clear itself — compensation shrinks the very residual that found the
fault, so the monitor refuses to be argued out of the diagnosis. The only exit is somebody saying
the equipment was repaired, and the dashboard is where they say it:

```bash
curl -s -X POST \
  "http://127.0.0.1:8080/api/zones/A115/cases/<caseId>/serviced?reason=damper+actuator+replaced"
```

It answers **202** and publishes the report on `service/A115`; the monitor closes the case, publishes
`SERVICED` on `faults/A115`, and that event is what releases the supervisor — every zone back to
`NORMAL`, the fan back to its normal speed, the BuildSim work order cleared. Watch it on the status
page: the room goes `SERVICED`, then `WARMUP`, then `HEALTHY` a few cycles later.

The refusals are worth showing too, because they are the point of validating at the boundary:

| What you send | What you get |
|---|---|
| a room that is not one of the six | `404 no such room` |
| a `caseId` that is not the one open in that room | `404 ... is not the case open in A115, which is ...` |
| a room whose case is only `SUSPECT` | `409 ... and only a latched case can be serviced` |
| no broker reachable | `503 ... so no repair can be reported` |

Take the `caseId` the same way section 4.5 does. Nothing else in the system publishes on
`service/{zone}`, so `mosquitto_pub` on that topic does the same thing if the dashboard is down —
the monitor validates the report again on its own side and refuses it in its log.

There is a catch, and section 4.7 is the other half of it: the room goes `SERVICED`, then
`WARMUP`, then `HEALTHY` — and then, if the damper is still physically stuck, the monitor
**re-detects it and opens a fresh case**. Measured on a six-room demo: a second A115 case,
`COMPENSATING`, about a minute of simulated-fast time after the first was closed, with the
cases table reading `open 1, total 2`. That is the system being right. Nothing was repaired;
only the paperwork was signed.

### 4.7 Repair the plant, which is what makes the recovery hold

`FAULT_SPEC` is read once at start-up, so until now the only way to take a fault out of the
plant was to restart the stack. The simulator publishes a small control surface for it, on
127.0.0.1 only, the runtime half of the same grammar:

```bash
# what is injected, and which of it is biting yet
curl -s http://127.0.0.1:8081/faults

# repair the damper: the fault leaves the plant on the next tick
curl -s -X DELETE 'http://127.0.0.1:8081/faults?kind=STUCK_DAMPER&target=A115'

# and a fault can be introduced the same way, with no restart
curl -s -X POST --data 'SENSOR_DRIFT:A124:150:T+0' http://127.0.0.1:8081/faults
```

**Do it in this order, because the order is the whole point:** clear the fault *first*, then
report the repair as section 4.6 does. Clearing alone does not close the case — compensation
has already shrunk the residual that found the fault, and the monitor refuses to be argued out
of a latched diagnosis, which is section 4.6's subject. Reporting alone does not fix the air,
which is what the paragraph above measures. Together, and in that order, they are what a
technician actually does: repair the equipment, then sign off the work order. The room then
returns to `HEALTHY` and **stays** there, and that is the recovery worth putting on a screen.

One difference from `FAULT_SPEC`, and it is the thing to get right: here `T+<seconds>` counts
from the **simulated now**, not from `START_AT`. A request arriving at an already-running plant
has no first tick to aim at. So `T+0` bites on the next tick and `T+300` is five simulated
minutes later — five real seconds at `TIME_SCALE=60`. The grammar is otherwise exactly section
6's, so a spec demonstrated from a file pastes straight into a curl.

The refusals are worth showing, for the same reason section 4.6's are:

| What you send | What you get |
|---|---|
| a room this process is not simulating | `400 no such room: A116; this process is simulating 6: ...` |
| a spec where one of several faults names no room | `400 ...`, and **nothing** is injected |
| a magnitude outside what the kind can physically take | `400 a stuck damper freezes at a position within [0,1], was: 1.5` |
| `?kind=` without `?target=`, or the other way round | `400 clearing one fault takes both kind and target` |
| an onset that is neither `T+<seconds>` nor ISO-8601 | `400 Text 'nope' could not be parsed at index 0` |
| any path but `/faults` | `404`, naming where the surface is |

The port follows the file's convention: `PHYSICS_PORT` on the host, 8081 inside, published on
127.0.0.1 like every other port in `docker-compose.yml`. Inside the container the server binds
the wildcard address, which it has to — Docker's port proxy connects to the container's own
interface, so a server bound to 127.0.0.1 *inside* would be unreachable through any published
port. Keeping the surface off the network is the host-side mapping's job, and it does it.

### 4.8 Teardown

> **Tear down before +336 s if anyone is watching.** Left running past about +336 s real
> (simulated 13:21) this profile goes on to confirm a **second** room, A116A, as `STUCK_DAMPER`
> with **no fault injected in it**. That is a real R-3 false alarm, not a clock artefact — the
> mechanism is the monitor's flow belief, and `docker-compose.demo.yml` records it in full. It is
> worth discussing and it is a poor thing to discover live, so both captured runs stop at about
> **+313 s** by design — `T-23-demo-run-261416e.txt` at +314 s and `T-23-demo-run.txt` at +313 s.
> The last planned screen is at +300 s, which leaves about 30 s of margin.

```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml down -v
```

`REAL_EPOCH` must still be exported, or this refuses to run. If you have lost it, any value will
do for `down` — it only has to be present.

`down -v` does **not** empty the journal, and that is a correction: this project declares no named
volumes, so `-v` has nothing to remove, and `storage/timescaledb/data` is a bind mount that
outlives the teardown with every earlier run's rows in it. The same is true of the broker's spool
and of the two `state.json` documents under `storage/`. Export whatever you want to read later
anyway — `tools/capture-demo-run.sh` does it in the right order, before the container the queries
go through is taken away — and use `tools/fresh-demo.sh` when the next run must start from
nothing.

---

## 5. What the demo actually shows right now, and the one thing to be ready for

**The demo now runs clean, and that is the trap.** On `261416e`, in the run recorded in
`evaluation/results/T-23-demo-run-261416e.txt`, the arc is correct end to end: six rooms stay
`HEALTHY` through an empty building and a filling one, the injected stuck damper is confirmed
**72 simulated minutes** after onset, it is classified **correctly** as `STUCK_DAMPER` with the
evidence in section 4.4, compensation runs, and the other five rooms never leave `HEALTHY`.
Nothing visibly misbehaves.

Be ready for exactly one question, because it is the right one: **does a clean demo mean R-3
passes?** It does not, and saying so unprompted is worth more than being asked.

- The demo is **one fault-free morning**. R-3 is a rate over **20 fault-free simulated weeks**,
  and the harness measures it: **0.55 false confirmations per week** against a limit of 1 — 45 %
  below the limit — with **16 of 20 weeks clean** and a **worst week of 6**. The harness scores the
  worst week, so R-3 **still fails**, and it fails on the worst week rather than on the rate.
- What changed is real but partial. ADR-0010 found the plausibility guards were *filtering* the
  measurement instead of bounding it, so each CUSUM arm was running on a filter error rather than
  on a residual. Fixing that halved the rate and is why the early `SENSOR_DRIFT` false alarm that
  this demo used to produce at simulated 08:00 is gone. It did not fix the worst week.
- So the honest framing: the mechanism behind R-3 is **attributed**, the attribution came from
  running this demo, half the rate went away when the cause was removed, and the remaining failure
  is concentrated in a few bad weeks that are not yet attributed. `project/aiadocs/R-3-handoff.md`
  names seed 2 as the whole worst week and is the next thing to read.

**Do not quote 72 minutes as the detection delay.** Two things are wrong with it. It is a single
draw — six runs on record span 10:16 to 10:27, which is 61 to 72 minutes from the same onset — and
T-24's onset-referenced arm puts the median at 180 min. And it is counted from onset, whereas
**R-1's figure is counted from the first ignored command: median 74 min, p95 257 min** against a
15 min limit. The number that belongs in a sentence about the system is 257, not 72 and not 387.

### Two things the demo does prove, live

- **R-5, journal replay.** The case id in the `strategies` row reconstructs the whole episode:
  `curl -s localhost:8080/api/cases/<caseId>` returned **465 events** and 82 415 bytes on this run
  (`T-23-demo-run-261416e.txt`, commit `261416e`), and **445 events** and 77 248 bytes on the
  earlier one (`T-23-demo-run.txt`, commit `fee6748`). The report quotes the earlier figure; both
  are committed, and the difference is the same run-to-run variance as the confirmation time. That
  is R-5's property demonstrated against a real TimescaleDB rather than a test double, and it is
  demonstrated twice over: eight committed tests execute `replayCase`'s own SQL against a real
  PostgreSQL.
- **R-7, isolation.** Exactly one room ever left `HEALTHY`. The other five produced a single
  decision row each, no fault event of any kind, and a final CUSUM of **0.0**. That is R-7 visible
  live rather than only in the harness.

### Why `docker-compose.demo.yml`'s transcript still differs

That file carries a transcript measured at `5618fb5` and a `SUPERSEDED` block explaining it.
Both predate the R-3 work and neither is the current behaviour: the transcript's times (`SUSPECT`
09:43, `CONFIRMED` 09:45) and the block's correction (`CONFIRMED` 08:00 as `SENSOR_DRIFT`) are each
wrong now, in opposite directions. The classification in the transcript happens to be right again,
which is the most misleading thing about it. **Section 4.4 above is the current measurement; read
it and ignore both.**

---

## 6. Demo variants

Each needs its own `up`, with `REAL_EPOCH` regenerated.

```bash
# plant-wide blocked filter, 40 %, one simulated hour in
FAULT_SPEC='BLOCKED_FILTER:PLANT:0.4:T+3600' …

# sensor drift of 150 ppm/h in the open-plan office — the magnitude is a RATE
FAULT_SPEC='SENSOR_DRIFT:A124:150:T+3600' …

# the same sensor miscalibrated instead of drifting: the zero jumps 350 ppm once
# and holds. Magnitude in ppm, not ppm/h. T-15 reports that neither shape is named
# SENSOR_DRIFT by the classifier, for two different reasons.
FAULT_SPEC='SENSOR_OFFSET:A124:350:T+3600' …

# two faults at once, to show they are diagnosed separately
FAULT_SPEC='STUCK_DAMPER:A115:0.15:T+5400;SENSOR_DRIFT:A124:150:T+7200' …

# nothing injected at all — the fault-free arm, and R-3's failure on its own
FAULT_SPEC=' ' …
```

**R-4, the staleness case,** is the one variant that needs no fault spec. Kill the sensor and watch
the monitor report unavailable rather than guess:

```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml stop co2-sensor
# three cycles later the affected rooms go BLIND and publish no diagnosis
docker compose -f docker-compose.yml -f docker-compose.demo.yml start co2-sensor
```

R-4 is verified in the harness (23/23 rows) **and at container level**: these commands were run on
`daa2d5c` against the demo profile and `evaluation/results/T-27-container-kills.txt` is the
transcript — five of six rooms reach `BLIND` with no diagnosis and no healthy-room command while
blind, and they are `HEALTHY` again three cycles after the sensor returns.

### More than six rooms (R-6)

`ZONE_COUNT` sets how many zones the whole stack runs. It is one variable for all seven
project-authored containers, like `REAL_EPOCH`, because a process on a different count computes
residuals against a plant nobody else is simulating.

```bash
ZONE_COUNT=12 docker compose up -d

# every service on the same zone set: 12 for each of the three writers
docker compose exec -T timescaledb psql -U ventilation -d ventilation -c \
  "select 'readings' t, count(distinct zone) from readings
    union all select 'residuals', count(distinct zone) from residuals
    union all select 'decisions', count(distinct zone) from decisions;"
docker compose logs fault-monitor | grep -o 'watching [0-9]* zones'
# twelve dampers in BuildSim, plus the air handler: grep -o counts occurrences, grep -c counts
# lines, and this response is one line
curl -s http://127.0.0.1:9090/api/equipment | grep -o -- '-damper"' | wc -l
```

Observed at `ZONE_COUNT=12` with the demo overlay: `readings`, `residuals` and `decisions` all
carry the same twelve zones — A125A, A125, A116A, A115, A124, A145 and then A105, A113, A113C,
A114, A119, A122 — `fault-monitor` logs `watching 12 zones`, the dashboard reports `roomsLive 12`,
BuildSim holds twelve dampers plus the air handler, and exactly one room reached `CONFIRMED`:
A115, the one the demo injects, as `STUCK_DAMPER`.

Three things to know before using it:

- **Default 6, and nothing changes at 6.** Unset or `ZONE_COUNT=6` is the real corridor and the
  ADR-0008 air handler to the last decimal. The demo overlay is unaffected.
- **58 is the ceiling.** Zones past the sixth are placed in further real level-0 rooms, because
  BuildSim refuses to host equipment in a room its floorplan does not have; there are 52 of those.
  Above 58 the containers will not register, and the process says so on start-up.
- **This is not R-6.** T-28 puts the monitor's breaking point near 10^5 zones, far above 58, so the
  breaking point stays a harness measurement. What this gives is a deployed system that can be
  scaled and watched at all. A zone past the sixth has a real room's name and location but a copy
  of one of the six rooms' size and occupancy, so it is representative, not a claim about that room.

---

## 7. Troubleshooting, all observed

| Symptom | Cause | Fix |
|---|---|---|
| `required variable REAL_EPOCH is missing a value` | demo profile declares it `:?` and you did not export it | `export REAL_EPOCH="$(date -u +%Y-%m-%dT%H:%M:%SZ)"`, then re-run |
| `failed to calculate checksum ... "/target/<module>-1.0.0-SNAPSHOT.jar": not found` on `compose build` | the jars are not there: you ran `mvn test`, or a `clean` after your last `package` | `mvn -o clean package -DskipTests`, then rebuild |
| `docker compose ps` prints nothing on the demo profile | same thing, with stderr discarded | as above |
| Port 9090 already allocated | a BuildSim started outside Compose | `export BUILDSIM_PORT=9091` |
| Harness output ends mid-table, exit code 0 | piped into `head`/`grep`, SIGPIPE killed the JVM | redirect to a file |
| A constant change has no effect | `javac` inlined the old value into callers | `mvn clean` and rebuild |
| `cannot find symbol` from `shared` in a single-module build | resolved from a stale installed snapshot | add `-am` (`mvn -o -pl supervisor -am test`) |
| Dashboard shows a dash for a room | no value yet, or older than the 180 s staleness window | intended: it will not show stale data as if it were current |

---

## 8. The state of the evidence, for reference

Measured against `main` at `17e812a`. Suite: ten reactor projects, **621 tests, 0 failures,
0 errors**. The skip count is not a fixed property of the suite and should not be quoted as one:
the integration tests that need the broker or the journal assume one is reachable, so they skip
when nothing is up and run when the stack is — the run above was taken with the stack up and
reported **0 skipped**. Report builds clean at **82 pages** from a scratch `make` in `report/`,
with no errors and no undefined references.

| Req | Verdict | Evidence |
|---|---|---|
| R-1 | **pass** on its specified protocol — stepped arm, 20/20 detected, p95 6 min against a 15 min limit, and it passes at every `h` from 50 to 300 ppm (p95 5–8 min) | T-24 |
| R-1 | **fails** in closed loop — 15 of 20 detected, median 74 min, p95 257 min | T-24 |
| R-2 | **pass** 5/5 seeds on a drawn weekday; **fails** 0/5 at design occupancy | T-26 |
| R-3 | **still fails, improved** — 0.55 false confirmations per fault-free week against a limit of 1, down from 1.30 and 45 % below the limit; 16 of 20 weeks are now clean, up from 9, but the worst week is unchanged at 6, and the harness scores the worst week, so what fails is the worst week and not the rate | T-25, ADR-0010 |
| R-4 | **pass** — 23/23 rows | T-27 |
| R-5 | demonstrated live on `fee6748`, **445 events** and 77 248 bytes rebuilt from case `aecd9137-` against a real TimescaleDB (`T-23-demo-run.txt`), **and tested**: eight committed tests execute `replayCase`'s own SQL against a real PostgreSQL — 8 skips without a database, 8/8 with one | §4.5, §5 |
| R-6 | **pass** — 25 600 zones at p95 **6.008 s**, inside the 60 s interval. Cost is `p95 = 1.196e-08 · n^1.97 s` (r² = 0.9991) over the nine points at 100 zones and above, i.e. quadratic, and the interval is **extrapolated** to break at about **86 000 zones** — an extrapolation, not a measurement | T-28 |
| R-7 | **passes on the drawn weekday** — 0 of 270 un-injected room observations fail, 0 fault events, no mode change | T-34b |
| R-7 | **fails at design occupancy** — **231 of 270** fail: **215** accused of a fault they did not have over **5 215** events, and **16** outside the 5 % airflow band, the two clauses disjoint. With the supervisor dead 85 of 90 rooms are still accused, so the accusations are the monitor and the regime, not the compensation | T-34b |

The three defects the project found in its own work, which are the material worth leading with:
the plant was initially undersized, exposed by its own no-fault control arm scoring 0.000; the
monitor broke isolation by itself, measured with the supervisor switched off; and compensation was
not merely inert but *net harmful* at design occupancy, leaving the faulted room 34 ppm worse than
no supervision at all. Each surfaced only because every experiment reports a control arm beside
the treatment arm.

## Producing the file to upload

The assignment names the upload `TeamName_FinalReport.pdf`, verbatim from the course's
`assignments.json`. From `project/report/`:

```bash
make distclean && make submission
```

`distclean` first is not optional. `report.pdf` is tracked, so a `git checkout` gives it a newer
mtime than its own sources and the next `make` reports success without rebuilding anything — the
upload would then be a stale PDF that `make` has just told you is current. `distclean` removes the
PDF and the figures so the whole chain, including the twenty-one figures converted from the
committed SVGs, is rebuilt and proven.

`Group5_FinalReport.pdf` is a byte-identical copy of `report.pdf` and is deliberately **not**
tracked: it would be a second five-megabyte copy of a file already in the repository.

## Exporting the submission repository

`tools/export-submission.sh` produces the repository the report links to. It mirrors the layout of
the course repository, `github.com/eislab-cps/D7065E`, which is public and carries `.github`,
`.gitignore`, `README.md`, `buildingsim`, `course-notes`, `lab-assignment`, `occupancysim` and
`tutorials` — and adds `project/` and `project.md`, which are the work.

The course material is included deliberately. It redistributes nothing the instructor has not
already published, and **the project cites it as evidence**: ADR-0002 justifies the runtime against
`lab-assignment/final_report_guide/report_guide.tex:384` and `:259`, sizes the breaking-point
experiment against `final_report_example.tex:900`, and ADR-0008 cites `tutorials/buildsim.md`. Ten
files under `project/` reference those paths. Leaving them out handed an examiner decision records
whose stated evidence was missing from the repository they were reading.

`.claude/` stays out: local tooling, not course material, and the course repository has no
equivalent.

```bash
tools/export-submission.sh --dry-run     # build and verify, push nothing
tools/export-submission.sh               # and push
tools/export-submission.sh --force       # replace a remote that already has commits
```

It refuses to run on a dirty tree, because an export taken from one matches no commit and nobody
can later say what was submitted. It also refuses if the committed `report.pdf` does not carry the
repository link its own sources state — `report.pdf` is a tracked build artefact, so a checkout
gives it an mtime newer than its sources and the next `make` reports success without rebuilding,
which is how a stale PDF comes to look current. Rebuild with `make -C report distclean submission`.

After building the tree it checks, and refuses to push on any failure: no `target/`, `.jar` or
`.class`; nothing under `storage/` but `.gitkeep` (that directory is a live PostgreSQL cluster and
broker spool, ignored rather than absent); no figure PDFs, which rebuild from the committed SVGs;
no `.claude/`; the four course directories present along with the two files the ADRs cite by name;
and `report.pdf`, the root `.gitignore` and `buildingsim/` all present — the last because `docker-compose.yml` builds BuildSim from it, so a
tree without it cannot be started.

`--force` is safe for the export repository and only for it: that repository holds no history of
its own, since every byte is reproduced by re-running this script.
