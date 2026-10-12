# Architecture documentation — arc42

**System:** Ventilation fault detection, diagnosis and graceful degradation (use case A-06)
**Course:** D7065E · **Author:** Wassim Akachi · **As of:** 2026-10-06, the arc42 reconciliation at `a97c7c6`

## How to read this document, and what it is not

This is the arc42 frame for a system whose structural views are drawn in **C4** and whose full
narrative is the report, `project/report/report.tex`. The two are used for different jobs and
neither is decoration:

- **C4** supplies the structural views — a numbered zoom from context to containers to components
  to code, with a fixed element vocabulary at each level (person, software system, container,
  component), so a reader can tell what kind of thing a box is without reading its label twice.
- **arc42** supplies the document frame — twelve numbered sections that force the questions a
  structural diagram cannot answer: constraints, cross-cutting concepts, measurable quality
  scenarios, and risks and technical debt.

**This document deliberately does not repeat the report.** Where the report already says something
well, the section here cites it as `report.tex §n.m` and summarises in a sentence or two. The value
added by arc42 for this system is concentrated in **§10** (a quality tree with concrete, measured
scenarios) and **§11** (the debt register), which the report's structure has no single home for.
Where a section has genuinely nothing to say, it says so in one sentence rather than padding.

Every number in this document is read off a file in the repository or off a recorded run. None is
an estimate. Where a figure is an extrapolation or an analytic result rather than a measurement, it
is labelled as one.

---

## 1. Introduction and Goals

The system runs demand-controlled ventilation over six rooms of one office corridor behind a single
shared air handler, and — this is the project rather than the backdrop — decides whether its own
equipment still works, names which fault it has, and keeps the affected room breathable while a
technician is sent. It has two nested loops: an inner control loop at about 1 s and a supervisory
diagnosis loop at about 60 s. See `report.tex §1`–`§2` for the use case and the building, and
`decisions/ADR-0001-use-case.md` for why this use case was chosen.

### 1.1 Requirements overview

Seven requirements in EARS form. `REQUIREMENTS.md` is the authoritative copy and `report.tex §3`
carries the full statements, acceptance criteria and verification. Summarised here with the status
as of the date above, because arc42's job is to make the state of the system legible at a glance:

| ID | What it demands | Status as of 2026-10-06 |
|---|---|---|
| R-1 | Confirm a non-responding damper, p95 ≤ 15 min | **Met on the stepped protocol (p95 6 min, 20/20); not met in closed loop (p95 257 min, median 74, 15/20)** |
| R-2 | Keep a latched zone below 1000 ppm by compensation, ≥ 95 % of occupied minutes | **Met on the drawn weekday (1.000, 5/5); not met at design occupancy (0.022, 0/5)** |
| R-3 | ≤ 1 false alarm per fault-free simulated week | **Not met: 0.55 confirmed cases per week over 20 weeks**, down from 1.30 by ADR-0010; 16 weeks clean; worst week unchanged at 6, and the harness scores the worst week |
| R-4 | Report unavailable and issue no diagnosis on stale readings | **Met in-JVM (23/23 assertions) and at container level** (`T-27-container-kills.txt`) |
| R-5 | Make every diagnosis reconstructable from the journal | **Met: demonstrated live and tested** — eight tests execute the adapter's real SQL against a real PostgreSQL |
| R-6 | Identify the zone count at which the monitor misses its interval | **Met in the harness, extrapolated: ≈ 86 000 zones.** The *deployed* stack tops out at 58, for a reason outside the monitor (D-13, D-30) |
| R-7 | Keep the other five rooms unchanged while one is latched | **Met on the drawn weekday (0 of 270); not met at design occupancy (231 of 270 fail)** |

Two of the seven are met outright. Three more pass on the arm their criterion names and fail on the
harder one beside it, and those are reported as two numbers rather than one, because a single verdict
would have to pick which regime to be silent about. R-3 is the one that fails everywhere, and R-1's,
R-2's and R-7's harder arms all have its false-alarm rate somewhere in their causes. That is the
system's honest headline and it is not softened anywhere in this document.

### 1.2 Quality goals

The three the architecture is actually shaped by, in priority order. Each is made measurable in
§10 rather than left as an adjective.

1. **Ventilation must not stop because diagnosis went wrong.** The newest and least-trusted code in
   the system must not hold actuator authority. This is the single most important structural
   decision and it is why `fault-monitor` and `supervisor` are two containers
   (`report.tex §4.3`).
2. **A diagnosis must be defensible after the fact.** Every decision carries the inputs, the
   command it was judged against, the residual series and its evidence, so a work order can be
   argued with rather than merely obeyed (R-5).
3. **A fault in one room must not degrade the other five.** The shared air handler couples them, so
   this is something the supervisor has to earn rather than something the topology gives away
   (R-7, `decisions/ADR-0008-room-topology.md`).

Two further goals are real but subordinate: detection must be fast enough to matter (R-1) and quiet
enough to be believed (R-3), and these two pull against each other through a single number, the
CUSUM limit `h`. `report.tex §3.1` sets out that tension; §10 below prices it.

### 1.3 Stakeholders

| Stakeholder | Concern | What they need from this document |
|---|---|---|
| Examiner | Whether the architecture is understood and defensible, and whether the evidence supports the claims | §9 for decisions with their rejected alternatives, §10 for measured quality, §11 for what does not work |
| Facilities technician | Which room, which fault, and whether the reasoning can be checked | §6 runtime view, §10 scenarios Q-12 and Q-14 |
| Room occupants | Air that stays breathable while equipment is broken | R-2, scenarios Q-05 to Q-07 |
| Author / future maintainer | Where to change a thing without breaking another | §5 building blocks, §8 cross-cutting concepts, §11 debt |

---

## 2. Architecture Constraints

These are given rather than chosen. The implementation plan's §2 is the authoritative list
(`aiadocs/PLAN-A06-implementation.md`); the ones that actually shape the architecture are:

| # | Constraint | Where it comes from | What it forces |
|---|---|---|---|
| C-1 | **BuildSim is the only interface to the building.** It is a state store with a 3D viewer and no physics, and it never calls in | Course material; ADR-0001 | Physics, sensing, decisions and history are all processes this project writes. Every exchange with the building is initiated by this system |
| C-2 | **BuildSim is not independently obtainable** — no published image, no repository of its own | ADR-0007 | The project lives inside the course-material repository so the evaluation is reproducible from one clone |
| C-3 | **Java 25 on Quarkus/MicroProfile** for the services that need scheduling, configuration and health; plain Java for the device processes and the simulator | ADR-0002 (plan says Java 21+; Java 25 is what is used) | No framework in the control layer, which is what makes it unit-testable without a broker |
| C-4 | **BCE inside hexagonal.** Packages by business component, each with `boundary` / `control` / `entity`; every boundary crossing is a port declared in the core in domain vocabulary; domain types are records enforcing their own invariants; **no mapper layer** except where the schema is not ours | ADR-0005, ADR-0006 | §5 and §8.1 |
| C-5 | **Container ↔ compose service ↔ folder is 1:1** for the project-authored components | Plan §2.4 | Adding a box to the container diagram means adding a folder and a service, which is what keeps the diagram from drifting. Holds for all seven project-authored services; the other three compose services are `buildsim` and two third-party images |
| C-6 | **Traceability.** Every requirement has an ID, every test names the ID it verifies, every experiment names the requirement it produces evidence for | Plan §2.5 | A test that cannot name a requirement is a finding, not a reason to skip the ID. §10 marks the scenarios with no ID as gaps |
| C-7 | **Several rooms** — six rooms on level 0, with a fault injected in one while the other five stay live | Examiner condition on the accepted proposal | R-7 exists; the topology is one shared air handler rather than six independent fans |
| C-8 | **No dependency without justification**: standard library, then Jakarta EE / MicroProfile, then a library, and say why the first two did not work | Plan §2.7 | `report.tex §7.5` records the measured reason Parquet is not vendored: 98 jars and 64.8 MiB against roughly 150 kB of data |
| C-9 | **TimescaleDB as the hot store, native SQL for the window queries**, no ORM for them | ADR-0002, plan §2.2 | §8.4 |
| C-10 | **One laptop.** Everything runs in one Docker Compose project on one machine | Project scope | §7, and the deployment viewpoint is about process composition rather than geography |

One constraint is worth flagging as *not* binding: there is no performance budget imposed from
outside. R-6's 60 s interval is the system's own cycle, chosen in ADR-0008, not a stakeholder
requirement.

---

## 3. Context and Scope

Drawn as **C4 Level 1** in `diagrams/context.d2` and discussed in `report.tex §4.1`. The system
boundary is the point of that figure: the system reads and commands the building only through
BuildSim, the technician is the only actor who can clear a latched case (by repairing the damper),
and occupants are sensed rather than connected.

### 3.1 Business context

| Partner | Element type | What flows in | What flows out |
|---|---|---|---|
| Room occupants | Person | — | CO₂, via the simulated building; their comfort is what R-2 protects |
| Facilities technician | Person | A work order naming the room and the fault, with the evidence behind it | A repair, which is the only thing that can clear a latched case |
| BuildSim | Software System (external) | Sensor values, actuator state, occupancy, room layers, alerts, the 3D view | Nothing: it has no client for this system and never initiates an exchange |

### 3.2 Technical context

| Channel | Protocol | Contract | Where specified |
|---|---|---|---|
| Building state and commands | REST over HTTP to BuildSim | `GET/PUT /api/sensors`, `/api/actuators`, `PUT /api/occupancy`, `/api/room-layers`, `/api/alerts`, `POST /api/equipment/bulk`, `GET /healthz` | `report.tex §5.2` |
| Internal events | MQTT via Mosquitto, eight topic patterns | Readings, commands, residuals, faults, strategy, service reports — publishers, subscribers and payload fields | `report.tex §5.1` |
| History | SQL to TimescaleDB, six hypertables | `readings`, `fan_power`, `commands`, `residuals`, `decisions`, `strategies` | `report.tex §5.3` |
| Operator view | HTTP on 8080 | An HTML status page, `GET /api/state`, `GET /api/cases/{caseId}`, `POST /api/zones/{zone}/cases/{caseId}/serviced` | `report.tex §12` |

### 3.3 Explicitly out of scope

Doors, badges and access control (the use case this project replaced, ADR-0001 and the superseded
ADR-0003/ADR-0004); heating and cooling; more than one air handler; more than one floor; any
deployment beyond one laptop; and any real building. `report.tex §13.7` separates the scope choices
that are legitimate from the debts taken knowingly.

---

## 4. Solution Strategy

Five decisions carry the design. Each is one sentence here and is argued at length where cited.

1. **Two nested loops, split across two containers.** A plain hysteresis rule controls ventilation
   at about 1 s; a model-based supervisory loop diagnoses at about 60 s. The inner loop must
   survive the outer one dying. (`report.tex §7.1`, `§4.3`)
2. **The monitor raises events and never commands; the supervisor is the only component that
   acts.** Enforced structurally, not by convention: the monitor's container has no publisher for
   any `commands/*` topic, and the one apparent exception — the active probe, which is a damper
   command — is owned by the supervisor so the rule has no exception. (`report.tex §4.3`)
3. **Diagnose by what the command owed, not by the concentration.** An observer predicts the CO₂
   trajectory the last commanded damper step should have produced and the residual is the
   difference, because a stuck damper and a full room look identical to anything that only sees
   CO₂. Occupancy, which a real building does not measure, is replaced by three devices: judge only
   commanded step responses, estimate generation from quasi-steady state and freeze it while
   judging, and guard with a design-occupancy ceiling. (`report.tex §7.2`)
4. **Accumulate rather than threshold, and latch.** A one-sided CUSUM catches a small persistent
   bias long before any single-sample threshold fires; a confirmed case does not clear itself,
   because compensation shrinks the very residual that found the fault. (`report.tex §7.3`)
5. **Compensate by a closed form, not a search.** Trimming every healthy damper by one common
   factor `t` and raising the fan to the one speed that preserves the healthy flows gives the
   faulted room exactly `1/t` times its airflow — the trim *is* the gain — and the plant's nominal
   flow and filter blockage cancel out of both ratios. (`report.tex §7.4`)

Underneath all five: **ports at every boundary crossing**, which is what lets the offline harness
drive seven simulated days through the same control classes the containers run, in minutes
(`§8.2`).

---

## 5. Building Block View

### 5.0 How arc42's building blocks map onto the C4 levels

arc42 names a hierarchy of "building blocks" without fixing what a block may be; C4 fixes the
element types but not the document structure. This system uses the correspondence explicitly, so
that neither vocabulary has to be guessed at:

| arc42 | C4 | Here | Figure |
|---|---|---|---|
| Level 1 — whitebox of the overall system | Level 2, containers | The nine containers inside the system boundary, plus BuildSim outside it | `diagrams/container.d2` |
| Level 2 — whitebox of one building block | Level 3, components | Inside `fault-monitor` | `diagrams/component.d2` |
| Level 3 — whitebox of one component | Level 4, code | The diagnosis chain; the compensation solver | `diagrams/code-diagnosis.d2`, `diagrams/code-compensation.d2` |

### 5.1 Level 1 — the containers

`report.tex §4.2` and `§4.3` carry the figure, the one-job-each responsibility table and the reason
each is a separate process. Not repeated here. The facts worth having in this document, because
they are the ones most often mis-stated:

- **Ten compose services**, nine of which are containers *of this system*. The seven
  project-authored ones are `physics-simulator`, `co2-sensor`, `damper-actuator`,
  `dcv-controller`, `fault-monitor`, `supervisor`, `dashboard`; `mosquitto` and `timescaledb` are
  third-party images configured under `deploy/` and are containers of this system; `buildsim` is an
  **external software system** that happens to be built from the sibling folder in the same
  repository (C-2).
- **Ten Maven reactor projects**: nine `<module>` entries plus the aggregator root POM. Two of the
  nine modules are not containers — `shared` is a library (the domain language, the seven ports,
  the three infrastructure adapters for MQTT, TimescaleDB and BuildSim, the event codec and the
  three clock implementations) and `evaluation` is the offline harness. That is the whole reason the
  folder set is larger than the service set.
- **Six zones and one air handler**, both defined in code in exactly one place,
  `Topology.java`: zone ids `A125A`, `A125`, `A116A`, `A115`, `A124`, `A145`, and
  `AHU-A120`. `Topology.syntheticZones(int)` produces a larger set, and since D-13 was closed it is
  no longer harness-only: `Topology.zones()` returns `syntheticZones(CONFIGURED_ZONE_COUNT)` whenever
  `ZONE_COUNT` is not 6, and all seven project-authored containers read the same variable. The
  default six-room corridor is unchanged end to end. The ceiling the wiring exposed is BuildSim's,
  not the monitor's — 58 zones (§11, D-13, D-30).

### 5.2 Level 2 — inside `fault-monitor`

`report.tex §4.4` carries the figure and the BCE discussion. `fault-monitor` is the one container
complex enough to deserve its own picture. Two structural facts that the figure makes explicit and
that are easy to get wrong:

- Its **entity layer lives in `shared`**, under the same `se.ltu.d7065e.diagnosis` package prefix.
  The module holds only `boundary` and `control`. BCE is followed, but split across two Maven
  modules.
- **`MqttAdapter` is one class implementing two ports** — `ReadingStreamPort` and
  `EventPublisherPort` — because subscribing and publishing are one broker session. There are seven
  ports in total and they are the entire contents of `shared`'s `control` package.

### 5.3 Level 3 — the two code-level blocks

`report.tex §4.5` states the criterion for drawing code-level diagrams at all and why only these
two qualify: a code diagram earns its page where a claim the documentation makes is a claim about
the *shape of the code* rather than about the physics, and where the level above hides the thing
being claimed. The diagnosis chain carries four such claims — the two-phase cycle, the three
distinct residuals, the single exit from a latch, and the missing classification constant — and the
solver carries one, that `solve()` is straight-line with no convergence test.

---

## 6. Runtime View

Four scenarios. The first two are drawn; the other two are described because a figure would add
nothing.

### 6.1 The headline scenario: a damper frozen at 0.15

Drawn twice, deliberately: as a **C4 dynamic diagram** with numbered interactions
(`diagrams/dynamic.d2`, `report.tex §8.1`) and as a **sequence diagram** that adds duration and the
three phases after confirmation (`diagrams/sequence.d2`, `report.tex §8.2`). The point the pair
makes and a static view cannot: the monitor acts on *a command and its aftermath*, not on a
reading, and every action in the second half comes from the supervisor. The fourth group of the
sequence view is the project — after compensation the residual collapses and the CUSUM goes to
zero, and **the case does not clear**.

### 6.2 One monitor cycle, and why the order matters

`FaultMonitor.runCycle()` runs in two phases over all six zones. Phase one calls `evaluate(now)` on
every zone: freshness, the three residuals, both CUSUM arms, and `BLIND` if the readings are stale.
Only then is the count of elevated zones taken, and phase two calls `decide(signals, now)` on every
zone with that count in hand. The barrier is load-bearing: the count is the common-mode signal that
separates a plant-wide blocked filter from a local stuck damper, so a single-phase loop would hand
the sixth room a count mixed from two cycles. (`report.tex §4.5`, `§7.3`)

### 6.3 Degradation when readings stop

`co2-sensor` dying produces **silence**, which is the failure mode pub/sub gives you for free and
exactly what R-4 is written to detect. Each zone ages past its stale limit of 180 s (three 60 s
sample intervals), enters `BLIND`, reports unavailable and issues no diagnosis; there is no edge
from `BLIND` into `SUSPECT` or `CONFIRMED`, because missing data is not evidence of a fault.
`BLIND` always returns to `WARMUP` rather than into an open case, so the step response is
re-identified before judging resumes. Meanwhile the inner loop drops anything not `GOOD` and keeps
ventilating on its last good reading. (`report.tex §7.3`, `§8.3`, scenario Q-10)

### 6.4 Startup

Ordered by `docker-compose.yml`'s `depends_on` with health conditions rather than by luck:
`physics-simulator`, `co2-sensor`, `damper-actuator`, `supervisor` and `dashboard` wait for
BuildSim to pass `/healthz`, because they have nothing to do before the building exists;
`dcv-controller`, `fault-monitor` and `supervisor` wait for TimescaleDB. `physics-simulator`
registers the six rooms' equipment in BuildSim at boot, which is the only equipment-creating call in
the system. (`report.tex §9`)

---

## 7. Deployment View

Drawn as a **C4 deployment diagram** in `diagrams/deployment.d2` and discussed in `report.tex §9`.
Nested deployment nodes — development laptop, then the Docker engine, then one bridge network —
holding ten container instances, each labelled with the environment it is given.

Summary of what the figure says, since §9 argues it at length: everything runs on one laptop, so
this viewpoint is about **how the system is composed from independent processes**, not about
geography. Four ports are published, all bound to the loopback interface only (BuildSim 9090, the
dashboard 8080, and the broker 1883 and the database 5432 for inspection). No image carries a
compiler: `mvn package` builds the jars on the host and each image copies one over
`eclipse-temurin:25-jre`. Every service carries `restart: unless-stopped`, which is what would make
R-4's container-kill experiment a recovery measurement rather than a manual restart. That
experiment has since been run: `evaluation/results/T-27-container-kills.txt` is the transcript, and
debt D-05 is closed (§11).

`report.tex §9` also records what would be lost by merging components, and where the edge/cloud line
would fall in a real building: the whole control path belongs on a local edge server, because
ventilation must keep working when the internet does not, and only two things belong off-box — a
learned contender's *training*, if one were ever kept, and the seven-day replay analysis, which is
batch work nothing waits on.

---

## 8. Cross-cutting Concepts

### 8.1 Domain model: records that cannot hold an illegal state

Domain types are Java records with invariants in compact constructors, so an illegal state is
*unconstructible* rather than merely untested: `FanSpeed` cannot hold 1.4, `DamperPosition` cannot
hold −0.1, a `VentilationZone` in `DEGRADED` mode refuses a normal control command, and a latched
`FaultCase` leaves `CONFIRMED`/`COMPENSATING` only through `serviced()`. Value objects replace
`String` and `double` throughout — `ZoneId`, `Ppm`, `AirflowRate`, `CommandId`, `CaseId` and the
rest. (ADR-0006, `report.tex §4.4`, `§11.4`)

### 8.2 Ports and adapters, and the testability that pays for them

The dependency rule points inward: `control` and `entity` never reference `boundary`. The core
declares seven ports in domain vocabulary and `boundary` implements them. The payoff is concrete
rather than tidy — because the clock is a port, `evaluation/` drives seven simulated days through
the same classes the containers run in about 350 s of wall clock; because the reading stream is a port,
every control-layer test runs against an in-memory fake instead of Docker. This is the single
property most of the evidence in this project depends on. (ADR-0005, `report.tex §4.4`, `§10.1`)

### 8.3 Data quality, judged at the edge that can see it

`co2-sensor` stamps every reading `GOOD`, `STALE`, `MISSING` or `DUPLICATE`. A failed read publishes
`MISSING` *carrying the last known value*, never a fabricated one, and nothing at all is published
before the first successful read. The comparison for duplicates is exact rather than
tolerance-based, deliberately, because BuildSim round-trips values as fixed-decimal strings and a
tolerance would classify slow genuine drift — a fault — as a duplicate. Downstream, consumers drop
anything not `GOOD` and reject out-of-order redelivery, so QoS-1 cannot rewrite history. **The gap
is in the specification, not the mechanism**: no requirement says what `quality` must mean, so all
of this is a reasonable invention rather than something a test can hold to an agreed criterion.
(`report.tex §7.5`, `§3.2`; scenario Q-15)

### 8.4 Provenance and the journal

Six typed hypertables, every row carrying which process wrote it and which command the observation
is judged against, because a residual that cannot be tied to its command is not evidence. Native
SQL rather than an ORM for the window queries (C-9). One wrinkle worth knowing before changing
anything: the `commands` table is written by **three** services — `dcv-controller`,
`fault-monitor` and `supervisor` — distinguished only by a `source` column, so every damper command
produces at least two rows. (`report.tex §5.3`; R-5, scenario Q-12)

### 8.5 Degradation as a design default

Every optional dependency has a null-object adapter rather than a conditional: `DiscardingJournal`
in the monitor, `SkippedJournal` and `SkippedWorkOrders` in the supervisor. The control layer
therefore has no "unconfigured" case to handle. The dashboard inverts the usual dependency
direction on purpose — every one of its dependencies is optional and it keeps serving when the
broker, BuildSim or the database is down, retrying each on its 5 s tick, which makes it the
instrument for telling whether the rest of the system is alive. Its readiness probe is never
reported DOWN for the same reason: a dashboard restarted because no readings were arriving would be
restarted at precisely the moment an operator needed it. (`report.tex §12`)

### 8.6 Health and readiness

MicroProfile health in the Quarkus services. `MonitorHealthCheck` reports the per-zone monitor state
and goes DOWN only when **every** zone is `BLIND` — one blind room is information, not ill health.

### 8.7 Simulated time

`ClockPort` with a `ScaledClock` driven by `TIME_SCALE`, `START_AT` and `REAL_EPOCH`, which is what
the 60× demo profile uses and what the offline harness uses at full speed. The reproducibility this
buys differs between the two, and the distinction matters:

- **Offline harness — reproducible bit for bit.** `SimulatedClock`, an in-process bus and a fixed
  iteration order mean the draws are identical run to run. This is what R-3's false-alarm count
  rests on, and every requirement is argued from these numbers.
- **Containerised demo profile — not reproducible.** Measured: four runs on identical `SEED`,
  `TIME_SCALE` and `START_AT` confirmed A115 at simulated 10:16, 10:17, 10:19 and 10:23. The sensor
  model draws once per Euler sub-step and the sub-step count follows *real* elapsed time, so host
  scheduling decides how many draws fall inside a simulated minute. The demo profile demonstrates
  the system; it does not measure it.

What simulated time does **not** exercise either way is anything that appears only over real
duration: scheduler jitter, clock drift, broker backpressure, memory growth. (`report.tex §13.7`)

### 8.8 Configuration

MicroProfile Config in the Quarkus services, environment variables in the plain-Java ones, defaults
in code so a container starts without a profile. One honest wrinkle: the monitor's stale limit is
`sampleInterval × staleIntervals` and neither is exposed as a config key, so `CYCLE_SECONDS` (times
`TIME_SCALE`) is the only lever on it.

### 8.9 Units and vocabulary

Units live in field names or are stated in the payload contract, so no consumer guesses: `ppm`,
`watts`, `litresPerSecond`, `position` in [0, 1], `speed` in [0.2, 1.0]. The fixed vocabulary is
the implementation plan's §3, and `aiadocs/NOTES-terminology-en-de.md` carries the English/German
pairs. See §12.

### 8.10 Security

**Nothing to say, and the reason is worth one sentence.** There is no authentication, authorisation,
transport security or secret anywhere in this system: it runs on one laptop, every port is bound to
the loopback interface, and the only credentials are the Compose-local database ones. A real
building deployment would need all of it, and none of it is designed here.

---

## 9. Architecture Decisions

An index, not a rewrite. The records are in `project/decisions/`, one file apiece, each naming the
requirement it serves, the alternative it beat, the price paid, and a one-sentence defence.
Superseded records are kept rather than deleted, because the path the design took is itself
evidence.

| ADR | Decision | Status | Rationale in one line |
|---|---|---|---|
| [0001](../decisions/ADR-0001-use-case.md) | Use case A-06: ventilation fault detection, diagnosis and graceful degradation | Accepted | A stuck damper and a full room look identical to a controller that only sees CO₂, so the autonomous part has to be an inference over what the last command should have achieved — which makes fault injection and degraded operation the use case itself rather than an experiment bolted on |
| [0002](../decisions/ADR-0002-runtime-stack.md) | Java 25 with MicroProfile on Quarkus; MQTT for transport; TimescaleDB as the hot store | Accepted | A sensor that dies must go quiet rather than hang its consumers, and pub/sub is the transport in which silence is the failure mode; reconstructing a diagnosis is a time-window join across four series, which is SQL over time-partitioned data |
| [0003](../decisions/ADR-0003-detection-tiers.md) | Two-tier detection: rules hold authority, a learned model only advises | **Superseded by 0001** | The detections it tiered belonged to the access-control use case; its principle survives as the monitor/supervisor split and as the rule that the CUSUM baseline comes first and a learned contender must beat it on identical seeds to be kept |
| [0004](../decisions/ADR-0004-door-state-ownership.md) | Ownership of BuildSim's door collection | **Superseded by 0001, no successor** | Doors are out of scope; the single-writer observation stays on record, and the same collection-replacement hazard is what keeps the dashboard out of BuildSim's alerts |
| [0005](../decisions/ADR-0005-architecture-style.md) | BCE structure with a port at every boundary crossing | Accepted | A port is what makes the dependency point inward and lets decision logic be tested with a stub instead of a broker — declared in the core, in domain terms, because a contract owned by the adapter leaves the ripple it was meant to prevent |
| [0006](../decisions/ADR-0006-domain-model-and-payloads.md) | Records as domain types; domain events as the internal payload; no DTO layer | Accepted | Translation happens only where the schema is not ours, because for payloads this project defines a DTO would be the same record in another package, and the versioning problem that justifies DTOs does not exist when every process ships together |
| [0007](../decisions/ADR-0007-repository-layout.md) | The project lives inside the course-material repository | Accepted | BuildSim is not a repository and publishes no image, so one tree is what makes the evaluation reproducible from a single clone, at the cost of a mixed commit history |
| [0008](../decisions/ADR-0008-room-topology.md) | Six rooms on corridor A120 behind one shared air handler | Accepted | One handler feeding six dampers is how an office corridor is actually ventilated, it hands the monitor the one cross-room signal that tells a blocked filter from a stuck damper, and it makes keeping five rooms unchanged while compensating the sixth something the supervisor has to earn |
| [0009](../decisions/ADR-0009-healthy-room-constraint.md) | The healthy rooms' airflow bound stays at 5 %, in both directions | Accepted | Loosening what a fan raise may give a healthy room was swept from 5 % to unbounded and bought R-2 exactly zero occupied minutes in the regime it fails, because with every healthy damper on its floor the fan lever is not selective and the plant caps it at 1.43 against the 2.0–3.3 the regime needs; the per-room trim that does reach the limit starves a healthy room to 1033 ppm, so R-2's failure is recorded with its real causes rather than traded against R-7 |
| [0010](../decisions/ADR-0010-plausibility-guards-are-bounds.md) | The plausibility guards are bounds, not filters of the measurement | Accepted | `correctTowards` pulled both guards 30 % of the way onto every reading, turning each from a bound into a lagged filter and each CUSUM arm into a zero-mean drift detector that fired on every occupancy ramp; correcting the lower arm's half and re-anchoring the five rooms a pin moves halves R-3's rate with nothing else moved, while the upper arm's half meets R-3 outright and stays on a branch because three other requirements depend on an occupancy-bounded ceiling seeing an 86 ppm fault |

ADR-0005 and ADR-0006 were the two records that carried a known defect — they argued in the
superseded access-control vocabulary against requirements the project had replaced — and they were
retargeted on 2026-10-05 and then checked line by line against the source tree, which found three
further statements about the code that were wrong in the retargeted prose (§11, debt D-14, closed).
`decisions/README.md` still lists three decisions as open — simulated time, dashboard technology,
and the observer's occupancy dependency — but in each case the code has already taken the decision
and the README itself asks only that it be recorded with its rejected alternatives, so all three are
write-ups rather than open questions. The third is the one that matters, and §10's Q-03 is now the
measurement of what the answer cost.

---

## 10. Quality Requirements

This is the section arc42 adds most to this project, because the system has real measured numbers
and because a quality attribute that is not a scenario with a measure is an adjective.

### 10.1 Quality tree

Branches are the attributes; leaves are the scenarios in §10.2. A leaf with no requirement ID is
marked **(gap)** and reuses the honesty `REQUIREMENTS.md` already applies to its own uncovered work:
nothing in R-1…R-7 constrains it, so no test can name an ID for it.

```
Quality
├── Functional suitability
│   ├── Detection timeliness ............................ Q-01, Q-02, Q-03   (R-1)
│   ├── Detection specificity / false alarms ............ Q-04              (R-3)
│   └── Diagnosis reconstructability .................... Q-12              (R-5)
├── Comfort preservation under fault
│   ├── Compensation effectiveness ...................... Q-05, Q-06        (R-2)
│   └── Unreachable-fault handling ...................... Q-07              (gap)
├── Reliability
│   ├── Graceful degradation on lost sensing ............ Q-10              (R-4)
│   └── Fault containment between rooms ................. Q-08, Q-09        (R-7)
├── Performance efficiency
│   └── Supervisory cycle headroom ...................... Q-11              (R-6)
├── Maintainability
│   └── Testability / change confidence ................. Q-13              (gap)
├── Operability
│   └── Operator visibility of a confirmed fault ........ Q-14              (gap)
└── Data integrity
    └── Reading quality semantics ....................... Q-15              (gap)
```

**Four** of the seven branches have a leaf with no requirement ID — counted off the tree above:
unreachable-fault handling (Q-07), testability (Q-13), operator visibility (Q-14) and reading-quality
semantics (Q-15). Three of the four are the gap `REQUIREMENTS.md §Not yet covered` and
`report.tex §3.2` record: R-2's bound, the dashboard and data quality. The fourth is testability,
which this tree names and neither of those registers does — which is why Q-13 below reports a
property and not a verdict.

### 10.2 Quality scenarios

Each is **stimulus → response → measure**, with the measured outcome and its verdict. "Measured"
means read off a recorded run; "analytic" means it follows from the closed form and is pinned by
unit tests rather than produced by an experiment.

| # | Req. | Stimulus | Required response | Measure | Measured outcome | Verdict |
|---|---|---|---|---|---|---|
| **Q-01** | R-1 | A damper stops responding to commands, with the building settled at design occupancy and the inner loop off | The monitor confirms a stuck damper | p95 delay from the first ignored command ≤ 15 min, ≥ 20 runs | **p95 6 min**, 20/20 detected at `h` = 150 ppm; p95 stays 5–8 min across `h` = 50…300 ppm, and `fault-monitor`'s own test agrees to the minute against a separate physics implementation | **Met** |
| **Q-02** | R-1 | The same fault is injected at 08:00 on a drawn weekday, inner loop commanding and supervisor live | The monitor confirms | p95 ≤ 15 min from the first ignored command, 20 seeds | **p95 257 min**, median 74 min, **15/20 detected**; five injections never confirmed inside 12 h | **Not met** |
| **Q-03** | R-1 | A damper fault becomes observable while the room holds 0.625 of its design occupancy | The monitor confirms within the observation window | Confirmations, stratified by the head count at the ignored command | **0 of 4 confirmed at 0.625** of design; 122 and 257 min at 0.75; 77 min median at 0.875; **31 min median at 1.00**. A stratification of 20 drawn weekdays, not a controlled sweep, over one room at one `h` | **Not met below 0.75 of design occupancy** |
| **Q-04** | R-3 | Seven fault-free simulated days elapse over all six rooms on a fixed seed | The monitor raises no confirmed case | ≤ 1 distinct `caseId` reaching `CONFIRMED` per week | **0.55 per week** over 20 fault-free weeks; 16 weeks clean, worst week 6. Down from 1.30 by ADR-0010, which found the plausibility guards were filtering the measurement rather than bounding it. The rate is driven by a minority of weeks, which is what a tail-draw mechanism looks like, and the criterion is scored on the worst of them | **Not met** |
| **Q-05** | R-2 | A damper freezes at the 0.15 floor on a drawn weekday while the room is occupied | The supervisor degrades the room and compensates | Zone CO₂ ≤ 1000 ppm for ≥ 95 % of occupied minutes after onset, 5 seeds | **1.000 of occupied minutes, 5 of 5 seeds** — unchanged with the lower CUSUM arm off and with the harness's damper feedback off, so neither is doing the work; the no-fault reference is 1.000 and 5 of 5 | **Met** |
| **Q-06** | R-2 | The same fault, with every room simultaneously at its design head count | Compensation holds the faulted room | Same criterion, 5 seeds | **0.022 supervised** against 0.009 unsupervised — the loop is worth **+0.012** and the fault costs −0.991 — **0 of 5 seeds**; the solver's own comfort rule asks for **2.3×** the room's flow and the plan reaches 1.05–1.77 over the five seeds. The no-fault reference still meets it 5 of 5 | **Not met** |
| **Q-07** | *(gap)* | A damper is genuinely shut at opening 0, so no air path exists | A critical work order with occupancy advice, and **no** compensation command | `compensates()` false, achieved gain exactly 1.000, no damper command issued | As designed: gain **exactly 1.000**, no command, critical work order; comfort measured for the bound at 0.153 of occupied minutes | **Behaviour correct, but no requirement constrains it** — ADR-0008 and `REQUIREMENTS.md` record the bound, and nothing asserts it as a criterion |
| **Q-08** | R-7 | A fault is confirmed and latched in one of the six rooms | The other five keep their control mode and their air | 0 fault events, 0 mode changes, CO₂ and airflow inside the fault-free band (5 % tolerance), injected room rotated over ≥ 3 rooms | **Drawn weekday: 0 of 270 observed rooms failed**, rotating the injected room over all six, 3 seeds, 3 configurations; airflow deviation 0.0000. **Design occupancy: 231 of 270 failed** — 215 rooms accused of a fault they did not have, 16 outside the airflow band at a worst 0.058919, the two sets disjoint | **Met on the drawn weekday, not met at design occupancy** (D-27). The caveat the drawn arm carried is now answered rather than outstanding: it said the mode clause went **unexercised** because every supervised plan trimmed by exactly 1.00, and named the honest re-test as an experiment that forces a trim. The design arm is it — 40 of 540 rooms reach `COMPENSATING` — so the literal mode clause fails on 40 rooms and ADR-0008's own reading fails on 16, and choosing between the readings no longer rescues the requirement. `DEGRADED` stays 0 of 540, which is the half that matters |
| **Q-09** | R-7 | The solver is asked for a gain of 2.0 with the fan at its normal 0.70 | Every healthy room's flow is preserved exactly | Healthy flow deviation | **0**, with trim 0.500, gain **exactly 2.000** and the fan raised to 0.8012. At a requested gain of 4.0 with the fan **already at 1.00** the achieved gain is **1.296** and exactly 5 % is taken from each healthy room | **Met (analytic**, pinned by `CompensationSolverTest`) |
| **Q-10** | R-4 | `co2-sensor` stops publishing | Every zone reports unavailable and issues no diagnosis; recovery when readings return | State `BLIND`, 0 fault events, 0 damper commands on stale data | **23 of 23 assertions**: `BLIND` within **4 cycles** of the kill (three sample intervals plus one cycle), 6 of 6 zones reporting unavailability, **0** fault events while blind, **0** commands on stale data, `HEALTHY` 3 cycles after readings return | **Met in one JVM and at container level** — `evaluation/results/T-27-container-kills.txt` is the transcript of the `docker compose` run, which also exercises the restart policies, the broker's session state and the reconnect paths that an in-JVM kill cannot reach (§11, D-05, closed) |
| **Q-11** | R-6 | The zone count served by one monitor is raised | The cycle stays inside its interval, and the count where it does not is reported | p95 cycle time against zone count, and the saturated element named | **25 600 zones at p95 6.008 s** on the committed run (5.676, 5.740, 5.759, 6.008, 6.052 over five runs of identical code), inside the 60 s interval; fit p95 = 1.196×10⁻⁸·n^1.97, r² = 0.9991 over the nine points at 100 zones and above, crossing 60 s at **≈ 86 000 zones — an extrapolation, labelled as one**. Saturated element named: `AhuFlowModel.believedFlowTo`, Θ(n²) per cycle. Reading ingestion stays at 0.89 ms p95. The **deployed** stack is a different number and a much smaller one: 58 zones, bounded by BuildSim's floorplan rather than by the monitor (D-13, D-30) | **Met in the harness**, and the two ceilings must never be quoted as one |
| **Q-12** | R-5 | An operator asks for the diagnosis behind a `caseId` | Inputs, command, residual series, classification and timestamp are all returned | All five recoverable for any `caseId` | Schema and endpoint exist (`GET /api/cases/{caseId}`), the replay uses the same codec that published the events, and a live rebuild against a real TimescaleDB returned 445 events / 77 248 bytes for one `caseId`. **Eight tests now execute `replayCase`'s real SQL against a real PostgreSQL** — the `?::jsonb` cast, the per-zone window scans, the case window, and the one-hour evidence lookback pinned at −59, −60 and −61 minutes. They skip cleanly where no database is reachable, which is the suite's 8 skips. Measured bound, not assumed: a `timestamptz` round trip rounds to the nearest microsecond, so reconstruction fidelity is half a microsecond — unreachable in deployment, where every journalled instant is a whole simulated second | **Met: demonstrated live and tested** |
| **Q-13** | *(gap)* | A developer changes the control logic | The suite runs fast enough to run on every change | Wall clock for the full suite | **523 tests, 0 failures, 0 errors, 8 skipped** over nine reactor modules in well under a minute; the ten-experiment offline harness in **350 s**. The 8 skips are Q-12's journal integration tests, which need a database container and are skipped by design rather than failing | **Property holds; no requirement states a budget**, so it is not a verified criterion |
| **Q-14** | *(gap)* | A stuck damper is confirmed in one room | An operator sees which room and which fault, and that it is still unrepaired after compensation | Freshness and what must be visible | The dashboard repaints four layers every 5 s, and the `state` layer is ordered so a latched room stays the hottest thing on the floor even after compensation pulls its residual to zero. Seven frames from a live run are captured in `report.tex §12`. **No requirement still states what must be visible or how fresh** | **Gap in the specification; the evidence now exists** (`report.tex §12`) |
| **Q-15** | *(gap)* | A reading arrives duplicated, out of order, or not at all | The system distinguishes the cases and never fabricates a value | What `quality` must mean, and what must happen to each case | Mechanism exists and is stronger than the requirements ask: four quality states, `MISSING` carries the last known value, consumers drop non-`GOOD` and out-of-order redelivery. **Nothing requires any of it** | **Gap in the specification, not the mechanism** |

### 10.3 The trade-off the tree does not show

Q-01 and Q-04 are coupled through a single number, the CUSUM limit `h`, and the coupling is
measured rather than asserted: **`h` is a false-alarm parameter, not a speed parameter.** Sweeping
it from 50 to 300 ppm — 1.7σ to 10σ — moves the stepped p95 only from 5 to 8 min, so buying R-3
margin by raising `h` is nearly free in R-1 terms and still was not enough; `h` is left where the
spike put it rather than retuned until R-3 passes. Q-05 and Q-08 are coupled the other way:
compensation works by trimming healthy dampers, so where a plan *does* trim, the healthy rooms'
mode must change for their airflow to be held — and R-7's literal wording would then contradict the
design it was written for. (`report.tex §3.1`, `§11.1`, `§11.2`)

---

## 11. Risks and Technical Debt

**As of 2026-10-06, against `main` at `f1b52ec`.** This register is re-read against the repository
after every batch of work, and this pass follows eleven tasks landing in parallel and one commit
that regenerated every experiment on the merged code, so that no number here comes from a run of a
subset of it. **Nine debts closed in this pass and four were added**, three of them found by the work
that closed the others.

Closed entries are kept, struck through and marked **CLOSED** with the evidence that closed them.
That is the point of the register: it records what was wrong and when it stopped being wrong, and
report §13 refers to it by number. Nothing here is softened. Several entries are **functional
holes** — things that do not work, rather than things that are merely unvalidated — and they are
listed first because they are worth the most to find.

Two habits this project keeps and this register records are worth naming, because both produce
entries that look like failures and are not. The first is the **measured refusal**: a change
implemented, measured and then not kept, with the measurement published (D-02's upper-arm guard,
D-08's learned contender, D-26's probe reordering). The second is **reporting the regime**: where a
requirement passes in one occupancy regime and fails in another, both numbers are stated and neither
stands alone (D-03, D-27).

### 11.1 Requirements not met

| # | Debt | Measured state | Why it matters |
|---|---|---|---|
| **D-01** | **R-1 fails in closed loop** | 15 of 20 detected; median 74 min, **p95 257 min** against a 15 min budget; five injections never confirmed inside 12 h (`T-24-detection-delay.csv`, closed-loop arm at h = 150) | The cause is observability, not detector latency: the CUSUM is driven against a ceiling computed at 1.25 × design occupancy, while the controller issues its first ignored command as soon as the room passes 800 ppm, which on a weekday happens with far fewer people present. R-1's clock starts at the ignored command; the physics does not start until later. The stepped arm detects 20 of 20 at a median of 6 min, which is the same detector on the same seeds with the step supplied — so the gap is the building, not the statistic |
| **D-02** | **R-3 fails, and the failure is attributed** | **0.55** distinct confirmed cases per fault-free week against a limit of 1, over 20 weeks; **16 clean**, worst week 6 (`T-25-baseline-comparison.csv`) — 1.30 per week with 9 clean before ADR-0010 | Still the requirement the design does not meet, because the harness scores the **worst week** and that is unchanged at 6. What changed is the half that was hard: the mechanism is named and was measured rather than argued. `ZoneObserver.correctTowards` pulled *both* plausibility guards 30 % of the way onto every reading, so neither was a bound — each was a first-order filter of the measurement, and each CUSUM arm was running on a filter error rather than on a residual. Of the 26 false confirmations, **13 were primary and 13 cascaded** through the believed damper opening a confirmation pins in `AhuFlowModel`, and **17 were on the upper arm** against 9 on the lower. The lower arm's half and the cascade are corrected; the upper arm's half meets R-3 outright at 0.00 per week with 20 clean weeks, and was **declined on evidence** because it takes R-1's closed-loop detection from 15 of 20 to 1 of 20, T-27 from 23 of 23 assertions to 19, and R-7 from 0 of 270 to 2, with no setting of the ceiling's headroom that recovers them (ADR-0010) |
| **D-03** | **R-2 fails at design occupancy and passes on the drawn weekday** | Drawn weekday: **5 of 5 seeds** pass, in all three variants. Design occupancy: **0 of 5**; 0.022 of occupied minutes at or below 1000 ppm supervised against 0.009 unsupervised, so the supervisory loop is worth **+0.012** and the fault itself costs −0.991 (`T-26-comfort.csv`) | Compensation redistributes flow and buys a bounded amount of new flow from the fan; a building already at design occupancy needs more than either gives. The solver's own comfort rule asks 2.3× the flow against the 1.05–1.77 the plan reaches over the five seeds, and the plant's own bound — every unlatched room at its minimum, fan at its ceiling — holds the faulted room *and* every healthy room under 1000 ppm on only **2 of 5** seeds. The no-fault reference meets the criterion 5 of 5 in the same regime, which pins the failure to the fault rather than to the plant's sizing. Case B, a damper genuinely shut rather than frozen at 0.15, reaches 0.153 of occupied minutes and no plan at all: there is no air path to multiply |
| **D-04** | The healthy-damper trim was the only lever, and on a weekday every damper sits on its 0.15 floor | Before the fix, every plan came back `trim 1.00, fan 0.70, gain 1.00`, the supervisory loop was worth **+0.000** of occupied minutes, and at design occupancy it left the room **34 ppm worse** than no supervisor at all | Found by the shape of the experiment — T-26 runs an unsupervised arm beside the supervised one — rather than by reading the code. Fan speed is now spent once the trim is exhausted, but the fan lever is **not selective**: with no trim in force the frozen room's gain equals the healthy rooms' over-supply exactly, so it is bounded by the 5 % healthy tolerance rather than by the plant's ceiling. The 0.05 cases per week once charged to this lever against R-3 is withdrawn rather than renumbered: the allowance was swept from 0.05 to 0.99 with R-3 unmoved at every setting (ADR-0009), and ADR-0010 attributes those confirmations to the plausibility guards and to the pinned flow belief instead. Its separate cost to R-3 has not been re-measured since the fix |
| **D-27** | **R-7 fails at design occupancy and passes on the drawn weekday** | Drawn weekday: **0 of 270** observed rooms fail → pass. Design occupancy: **231 of 270** fail → FAIL (`T-34b-room-isolation.csv`, 540 rows over both arms) | Added in this pass, by an experiment arm that did not exist before. The two failing clauses are **disjoint and attribute in opposite directions**: 215 observed rooms were themselves **accused of a fault they did not have**, and a further 16 sat outside the 5 % airflow band — 215 + 16 = 231, so neither clause is standing in for the other. The supervisor-dead pass separates them: accusations run 85 of 90 rooms with the supervisor dead against 130 of 180 with it live, so they are the regime and the monitor rather than the response to the fault — **this is D-02's false-alarm rate arriving inside R-7's experiment**. Airflow breaches run 0 of 90 dead against 16 of 180 live, so that half is the compensation's own cost and nothing else's. ADR-0009 predicted exactly this collision and said no experiment asserted R-7 in that regime; one does now, and they collide |

### 11.2 Evidence that does not exist yet

Every entry in this subsection is now closed. It is kept because report §13 cites these numbers and
because the pattern — a requirement resting on an argument until an experiment is written for it —
is the one this project most wants to be able to show it broke.

| # | Debt | State |
|---|---|---|
| ~~**D-05**~~ | ~~**R-4 has no container-level chaos run.**~~ | **CLOSED.** `evaluation/results/T-27-container-kills.txt` is the raw transcript of a container-level run on the demo profile: declared restart policies for all ten services, a baseline, each kill, and the journal queries around it. The in-JVM harness still carries the 23-of-23 assertion table (`T-27-resilience.csv`, 23 of 23 pass); the container run is what shows Docker's restart policy, the broker's session state and the reconnect paths behaving, which the in-JVM kill cannot |
| ~~**D-06**~~ | ~~**R-5 rests on the journal rather than on an integration test.**~~ | **CLOSED.** Eight tests execute `TimescaleJournalAdapter`'s real SQL against a real PostgreSQL — the `?::jsonb` cast, the per-zone window scans, the case window and the one-hour evidence lookback, pinned at −59, −60 and −61 minutes. No new dependency. They skip cleanly on a machine with no database, which is why the suite reports `Skipped: 8` by design. One measured fact came out of it that no argument would have produced: a round trip through `timestamptz` **rounds** to the nearest microsecond rather than truncating, so R-5's reconstruction fidelity is bounded at half a microsecond. Nothing in the deployment reaches that bound, because every instant the system journals comes from a clock ticking in whole simulated seconds |
| ~~**D-07**~~ | ~~**No confusion matrix, and one would be weak evidence anyway.**~~ | **CLOSED,** and the second clause turned out to be the finding. `T-15-confusion-matrix.csv` carries **218 runs** over five injected conditions and two arms. It is weak evidence in exactly the way predicted and the matrix is what shows it: **one** of the mechanisms is classified correctly (D-12, D-28) |
| ~~**D-08**~~ | ~~**The learned contender (T-29) was not attempted.**~~ | **CLOSED and dropped on the numbers.** A one-class novelty detector over the same features, 30 operating points, fitted on commissioning data and judged on a held-out week. At the monitor's detection rate of 0.75 its closest alarm-volume point raises **11.25 alarms per fault-free week against the monitor's 0.55** — about twenty times — at a median delay of 518 min against 74. None of the 30 points reaches the incumbent's detection without raising more false alarms, and the reason is structural rather than tuning: the contender scores each minute on its own while the CUSUM accumulates, so its only route to an in-control run length is a persistence requirement, which is the one part of it that is hand-set and not learned |
| ~~**D-09**~~ | ~~**No dashboard screenshot has been captured.**~~ | **CLOSED.** Seven captured frames in report §12 from a live run: the status page not firing and with a case confirmed, the BuildSim work-order overlay, and the four map layers. The second clause of the original entry stands — no requirement states what the view must show (Q-14) — and is now carried by D-29 |

### 11.3 Functional holes in the code

| # | Debt | State |
|---|---|---|
| ~~**D-10**~~ | ~~**`serviced()` has no transport, so a latched false positive cannot be cleared in deployment.**~~ | **CLOSED.** `ServiceReported` on `service/{zone}` — deliberately *not* under `commands/`, because the topic carries a report about the world rather than an instruction to an actuator — a `ServiceRequests` boundary adapter that validates it, and `POST /api/zones/{zone}/cases/{caseId}/serviced` answering 202, 404, 409 or 503. The full `CONFIRMED → COMPENSATING → SERVICED → WARMUP → HEALTHY` lifecycle is executable and tested end to end, including that a reported repair unwinds the compensation. **A false latch is no longer permanent**, so the latching trade-off can be argued as designed: a false positive costs one technician visit and one HTTP request. There is deliberately no button on the HTML status page — it is server-rendered with a meta refresh and no JavaScript, so a form post would land the operator on a JSON body; the endpoint is the operator path |
| ~~**D-11**~~ | ~~**There is no `DAMPER_STUCK_OPEN` classification constant.**~~ | **CLOSED.** `FaultClassification` now holds six constants and three things branch on the new one: the classifier's low-side leaf, the supervisor's work-order narrative, and its severity — a damper stuck open is WARNING rather than CRITICAL, because the room is over-ventilated and safe while the other five are short. The two extremes are deliberately re-joined by `isUnresponsiveDamper()` for the one purpose where the direction is irrelevant: pinning the shared flow belief (ADR-0010, R-7). **It is a wire-format break for an older subscriber** and a worse one than a mis-read: `valueOf` throws, `EventCodec.decode` propagates, and `MqttAdapter.dispatch` logs "dropping undecodable payload", so an old supervisor would drop the whole fault event and not compensate at all. That costs nothing here because all ten services are built from one reactor and move together — but `decisions.classification` is TEXT and outlives a deployment, so a downgraded dashboard replaying a `DAMPER_STUCK_OPEN` row would throw out of `replayCase`. Recorded rather than coded around, per the project's rule against compatibility shims. **Closing it does not make the diagnosis reachable** — see D-17 |
| **D-12** | **The `BLOCKED_FILTER` branch is unreachable with this physics** | Two structural reasons, neither a bug to patch. The proportional split only redistributes a fixed total, so no damper command can move all six residuals the same way, and the observer judges step responses to commands. And the generation estimate absorbs the blockage exactly: true flow is low by (1−β), believed flow is high by the same factor, so the estimate comes out high by precisely 1/(1−β) and the two errors cancel in the predicted steady state. Pinned by a test that asserts `UNCLASSIFIED` so the finding cannot rot. **Of the faults the project set out to name, exactly one is classified**: `STUCK_DAMPER`. `BLOCKED_FILTER` can be pointed at but never confirmed, and `SENSOR_DRIFT` is never emitted at all (D-28) |
| ~~**D-13**~~ | ~~**R-6's capability is not wired into any container.**~~ | **CLOSED, with a ceiling the wiring exposed.** `ZONE_COUNT` is settable from the environment and all seven project-authored containers read the same one, the way they already share `REAL_EPOCH` and `TIME_SCALE`; the default six-room corridor is unchanged end to end. The stack was brought up and observed at 12 and at 58 zones. **58 is a hard ceiling** and it is BuildSim's, not the monitor's: it refuses to host equipment in a room its floorplan does not have, so a deployable zone must be named after a real one, and level 0 of the A building has 52 further rooms of comparable size. `ZONE_COUNT=60` dies with `room "A124S9" does not exist on level "level0"`. So R-6's breaking point remains a **harness** measurement and the report must not claim otherwise — see D-30 |
| ~~**D-14**~~ | ~~**Two accepted ADRs argue in superseded vocabulary.**~~ | **CLOSED,** and it had been false for a day before it was corrected here. ADR-0005 and ADR-0006 were retargeted on 2026-10-05 (`1639a6c`), and restated against the source tree on 2026-10-06 — the second pass finding that the retargeted prose made three wrong statements about the code: an interface inventory that listed five where `src/main` across the ten modules declares **eighteen** top-level interfaces, **nine** of them carrying an abstract method — counted from the compiled class files, which is the basis the earlier pair did not state and could not have passed: its fifteen and eight counted the `shared` module alone, and folded that module's two private nested helper interfaces into the total but not into the tally; a claim that five components carry the full `boundary`/`control`/`entity` structure; and device-process line counts given as "around a hundred each" against 297 and 236. One package was recorded nowhere, which is D-31 |
| ~~**D-15**~~ | ~~**The plausibility ceiling is documented as 1.0 and coded as 1.25.**~~ | **CLOSED** by correcting the documentation to the code, and kept here because it is the cleanest example in this register of the failure mode the register exists to catch: the one parameter that decides how desensitised the detector is had two different numbers written down, and the code is what runs. ADR-0010 now argues the 25 % of headroom rather than recording it twice |
| ~~**D-16**~~ | ~~**Two unreconciled probe step sizes.**~~ | **CLOSED.** `MonitorParameters.probeStepIncrease` was 0.20 and `SupervisionService.PROBE_STEP` was 0.25, and the consequence was worse than duplication: the monitor *asks* in the evidence an operator reads and the supervisor *acts*, so the system published "step to 0.35" on `faults/{zone}`, journalled that, and commanded 0.40. **It was telling an operator one thing and the building another.** One owner now, in `shared`'s `diagnosis/entity` layer, because the amplitude must mean the same thing to the component that requests it and the one that commands it. The reason the divergence survived is worth keeping: the request is prose inside `FaultStateChanged.evidence`, a `List<String>`, so the supervisor could not read the amplitude off the wire without a schema change four components decode. The change was taken on the operator-facing argument, **not** on a measurement — no requirement verdict moved — and it had one measured side effect, recorded as D-32 |
| **D-17** | **A damper stuck wide open is invisible at the deployed setting** | Unchanged by D-11's closure, and sharpened by it: the *constant* now exists and the *detection* still does not. The statistic is two-sided and the deployed default is the **guarded** lower arm, which accumulates only on readings below the unconditional floor — the sensor-drift signature. What the guard cannot catch is the other low-side fault: "everybody left the room" explains a room that stopped getting stuffier at least as well as "the damper ignored the close command". The unguarded setting sees both and costs six false confirmations per fault-free six-room week against R-3's limit of one. An observer with no occupancy signal **cannot** have both, and the choice was made in R-3's favour. `TwoSidedDetectionTest.shouldNotDetectADamperStuckWideOpenWhileTheLowerArmIsGuarded` pins the miss |
| **D-28** | **`SENSOR_DRIFT` is never emitted, and the leaf's two conditions are individually reachable** | Added in this pass, and it replaces a wrong diagnosis rather than adding a new one. The register and the report both said the branch was unreachable because the *fault model* could only inject a ramp. So a step offset was added to the model — `FaultKind.SENSOR_OFFSET`, a sensor whose zero jumps once and holds, injectable through the unchanged `KIND:TARGET:MAGNITUDE:ONSET` grammar — and the branch is **still** unreachable: 0 of 76 runs with a ramp injected and 0 of 76 with a step. The leaf wants an informative overnight floor **and** a matched step response in the same instant, and the two mechanisms fail it in complementary places: of the runs that confirmed, the ramp reached an informative floor 12 times and a matched step 0 times, the step reached a matched step 15 times and an informative floor 0 times, and **neither reached both once**. The binding constraint is that the classification is computed at the confirmation while one of its inputs needs a day of the fault. It is a property of the classifier, not of the generator |
| **D-26** | **The step-response signal is blind after a confirmation, not merely late** | Added in this pass, by a change that was implemented, measured over 176 paired runs and **not kept**. The stuck-damper leaf accepts `NOT_JUDGED` because a probe can only be requested once a case is latched, so at classification time no step has been judged. Reordering the pipeline to withhold the diagnosis until a probe has landed was built and measured: the case latches on the same cycle (detection moved in 0 of 88 pairs), the probe is judged before the fault is named in 28 of the 39 runs that defer against 0 before — **and the settled classification is identical in all 88 pairs**, because the judged step returns `MISSING` in all 28 of them — for a stuck damper, a blocked filter, nine drift runs whose damper is in perfect health, and the run with nothing injected at all; no deferred run ever read `MATCHED`. The located cause: `ZoneObserver.recordStepAmplitude` accumulates the measured leg against `predictedAtStep` while the predicted leg is also against `predictedAtStep`, so the measured leg carries the **standing residual** — and the anchor-gain pull that would make that baseline legitimate is switched off while a case is open, so at the confirmation the prediction is stale by exactly the residual that confirmed it. `ZoneObserver.measuredAtStep` is assigned and never read. Correcting the baseline was measured too, as an unlanded counterfactual, and makes the reorder **worse**: 18/44 → 15/44 on the diagonal, because by probe time the room's answer is the compensation's rather than the damper's. The 18/44 reproduces on the current code; the 15/44 was measured on a one-line patch that was then reverted, and is the one figure in this register not re-derivable from the repository as it stands. So a three-signal diagnosis needs a step judged *before* a case opens, not a probe after it |

### 11.4 Limits on what the evidence can mean

| # | Risk | State |
|---|---|---|
| **D-18** | **The detector is evaluated against its own generator** | `physics-simulator` freezes a damper, scales flow by (1−β), ramps a sensor or offsets one, and `fault-monitor` looks for exactly those things using the same mass balance and the same flow split. Every detection result is therefore a measurement of self-consistency between two pieces of one person's arithmetic. R-1's delay, R-3's rate and R-2's percentile are **conditional on the generator** and say nothing about a real air handler. It does *not* invalidate the architectural claims, which are properties of code. Removing it needs measured fault data from a real unit, or at minimum a second fault model written independently |
| **D-19** | **The ambiguous fault is absent from the model** | A partial obstruction in one duct branch reads as both a partly stuck damper and a partly blocked filter, and the fault enumeration cannot express it. In this simulator the signatures cannot collapse, so the classifier's hardest discrimination is never tested. Adding `SENSOR_OFFSET` did not change this: it is a fourth clean mechanism, not an ambiguous one |
| **D-20** | **Every physical parameter is invented** | The 3 m ceiling, the opening law, Q_nom, P_nom, the 0.15 damper minimum, β, the drift ramp, the whole occupancy schedule — and the **affinity exponent 3**, which is asserted rather than derived and is shared by the generator and the detector, so the blocked-filter power test cannot fail except through β. First-order on R-1: ceiling height, the design-flow rule, the schedule. First-order on R-2: the damper minimum and the plant sizing. Removing this needs a commissioning report |
| **D-21** | **Margin masks faults, and the trade was made deliberately** | The plant was re-sized so it covers its design condition at the speed it actually runs; at the previous rating, nothing broken, no room could be held below 1000 ppm at design occupancy and the monitor confirmed a fault in all six, because a uniformly starved building looks exactly like a faulty one. The honest cost of the fix: at the corrected rating a filter blocking 35 % no longer starves the rooms enough to be confirmed. Detection sensitivity was being bought with occupant comfort, which is the wrong currency |
| **D-22** | **No DTO layer, so a domain rename is a wire-format break** | Justified by lock-step single-author deployment and wrong the moment any consumer deploys independently. D-11 is the first time this cost something concrete rather than hypothetically |
| **D-23** | **No message replay** | R-3's evidence comes from a seeded harness rather than from the broker, so the evaluation never needs replay. In a real deployment a monitor restart loses the residual history it was accumulating, and R-5 then rests entirely on the journal — which is now tested (D-06) but still the only copy |
| **D-24** | **Simulated time exercises nothing that appears only over real duration** | Scheduler jitter, clock drift, broker backpressure, memory growth. R-6's cycle time is the only result that touches timing at all |
| **D-25** | **The broker is a single point of failure** | A dead broker stops readings flowing. Mitigated rather than solved: the restart policy, and the fact that a dead publisher produces silence, which drives `BLIND` rather than a wrong decision |
| **D-29** | **`CompensationPlan.worstHealthyFlowDeviation()` cannot falsify R-7** | Added in this pass. It reaches 0.0500 and no further in any regime, because the solver caps the fan at the speed that makes the figure exactly `HEALTHY_FLOW_TOLERANCE` and then reports that cap — so it is the solver quoting its own constraint back. The independently recomputed `FlowSplit` deviation is the only one of the two bounded figures that can fail, and it does, at a worst 0.058919. The earlier framing of "the two airflow columns that pass everywhere" was quoting a tautology alongside a measurement. Related and unresolved: **the 5 % bound's reference plant is ambiguous**, and this is an ADR-level question rather than a code change. The solver bounds its claim over each latched room's **latch-time** opening while the independent recomputation uses its **pre-latch** opening; confirmed numerically on one seed, the same room gives 48.7940 against 38.4015 and the ratios 1.050000 against 1.058919. The two coincide until a second room latches inside one episode — which the drawn weekday never does and design occupancy routinely does. Both are defensible readings of "before the compensation"; ADR-0008 consequence 9 never says which |
| **D-30** | **R-6's deployed ceiling and its extrapolated one differ by three orders of magnitude** | Added in this pass. T-28 measures 25 600 zones at a p95 cycle of 6.008 s — inside the 60 s interval — and extrapolates the crossing to **about 86 000 zones** (the committed CSV fits 85 992), an O(n²) fit whose saturated element is named: `AhuFlowModel.believedFlowTo` sums every zone's opening and every zone asks it. The **deployed** system tops out at 58 (D-13). The extrapolation also moves between runs of identical code — 87 338, 88 109 and 87 521 are on record from earlier runs against 85 992 from the committed one — so it carries one significant figure and that spread, about two thousand zones, is its only error bar. The report must say which of the two numbers it is quoting every time it quotes one |
| **D-31** | **A fourth package exists that the architecture description does not mention** | Added in this pass. `shared/src/main/java/se/ltu/d7065e/shared/` contains `boundary`, `control` **and `event`** — the latter holding `DomainEvent`, its eight permitted records and `Topics`. The documentation describes the structure as `boundary`/`control`/`entity` throughout. ADR-0005 and ADR-0006 now justify the package (it carries no framework import, and the alternative is a dependency from every subscriber onto every publisher), but it is visible from a directory listing and the architecture section has to name it |
| **D-32** | **The design-occupancy regime is not merely failing but unstable** | Added in this pass, as a by-product of D-16. Unifying the probe amplitude — a change of 0.05 in one number, taken for an operator-facing reason with no requirement verdict moving — moved **ten of T-34b's design-occupancy rows from pass to FAIL**, by causing one extra room to be falsely accused on one seed. It is D-02's false-alarm rate again, surfacing through a third experiment, and it says the 231-of-270 figure in D-27 should be read as a region rather than a point |

### 11.5 Found while writing this document, in files this document does not own

Reported rather than fixed, because they are outside the documentation's scope:

- ~~**The process counts were wrong in four places.**~~ **Now fixed.** `docker-compose.demo.yml`
  said "the same nine containers" twice, `physics-simulator`'s `Main.java` said "all nine agree on
  what time it is", `project/Makefile` claimed the folder set and the compose service set were 1:1
  apart from `shared/` and `evaluation/`, and `ScaledClock`'s javadoc said both "all nine
  containers" and "six processes". The base file defines **ten** services, **seven** of which read
  the time-scale variables and hold a simulated clock; `buildsim`, `mosquitto` and `timescaledb`
  are services with no `project/` folder and no clock. All four now say seven. `ScaledClock` was
  the last one standing and was found by reading the code rather than the prose, which is the
  lesson: the same wrong number had been copied into four files, so correcting three of them left
  the fourth looking authoritative.
- ~~**`MonitorRuntime`'s javadoc lists three inbound topics and the code subscribes four.**~~
  **Now five, and the javadoc says so.** `commands/+/fan-speed` was the fourth and `service/+` is
  the fifth (D-10). `FaultMonitor`'s javadoc still says "the three subscriptions" of the three
  topics the brief named, which is the brief's count rather than the code's and reads correctly in
  context.
- **The same literal-count defect recurred in a different unit.** `OccupancyScheduler` dispatched
  on the six zone ids and threw `no schedule for zone A105` on the simulator's first tick at
  `ZONE_COUNT=58`, restarting the container eight times — the geometry scaled and the occupancy did
  not — and four runtime messages hardcoded the word "six", so the simulator printed `registered
  six rooms` while registering 58. Both are fixed. It is the same shape as the process-count defect
  above: a number that had no business being a literal, copied.
- **`ZONE_COUNT` is read in the entity layer**, and that is a deliberate layering compromise rather
  than an oversight. `Topology` is an entity-layer interface and now calls `System.getenv`. All
  seven processes must agree on the zone set — the sensor publishes per zone, the controller
  commands per zone, the monitor holds a model per zone — so one process on a different count
  computes residuals against a plant nobody else is simulating. That is why `REAL_EPOCH` and
  `TIME_SCALE` are one variable given identically to all seven, and `ZONE_COUNT` follows them. The
  layer-pure alternative threads a zone set through seven composition roots and about twelve
  production classes, which turns "the services agree" from a guarantee into a convention. The
  existing precedent for environment reading is `ScaledClock` in `shared/boundary`, so the
  compromise is that this reader sits one layer lower than that precedent.
- **A synthetic zone is a real room's name with a copied room's body.** A zone past the sixth takes
  a real level-0 room's name and location so BuildSim can carry its sensor and damper, while its
  volume, floor area, damper authority **and occupancy schedule** are a cycled copy of one of the
  six. `A119` is 109.6 m² in the floorplan and is simulated as a copy of `A125A`'s 18 m². So a
  synthetic zone is representative in size, time constant and head count, and is not a claim about
  the room whose name it borrows.
- **`project/decisions/README.md` lists three decisions as still open that the code has already
  taken.** Simulated time (real-time against accelerated, and how tests pin it), dashboard
  technology, and the observer's occupancy dependency. In each case the container already does what
  plan §3 names and the README itself says the choice is to be *recorded* with its rejected
  alternatives rather than re-opened. So all three are short write-ups rather than open questions,
  and none blocks submission — but while they are listed as open they read as three unmade
  decisions in a project whose argument is that its decisions are recorded.

---

## 12. Glossary

Domain terms first, then the method vocabulary. The implementation plan's §3 is the fixed
vocabulary every module is written against, and `aiadocs/NOTES-terminology-en-de.md` carries the
English/German pairs.

| Term | Meaning here |
|---|---|
| **AHU** | Air-handling unit. One per system here, `AHU-A120`, serving all six zones through a proportional damper split |
| **Affinity laws** | The fan relations used: flow goes as speed, power as speed cubed. The exponent 3 is asserted, not derived (D-20) |
| **BCE** | Boundary–Control–Entity. The package structure: `boundary` holds adapters and framework types, `control` the decision logic, `entity` the domain types (ADR-0005) |
| **BLIND** | The monitor state for a zone whose readings are stale beyond three sample intervals (180 s). It reports unavailable, issues no diagnosis, and has no edge into an open case. Not a fault |
| **BuildSim** | The building server the course supplies: a Go binary holding building state in memory with a REST API and a 3D viewer. It simulates no physics and keeps no history |
| **`caseId`** | The identity of one diagnosis, from suspicion through to service. R-3 counts distinct `caseId`s reaching `CONFIRMED`, not published events, because a latched case republishes its state on every probe |
| **Common mode** | How many of the six residuals are raised at once. Five of six is the threshold that says plant-wide rather than local. Computed at the phase barrier of one monitor cycle (§6.2) |
| **CUSUM** | Cumulative sum control chart. Here one-sided per arm: S = max(0, S + r − k), alarm strictly above h, with k = 30 ppm (1σ) and h = 150 ppm (5σ). Catches a small persistent bias long before a single-sample threshold fires |
| **DCV** | Demand-controlled ventilation. The inner loop: damper position from CO₂, with hysteresis and a freshness check |
| **EARS** | Easy Approach to Requirements Syntax. An optional `When` / `While` / `If` precondition, then *the system shall* a response |
| **Latching** | A confirmed case does not clear itself, because compensation shrinks the very residual that found the fault. The only exit is `serviced()`, which a technician reaches over `service/{zone}` or `POST /api/zones/{zone}/cases/{caseId}/serviced` (D-10, closed) |
| **Plausibility ceiling** | A second CO₂ trajectory integrated in parallel at the room's design occupancy times 1.25, recovered from the damper authority alone. Suspicion is measured against it, so an unmodelled arrival cannot by itself confirm a fault. It is also the mechanism behind D-01 |
| **p95** | Nearest-rank 95th percentile, the ⌈0.95n⌉-th smallest. One definition throughout, because two would be measuring two requirements |
| **Residual** | Measured minus predicted CO₂. Three distinct ones exist: the published residual (against the prediction), the upper-arm driver (against the ceiling) and the lower-arm driver (against the floor). See `report.tex §4.5` |
| **Trim** | The single common factor `t` by which every healthy damper is scaled during compensation. The trim *is* the gain: at the fan speed that preserves the healthy flows, the faulted room's airflow is multiplied by exactly 1/t |
| **Work order** | What the technician receives: a BuildSim alert naming the room, the classification and the evidence for and against all three hypotheses — including when the classifier declines to name a cause |
| **Zone** | One of the six rooms, identified by the bare room name (`A115`), not BuildSim's `level0/A115` key, because the key contains a slash and would split an MQTT topic |

---

## Figure index

All nine figures are D2 sources with a rendered SVG beside each, in `project/diagrams/`, and are
converted to PDF for the report by `project/report/Makefile`. They are shared between this document
and the report rather than duplicated. Render with `d2 --layout=elk`, which is what reproduces every
committed SVG bit for bit.

| File | C4 level / kind | Cited in |
|---|---|---|
| `context.d2` | C4 Level 1, system context | `report.tex §4.1`, arc42 §3 |
| `container.d2` | C4 Level 2, containers | `report.tex §4.2`, arc42 §5.1 |
| `component.d2` | C4 Level 3, components inside `fault-monitor` | `report.tex §4.4`, arc42 §5.2 |
| `code-diagnosis.d2` | C4 Level 4, code — the diagnosis chain | `report.tex §4.5`, arc42 §5.3 |
| `code-compensation.d2` | C4 Level 4, code — the compensation solver | `report.tex §4.5`, arc42 §5.3 |
| `dynamic.d2` | C4 dynamic diagram (supplementary) | `report.tex §8.1`, arc42 §6.1 |
| `deployment.d2` | C4 deployment diagram (supplementary) | `report.tex §9`, arc42 §7 |
| `sequence.d2` | UML sequence diagram | `report.tex §8.2`, arc42 §6.1 |
| `state.d2` | UML state machine, one instance per zone | `report.tex §8.3`, arc42 §6.3 |
