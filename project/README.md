# Ventilation Fault Detection, Diagnosis and Graceful Degradation

D7065E project (LTU, autumn 2026, solo) — demand-controlled ventilation over six rooms on
level 0 of the A-building that detects, diagnoses and survives faults in its own equipment.
It runs on [BuildSim](../buildingsim/), the building-state server in this same repository
(ADR-0007). BuildSim supplies the rooms and no physics, so the air, the people and the plant
are all simulated here.

## The one idea

A CO₂ controller only sees CO₂, and a high reading means the same thing whether the damper is
stuck shut or the room is simply full: **a stuck damper and a full room look identical to it.**
What tells them apart is a model of what the last command *should* have achieved. Each command
predicts a response; the gap between the predicted and the measured concentration is a residual;
a residual that is too large and too persistent to be sensor noise is a fault, and which fault
it is follows from three signatures — one room's residual rising with plant fan power unchanged
(stuck damper), all six rising together with more power per unit of flow (blocked filter), and
one room whose step-response shape is right but whose overnight floor is wrong (sensor drift).

The system is therefore two nested loops, and the project proper is the outer one.

| Loop | Period | Closes because |
|---|---|---|
| Inner — control (`dcv-controller`) | ~1 s | A damper command changes airflow, which changes CO₂, which the controller reads next |
| Outer — supervision (`fault-monitor` + `supervisor`) | ~60 s | A confirmed fault changes the control strategy, which changes the commands, which changes the residual the monitor reads next |

The two halves of the outer loop are split on purpose: `fault-monitor` raises events and never
commands, so a crashing monitor cannot stop ventilation and a wrong diagnosis cannot act by
itself. `supervisor` is the only component that commands.

The hardest property in the design is that **the diagnosis stays latched after compensation has
made the CO₂ curve look healthy again** — compensation masks the very signal that found the fault.

## The six rooms and the one air handler

The six rooms are the cluster on corridor A120, chosen in
[ADR-0008](decisions/ADR-0008-room-topology.md) and encoded in
[`shared/.../ventilation/entity/Topology.java`](shared/src/main/java/se/ltu/d7065e/ventilation/entity/Topology.java).
Volume is floor area × an assumed 3 m ceiling; the BuildSim floorplan carries no height.
Design flow is the Swedish workplace guideline, 7 l/s per person plus 0.35 l/s per m².

| Room | Area | Volume | Intended use | Design flow |
|---|---|---|---|---|
| `level0/A125A` | 18.0 m² | 54.0 m³ | single office, 1 person | 13.3 l/s |
| `level0/A125` | 29.8 m² | 89.4 m³ | two-person office | 24.4 l/s |
| `level0/A116A` | 33.0 m² | 99.0 m³ | project room, 4 people | 39.6 l/s |
| `level0/A115` | 44.0 m² | 132.0 m³ | meeting room, 10 seats | 85.4 l/s |
| `level0/A124` | 76.5 m² | 229.5 m³ | open-plan office, 8 desks | 82.8 l/s |
| `level0/A145` | 74.0 m² | 222.0 m³ | seminar room, 20 seats | 165.9 l/s |

The rooms do **not** each get their own fan. ADR-0008 chose **option B: one shared
air-handling unit, `AHU-A120`, with one damper per room.** The unit delivers
`Q_fan = Q_nom·s·(1 − β)` at power `P = P_nom·s³·(1 + β)`, and that flow is split over the six
damper openings as `Q_i = Q_fan · a_i / Σ a_j`. Nominal flow is 0.59 m³/s — the sum of the six
design flows, 411.4 l/s, divided by the normal fan speed of 0.7 and rounded up, so that the plant
covers the whole simultaneous design condition **at the speed it actually runs** and not only at
full speed. The 43 % margin up to `s` = 1.0 is the headroom compensation spends, and **at a fixed
fan speed the only way one room gets more air is if another damper closes.** Dampers never shut
fully: the floor is 0.15. Outdoor CO₂ is 420 ppm, the comfort limit 1000 ppm, generation
5·10⁻⁶ m³/s per person.

That coupling is what **"compensate"** means here: when a room's damper is confirmed stuck,
`supervisor` raises the shared fan speed *and* trims the five healthy dampers, which under the
split pushes flow to the faulted room while the healthy five keep the airflow they had (R-7).
It works only if the stuck damper is stuck **partly open** — the realistic case, since the floor
is 0.15. **A damper stuck fully shut cannot be reached at all**: there is no air path, and the
response is a work order plus occupancy advice rather than compensation. R-2 is a claim about
the reachable case only.

Fan power belongs to the unit, not to a room, which is what gives the monitor its one cross-room
signal and separates a blocked filter from a stuck damper without inventing a fourth signal.

Every parameter above is **invented rather than measured** — each is derived from a stated rule,
and ADR-0008 lists the derivations so the report's critique can own them.

## How to run it

Needs a JDK 25, Maven and Docker. All commands are run from `project/`.

| Target | What it does |
|---|---|
| `make build` | `mvn -q package` — builds every module's jar on the host |
| `make images` | `make build`, then `docker compose build` — the images copy host-built jars |
| `make up` | `make images`, then `docker compose up -d`, and prints the two URLs |
| `make down` | `docker compose down -v` |
| `make logs` / `make ps` | follow the logs / list the services |
| `make test` | `mvn test` |
| `make experiments` | builds and runs the offline evidence harness in `evaluation/`, writing one CSV per experiment into `evaluation/results/` |
| `make verify` | `make test` then `make experiments`: the whole suite followed by the R-1…R-7 evidence runs |
| `make clean` | `mvn -q clean` and tears the stack down |
| `make all` | `make build images` |

Then open:

- **BuildSim's 3D view** — <http://127.0.0.1:9090/?layer=residual&floor=level0>, or on
`$BUILDSIM_PORT` if set (compose makes the host port overridable, because a BuildSim started
outside compose already holds 9090; `make up` prints the URLs it actually published).
  `dashboard` repaints BuildSim's own floorplan every 5 s with one value per room per layer, so
  the `layer` query parameter switches what the rooms are coloured by: `co2`, `residual`,
  `cusum` or `state`. `state` is the one that keeps a latched room hot after compensation has
  pulled its residual back to zero. `supervisor` is the only writer of BuildSim alerts, where
  the work order appears.
- **The dashboard** — <http://127.0.0.1:8080>, or on `$DASHBOARD_PORT` if set. A
server-rendered HTML table that refreshes
  itself every 5 s; `GET /api/state` is the same view as JSON, and `GET /api/cases/{caseId}`
  replays one diagnosis out of the journal (R-5). Every dependency is optional, so this page
  keeps serving when the broker, BuildSim or the database is down — it is how you tell whether
  the rest is alive.

The broker is on `127.0.0.1:1883` and TimescaleDB on `127.0.0.1:5432`
(`ventilation`/`ventilation`), both published on the loopback interface only.

### Injecting a fault

`physics-simulator` injects faults *between* a command and its physical effect, so the command
record and the physics disagree — which is what gives the monitor something to detect. It reads
either `FAULT_SPEC` or `FAULT_SPEC_FILE`; the format is documented on
[`FaultSpecParser`](physics-simulator/src/main/java/se/ltu/d7065e/physics/control/FaultSpecParser.java):

```
KIND:TARGET:MAGNITUDE:ONSET
```

several of them separated by `;` or by newlines, with `#` comments ignored:

```
STUCK_DAMPER:A115:0.15:T+1800
BLOCKED_FILTER:PLANT:0.4:T+7200
SENSOR_DRIFT:A124:150:2026-10-05T09:00:00Z
```

- `KIND` is `STUCK_DAMPER`, `BLOCKED_FILTER` or `SENSOR_DRIFT`.
- `TARGET` is a room name, or `PLANT` for `BLOCKED_FILTER`, which is plant-wide because the
  filter belongs to the shared unit.
- `MAGNITUDE` is the fault's own quantity — the frozen damper position, the blockage fraction β,
  the sensor's drift *rate* in ppm per hour, which accumulates from the onset rather than
  appearing as a fixed offset.
- `ONSET` is `T+<seconds>` from the start of the run, or an ISO-8601 instant.

`docker-compose.yml` declares both `FAULT_SPEC` and `FAULT_SPEC_FILE` on the
`physics-simulator` service and passes them through from the invoking shell, so a scenario needs
no file edit:

```
FAULT_SPEC='STUCK_DAMPER:A115:0.15:T+1800' make up
```

or, against a stack that is already running:

```
FAULT_SPEC='STUCK_DAMPER:A115:0.15:T+1800' docker compose up -d physics-simulator
```

Both default to empty, which is a fault-free run. The simulator's other variables are
`BUILDSIM_URL`, `TICK_SECONDS` (1) and `SEED` (42); the monitor's are `CUSUM_K` (30 ppm),
`CUSUM_H` (150 ppm) and `CYCLE_SECONDS` (60).

### The 60x demo profile

`docker-compose.demo.yml` runs the building 60 times faster than the clock on the wall, so the
headline scenario — a healthy loop, a damper jamming, the diagnosis, the degradation and the work
order — fits an oral slot instead of an afternoon. It changes no model, no threshold and no
decision rule: it changes how much simulated time one real second carries, and every loop period
and staleness window is converted by the same factor so each one still fires at the same point in
*simulated* time.

All seven project-authored processes compute simulated time the same way:

```
simulated now = START_AT + (real now - REAL_EPOCH) x TIME_SCALE
```

`REAL_EPOCH` is **one real instant that every container must be given identically**. It is not a
convenience: a clock counting from its own construction would put processes started at different
real instants on different timelines, permanently offset from each other and from the plant, and
the offsets would reach `fault-monitor` as residuals and staleness that look exactly like a fault.
Compose cannot generate the instant, but it substitutes from the invoking shell exactly as it does
for `FAULT_SPEC`, and the shell evaluates `date` once before compose starts — so one `up` gives
one epoch to all seven containers, and a container that restarts rejoins the same timeline.

**The working command.** The build is a separate step, because the shell evaluates `date` before
it execs compose and an epoch taken in front of `up --build` would be however long the image build
took older than the containers that read it:

```
cd project
mvn -q package -DskipTests
BUILDSIM_PORT=9091 docker compose -f docker-compose.yml -f docker-compose.demo.yml build
REAL_EPOCH="$(date -u +%Y-%m-%dT%H:%M:%SZ)" BUILDSIM_PORT=9091 \
  docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d
```

`BUILDSIM_PORT=9091` because a BuildSim started outside compose already holds 9090. Omitting
`REAL_EPOCH` is refused rather than defaulted — compose declares it with `:?` and `ScaledClock`
throws — because a per-container epoch is precisely the failure this profile used to have, and it
must not be reachable by forgetting a variable.

Tear it down with:

```
docker compose -f docker-compose.yml -f docker-compose.demo.yml down -v
```

**What a run looks like.** `START_AT` is 07:45 on Friday 2026-10-09, fifteen simulated minutes
before A115's 08:00 booking, and `FAULT_SPEC` jams its damper at `T+5400` — 09:15 simulated, which
is 90 real seconds in. Measured end to end, one real second per simulated minute:

| Wall clock | Simulated | What happens |
|---|---|---|
| +10 s | 07:55 | the monitor's first decision: all six rooms `HEALTHY` |
| +61 s | 08:46 | the first damper command on A115 at 771 ppm, and sixteen more before the onset |
| +90 s | 09:15 | the damper jams at 0.15 while the controller keeps asking for more |
| +118 s | 09:43 | A115 `SUSPECT` |
| +120 s | 09:45 | A115 `CONFIRMED STUCK_DAMPER`, `DEGRADED` by the supervisor, work order raised |

Exactly one room reaches `CONFIRMED`, it is the only room carrying an injected fault, and it is
confirmed after its own onset. The other five produce no fault event at all; their peak CUSUM at
the end of the window is 27–62 ppm against `h` = 150, none of them having reached even the 90 ppm
`SUSPECT` threshold. Query it with:

```
docker compose exec -T timescaledb psql -U ventilation -d ventilation \
  -c "select zone, state, classification, decided_at from decisions order by decided_at;"
```

**Tear it down when the scenario is over.** Left running past simulated 10:00, the same run went
on to confirm a *second* room, A116A, `STUCK_DAMPER` at simulated 13:21, with no fault injected in
it. That is not a timebase artefact — the clocks were measurably in step for the whole run and the
other four healthy rooms stayed between 30 and 68 ppm — but a gap in the diagnosis chain: nothing
closes the monitor's flow belief after a `STUCK_DAMPER` confirmation, so it keeps crediting A115
with air the other five are actually getting. The override file's `KNOWN LIMITATION` section has
the measurement and the mechanism.

What 60x still costs is in that same header: the inner loop and the sensor run a
one-simulated-minute cycle rather than a one-second one, so a detection delay read off this profile
is quantised to simulated minutes and is **not** the delay the base profile would give. The
R-1…R-7 numbers in the report come from the evaluation harness on a `SimulatedClock`, not from this
profile. This profile is for watching the system work.

### Current limitations

- **Four requirements carry a failing clause, and the harness says so rather than hiding it.**
  R-1 fails in closed loop while passing on the stepped protocol its own verification column
  names; R-2 fails at design occupancy while passing on a drawn weekday; R-3 fails on its worst
  week of 6, though its rate of 0.55 confirmed cases per fault-free week is 45 % below the limit;
  and R-7 fails at design occupancy (231 of 270 observed rooms) while passing on a drawn weekday
  (0 of 270). Three of the four pass in the regime their criterion names and fail in another, so
  quoting "four fail" without naming the arm is as misleading as quoting the pass. The numbers and
  the diagnosis are in the report: Appendix F carries the working behind each verdict and Appendix G
  the register of what each number actually is. `make experiments` reports the verdicts per
  requirement, including the failures.
- `mvn package` runs the tests, so any failing module stops `make build`, `make images` and
  `make up` at that module.

## How to run the tests

```
cd project && mvn -q test      # or: make test
```

Unit tests live beside the code they test, under each module's `src/test/java`, and the
business logic they cover is the control and entity layers: the value objects and the monitor
state machine and the flow-split law in `shared/`, the mass balance and fault injection in
`physics-simulator/`, the hysteresis loop in `dcv-controller/`, CUSUM and the classifier in
`fault-monitor/`, the compensation solver in `supervisor/`, and the view projection in
`dashboard/`.

The run is the authority on how many tests there are and whether they pass; a count written into
this file is stale by the next commit. `mvn test` reports the count and the failures per module,
and Surefire leaves the same figures in each module's `target/surefire-reports/`, so the number
worth quoting is the one from your own run.

A passing run is every module reporting zero failures. Eight of the nine modules sit outside
`fault-monitor`, and all eight carry tests of their own, `evaluation` included -- its experiment
classes are covered by six test classes under `evaluation/src/test`. The subset below leaves
`evaluation` out because its harness tests are the slowest part of it; it is the quickest way to
tell a regression in the detection core from a change that broke something shared:

```
mvn -q -pl shared,physics-simulator,co2-sensor,damper-actuator,dcv-controller,supervisor,dashboard -am test
```

`fault-monitor`'s detection core is the part still under change, so a failure is likeliest there.

## Folders

Seven folders are containers, one apiece. The rest are not, which is the whole reason the
folder set is larger than the compose service set — **the container-to-service-to-folder
mapping stays 1:1** for all seven.

| Folder | Kind | Role |
|---|---|---|
| [`physics-simulator/`](physics-simulator/) | container | CO₂ mass balance per room, occupancy schedules, the shared air handler driven through `shared/`'s flow-split law, fault injection; writes sensor values to BuildSim |
| [`co2-sensor/`](co2-sensor/) | container | Reads the six room CO₂ values and the unit's fan power from BuildSim, publishes them to the broker |
| [`damper-actuator/`](damper-actuator/) | container | Validates and applies HVAC actuator commands to BuildSim — the six dampers and the one fan speed |
| [`dcv-controller/`](dcv-controller/) | container | Inner loop: damper position from CO₂ with hysteresis and a freshness check, deliberately a plain rule |
| [`fault-monitor/`](fault-monitor/) | container | Outer loop, half one: predicts each command's response, accumulates the residual (one-sided CUSUM), classifies the fault. Raises events only, never commands |
| [`supervisor/`](supervisor/) | container | Outer loop, half two: acts on a *confirmed* fault — degrade, compensate, work order |
| [`dashboard/`](dashboard/) | container | The operator view: BuildSim's room layers plus its own HTML page and JSON endpoint |
| [`shared/`](shared/) | library | The domain language — rooms, the unit, value objects, monitor states, domain events, topics, and the air handler's flow-split law, which is arithmetic over two of those types and had been written twice — plus the MQTT, BuildSim and TimescaleDB adapters behind ports. Not a container |
| [`evaluation/`](evaluation/) | offline harness | Runs the same control and diagnosis classes against a simulated clock, so a seven-day replay takes minutes. `make experiments` produces the R-1…R-7 evidence. Not a container |
| [`deploy/`](deploy/) | configuration | `mosquitto.conf` and the TimescaleDB schema. Not a container |
| [`diagrams/`](diagrams/) | figures | The nine D2 sources and their rendered SVGs, shared by the report and by `docs/arc42.md` rather than duplicated. Not a container |
| [`docs/`](docs/) | documentation | [`arc42.md`](docs/arc42.md), the arc42 frame around the C4 views. Not a container |

`docker-compose.yml` has ten services: those seven, plus `buildsim` (built from
[`../buildingsim`](../buildingsim/)) and the two third-party images `mosquitto` and
`timescaledb`, whose configuration is the `deploy/` folder.

## Architecture documentation, decisions, requirements, plan and diagrams

Two frameworks, for two jobs. **C4** carries the structural views — the numbered zoom from context
to code, with a fixed element vocabulary at each level. **arc42** carries the document frame — the
twelve sections that force the questions a diagram cannot answer, among them constraints,
cross-cutting concepts, measurable quality scenarios, and risks and debt. The report is the
narrative; arc42 cites it rather than repeating it.

- [`report/report.tex`](report/report.tex) — the full architecture document. `make` builds the PDF.
  **Editing rule, and it is load-bearing.** The main body, §1–§13, stays concise enough to be read
  end to end to a verdict: the claim, the verdict with its regime qualifier, and the one table or
  figure that proves it. New depth goes into a lettered appendix in `report/sections/appendix-*.tex`,
  cross-referenced from the exact place in the body where a reader would otherwise go looking — the
  course's own worked example is 14 pages, so the body is already generous. Nothing is deleted to
  make room; it moves. And **look at the built PDF in a browser before committing it**, page by
  page, hunting near-empty pages, columns so narrow that cells collide, tables broken across a page
  boundary and floats stranded away from their text: LaTeX compiles bad layout with zero errors, and
  this document has shipped a malformed table through three review passes on a clean log.
- [`docs/arc42.md`](docs/arc42.md) — the arc42 frame, all twelve sections. Its own contribution is
  §10, a quality tree with fifteen measurable scenarios (stimulus → response → measure) carrying
  the numbers that were actually measured, and §11, the debt register as of a stated date. §12 is
  the glossary: the fixed vocabulary every module is written against.
- [`decisions/`](decisions/) — the decision log. One record per decision, each naming the
  requirement it serves, the alternative it beat, the price paid, and a one-sentence defence.
  arc42 §9 indexes them with a one-line rationale each.
- [`REQUIREMENTS.md`](REQUIREMENTS.md) — R-1…R-7 in EARS form with acceptance criteria, their
  verification, the traceability table, and the gaps not yet covered by any requirement ID.
- [`diagrams/`](diagrams/) — nine figures, each a `.d2` source with its rendered `.svg` beside it.
  The C4 model is complete: [context](diagrams/context.svg) (L1),
  [container](diagrams/container.svg) (L2), [component](diagrams/component.svg) (L3, inside
  `fault-monitor`), and two code diagrams at L4 — the
  [diagnosis chain](diagrams/code-diagnosis.svg) and the
  [compensation solver](diagrams/code-compensation.svg), which are the only two parts where a
  code-level view adds information. Plus the two supplementary C4 views, a
  [dynamic diagram](diagrams/dynamic.svg) of the stuck-damper scenario and a
  [deployment diagram](diagrams/deployment.svg), and two UML views, a
  [sequence diagram](diagrams/sequence.svg) and the monitor's
  [state machine](diagrams/state.svg). Re-render with `d2 --layout=elk`, or
  `make -C report diagrams`.
- [`../project.md`](../project.md) — the short summary: pitch, scenario, containers,
  requirements, risks.
- [`proposal/`](proposal/) — the accepted week-2 proposal.
  [`ideas/`](ideas/) — the catalogue the use case was chosen from.
  [`spikes/`](spikes/) — the throwaway experiments that fixed the detection parameters.

## Stack

**Java 25** throughout, on **Quarkus in JVM mode** for the four services that need scheduling,
configuration and health (`dcv-controller`, `fault-monitor`, `supervisor`, `dashboard`) and a
plain `java -jar` for the three processes that do not (`physics-simulator`, `co2-sensor`,
`damper-actuator`); every image is `eclipse-temurin:25-jre` over a jar built on the host.
**MQTT** carries readings, commands and fault events, through one Eclipse Paho adapter in
`shared/`. **TimescaleDB** is the hot store: six typed hypertables, every row carrying which
process wrote it and which command the observation is judged against, because a residual that
cannot be tied to its command is not evidence. Packages follow BCE by business component —
`ventilation/`, `diagnosis/`, `supervision/`, `physics/`, `dashboard/`, `devices/`, `shared/`,
each with `boundary/`, `control/` and `entity/`. Why this stack and not another is
[ADR-0002](decisions/ADR-0002-runtime-stack.md); why the structure is
[ADR-0005](decisions/ADR-0005-architecture-style.md).
