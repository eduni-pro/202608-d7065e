# Substitution seams — where one implementation can be replaced by another, and at what price

This document carries the argument. The figures beside it carry the shape.

`seams-overview.d2` shows the system at component granularity and marks every boundary at which
one implementation can be exchanged for another. `seams-loops.d2` shows how the inner and outer
control loops nest. Nine per-seam figures show one boundary each: the two sides, the contract
between them, and the tier as a colour. None of them explains anything, deliberately — a figure
that has to be read at paragraph length is not a figure. Every condition, every obligation and
every `file:line` is here instead.

Line numbers are as of commit `fc2c69c`. Ports live in
`project/shared/src/main/java/se/ltu/d7065e/shared/control/`, adapters in `.../shared/boundary/`,
events and topics in `.../shared/event/`.

---

## How to read the figures

### Two kinds of seam, which do not buy the same thing

**A contract seam — drawn as a hexagon. Run-time, language-agnostic.** The contract is an MQTT
topic plus the payload record on it. Anything that publishes the right topic with the right
payload is substitutable *whatever it is written in*, and the far side cannot tell. The whole
process can go, or the whole device. This is where a physical sensor or a real DDC panel is
installed.

**A port seam — drawn as a parallelogram. Compile-time, same JVM.** The contract is a Java
interface in `shared/control`, declared by the core in the core's own vocabulary and never a
mirror of the adapter's API (ADR-0005). Swap the adapter class in a composition root; `control`
and `entity` do not change at all. But the replacement has to be Java, on this classpath, in this
process.

A port seam cannot install a device. A contract seam cannot be unit-tested with a stub. The system
has both, on purpose, and at some boundaries it has both at once.

### Three tiers, by colour

| colour | tier | meaning |
|---|---|---|
| green | **Tier 1 — clean** | Replace one side; nothing else changes. |
| amber | **Tier 2 — one stated condition** | Genuinely substitutable, but something named must change with it or be inherited by the replacement. The condition is in this document. |
| red, dashed | **Tier 3 — not a seam** | It looks like a boundary on `container.d2`; crossing it would mean changing code on both sides. |

Tier 3 and the Tier 2 conditions are the useful content. Twelve seams all claimed clean would be a
prettier set of figures and a worse one.

### What the keys mean

`K1`–`K6` are contract seams, `P1`–`P6` are port seams, `N1`–`N4` are the boundaries that are not
seams. Every key has a section below. The contract seams are marked on `seams-overview.d2`; the
port seams and the non-seams are marked on the figure for the boundary they sit at
(`seams-plant.d2`, `seams-transport.d2`, `seams-storage.d2`, `seams-clock.d2`,
`seams-topology.d2`).

---

## The two questions, each answerable by pointing at one line

**"Where do we cut to replace the virtual Java CO2 sensor with a real physical sensor in the
room?"** → **K1**, and only K1. Not at a port: `BuildingStatePort` is a Java interface on this
classpath, and a device in a ceiling is not a Java class. At the wire, where
`readings/{zone}/co2` carries `ReadingObserved`. K1 is Tier 2 and the replacement inherits three
conditions. Then read **N2** for what the simulator is still for afterwards, and **N1** for what a
real room does to the model behind the monitor.

**"Where do we cut to replace the supervisor with a different implementation?"** → **K2**, and
only K2. The three topics it publishes are the complete *control* contract — provided the
replacement also takes over two obligations that are not topics at all, and announces every
fan-speed change on the topic rather than acting by some other route.

---

# Contract seams

## K1 — the sensing seam · Tier 2 · `seams-sensor.d2`

**Contract.** `readings/{zone}/co2` carrying
`ReadingObserved(ZoneId zone, Ppm ppm, ObservedAt at, Quality quality)`, and
`readings/{ahu}/fan-power` carrying
`FanPowerObserved(AhuId ahu, FanPower watts, ObservedAt at, Quality quality)`.

Topic builders `Topics.java:12` and `:16`; records `ReadingObserved.java:9` and
`FanPowerObserved.java:12`; the sample cycle `Co2Sampler.sampleOnce:80-89` with the two sample
methods at `:91-107` and `:110-127`; the one publication point `Co2Sampler.java:137`. The process
subscribes to nothing, so the seam is one-way and a replacement has no inbound contract to honour.

**What a replacement looks like.** Any process, in any language, on any machine that can reach the
broker, publishing JSON on these two topics. No Java, no classpath, no redeploy of anything else.
A Python gateway in front of a Modbus or BACnet device is the ordinary shape of it. The JSON field
names are not negotiated per publisher — see **N3**.

**The "virtual sensor" is two things, and K1 is downstream of both.** `physics-simulator` computes
the true CO2 and `SensorModel.publish:28-31` adds the acquired drift and the 30 ppm Gaussian noise,
writing the *measured* value into BuildSim (`BuildSimStateAdapter.java:43`). `co2-sensor` then
reads it back (`Co2Sampler.java:96`) and publishes it. Cutting K1 replaces the publishing half;
see **N2** for what happens to the other half.

### Condition 1 — it must judge its own data quality, because nothing downstream can

`GOOD / STALE / MISSING / DUPLICATE` is decided only in
`Co2Sampler.SignalHistory.judge:159-169`, inside this process, because this is the boundary that
received the value: no subscriber can tell a fresh 540 ppm from the same 540 ppm repeated for a
minute. The observer may consume only `GOOD` (`Quality.java:13`).

A replacement that always reported `GOOD` would disable R-4's blindness path **silently**. That is
not hypothetical: the evaluation harness already does exactly that, hard-coding `Quality.GOOD` at
`SystemHarness.java:236`, so the gap is demonstrated rather than feared. See **N4** for why the
judgement cannot be reused, only reimplemented.

### Condition 2 — it must publish fan power too, or nothing does

One process owns both topics (`Co2Sampler.sampleOnce:85-88` loops the zones and then samples the
air handler). Fan power is the monitor's only cross-room signal, and it has **no other publisher
anywhere in the system**.

So "replace the CO2 sensor" cuts one topic and "replace the `co2-sensor` process" cuts two. A real
room CO2 sensor does not measure the air handler's power, so this condition is a second device or a
second gateway, not a detail.

### Condition 3 — it must stamp `ObservedAt` on the shared timeline

Under `TIME_SCALE > 1` the timeline is simulated time anchored on one `REAL_EPOCH` handed
identically to every process (`ScaledClock.java:20-37`), and the staleness window the reading is
judged against is a span of that simulated time (`Co2SensorMain.java:45-46`).

A sensor in a real ceiling lives in real time. So a real sensor forces `TIME_SCALE=1` across all
seven processes, and the 60× demo profile in `docker-compose.demo.yml` goes with it. The seam is
clean; the demo is what it costs.

### And see N1

None of the three touches the payload. What a real room changes is what the monitor's residual
*means*, which is a different and larger problem, and a Tier 3 one.

---

## K2 — the supervision seam · Tier 2 · `seams-supervisor.d2`

**Contract in** (`FaultSubscription.java:40-42`, rationale at `:11-26`): `faults/+` carrying
`FaultStateChanged`; `readings/+/co2`; and `commands/+/damper`, which it takes as damper-position
feedback because it has none of its own and sizes the plan from measured air.

**Contract out:**

| topic | payload | site |
|---|---|---|
| `strategy/{zone}` | `StrategyChanged(ZoneId, ControlMode, Double trimmedPosition, ObservedAt, CaseId)` | `SupervisionService.java:325` |
| `commands/{ahu}/fan-speed` | `FanSpeedCommanded` | `:336` |
| `commands/{zone}/damper` | `CommandIssued` — the T-18 active probe only, claimed through `ProbeLimiter` at `:255` | `:265` |

`StrategyChanged.java:13`, with the trim range check at `:26-29` — the one numeric field on the
wire that is not a validated value object, because `null` means "no trim".

**Every consumer, and what each takes.** `dcv-controller` takes `strategy/+` and is the one
load-bearing consumer (`DamperControlJob.java:92` → `applyStrategy`: the loop stops commanding a
degraded room and holds the trim it was handed). `fault-monitor` takes `commands/+/fan-speed` and
*believes* it rather than measuring it (`MonitorRuntime.java:112`). `damper-actuator` takes the fan
speed and the probe command, which it cannot tell apart from the inner loop's own. `dashboard`
takes strategy and fan speed for display.

**Why the seam holds.** Every one of them reaches the supervisor through one of those three topics
and through nothing else. No consumer imports a supervisor class, calls a supervisor endpoint, or
depends on its timing beyond the arrival of a message. A replacement — a different compensation
strategy, a solver instead of a closed form, a human in the loop, a process not written in Java —
is substitutable at this boundary. That is a real result and the one the architecture was built
for.

### Obligation 1 — it must be the only writer of BuildSim alerts

The work order reaches the technician as a BuildSim alert, through `BuildingOverlayPort` (**P2**).
`/api/alerts` is a **whole-collection PUT** against a set held in the adapter *instance*
(`BuildSimAdapter.java:250-264`), so a second writer in a second process does not add a work order
— it erases the first writer's, and the two then erase each other in turn. A distinct id prefix
does not help: the clash is the whole-collection PUT, not the id.

`raiseWorkOrder` and `clearWorkOrder` have exactly **one** production caller in the entire system:
`SupervisionService.java:183` and `:299`.

So a replacement does not merely get to raise work orders. It must be the only thing that does, and
the old supervisor must be *gone* rather than idle — an idle one that still holds the adapter still
owns the collection.

### Obligation 2 — it must journal its own decisions

The `strategies` table is written only by this component (`journal.append` at
`SupervisionService.java:326` and `:337`, tagged `JOURNAL_SOURCE = "supervisor"` at `:60`) and read
only by the dashboard's R-5 case replay (`TimescaleJournalAdapter.java:190`; `CaseReplay.java:44`,
surfaced as `GET /api/cases/{caseId}`).

A replacement that published MQTT and nothing else would leave every case reconstruction two tables
short, and **nothing in the system would report it**. The replay would return a timeline with the
readings, the commands and the residuals, and no record of what was decided about them.

This is the obligation most likely to be missed, because it is invisible from the topic contract and
costs nothing at all until someone asks why a case was handled the way it was.

### Obligation 3 — it must announce every fan-speed change on the topic

The monitor does not *measure* fan speed. It believes `commands/+/fan-speed`
(`MonitorRuntime.java:112` → `flow.fanSpeedBelieved`) and feeds that belief into the AHU flow model
every room's prediction depends on.

A supervisor that raised the fan by any other route — writing BuildSim directly, say, or through a
vendor's own channel — would move air the monitor does not know about, and the extra fan power
would be diagnosed as a blocking filter through the affinity law.

So this is a condition on **how** the replacement acts and not only on what it says: the topic is
not a notification of the action, it *is* the action as far as the monitor is concerned.

---

## K3 — the diagnosis seam · Tier 1, clean · `seams-monitor.d2`

**Contract out** (`ZoneMonitor.java:722` and `:732`): `faults/{zone}` carrying
`FaultStateChanged(ZoneId zone, MonitorState state, FaultClassification classification,
List<String> evidence, ObservedAt at, CaseId caseId)`, and `residuals/{zone}` carrying
`ResidualComputed(ZoneId, Residual, CusumStatistic, ObservedAt, CommandId)` every cycle, fault or
not.

**Contract in** (`MonitorRuntime.java:109-113`): `readings/+/co2`, `readings/+/fan-power`,
`commands/+/damper`, `commands/+/fan-speed`, `service/+`.

Anything that publishes a latched `FaultStateChanged` on `faults/{zone}` is a substitutable
detector — one-sided CUSUM, a Kalman filter, a learned model, a process not written in Java at all.
The supervisor acts on the state and the classification, never on how either was reached.

**The payload invariant is part of the contract and is enforced on both sides of the wire:** a
latched state must carry a classification, even `UNCLASSIFIED` (`FaultStateChanged.java:31-34`).
Without it a replacement could publish a verdict that encodes and decodes cleanly and then throws
when the subscriber rebuilds the aggregate from it. R-5 needs the invariant to hold on both sides
or neither, so it sits in the record rather than in the publisher.

**An honest qualification on the Tier 1.** The project already has a second detector in the tree —
`OneClassNoveltyDetector`, the T-29 learned contender the harness scores the CUSUM against
(`LearnedContenderExperiment.java:18-32`) — which shows the detection logic is genuinely
replaceable. But it is scored *inside* the harness and has never been run as a second publisher on
`faults/{zone}`. So Tier 1 here is argued from the dependency direction, not demonstrated by a
swap, and that is weaker evidence than **P2** and **P4** can show.

**The smaller print.** K3 carries the verdict, not the model the verdict was reached against. A
replacement detector inherits the same problem the present one has: the residual means something
only relative to `Topology.java`. See **N1**.

---

## K4 — the inner-loop seam · Tier 2 · `seams-actuator.d2`

**Contract out** (`DamperControlJob.java:122`): `commands/{zone}/damper` carrying
`CommandIssued(ZoneId zone, DamperPosition position, ObservedAt at, String reason,
CommandId commandId)`. **In** (`:91-92`): `readings/+/co2` and `strategy/+`.

The rule inside is as replaceable as the project's own critique wants it to be — it is deliberately
a plain band with hysteresis, opening above 800 ppm and closing below 650 — on two conditions.

### Condition 1 — it does not own its output topic

`commands/{zone}/damper` has **two** publishers: this loop and the supervisor's diagnostic probe
(`SupervisionService.java:265`). So "replace the thing that commands dampers" is not one cut, and a
replacement written as though it were the sole authority would fight the probe. The actuator cannot
tell them apart and applies both — which is deliberate, because the probe is supposed to move a
damper the inner loop has stopped commanding.

### Condition 2 — every command must carry a `CommandId` and a reason

The monitor keys its prediction on the `commandId` (`CommandIssued.java:9-11`): that id is how a
residual is tied to the command it judges. The record also refuses a blank reason (`:20-22`).

A replacement control law that omitted either would compile, publish and actuate correctly, and
would leave the monitor unable to attribute anything it measured. The invariant is enforced in the
record's compact constructor, so a Java replacement cannot get it wrong — a non-Java one can.

---

## K5 — the actuation seam · Tier 2 · `seams-actuator.d2`

**Contract in** (`DamperActuatorMain.java:55-56`): `commands/+/damper` carrying `CommandIssued` and
`commands/+/fan-speed` carrying `FanSpeedCommanded`. The process publishes nothing and journals
nothing, so the seam is one-way: a real DDC panel subscribes the same two topics and the rest of
the system is unchanged.

It is also **the only writer of actuator state anywhere** (`HvacCommandApplier.java:73` and `:88`,
asserted at `:18-20`), so there is no second writer a replacement would have to coordinate with.

### Condition — it must keep the validation and the floor

This process clamps a damper command **up** to `Topology.MINIMUM_POSITION` = 0.15 rather than
rejecting it, because ventilation never shuts entirely (`HvacCommandApplier.java:66-71`), and it
refuses an unknown zone or air handler and an out-of-range value (`:57-65`, `:77-85`).

That matters more than it looks. The monitor's flow model believes the **commanded** position, not
a measured one (`AhuFlowModel.java:199`). A replacement that honoured a command this one would have
clamped puts the plant somewhere the monitor's belief is not — and the residual then measures the
disagreement between two pieces of this system rather than a fault in the building, which is the
one failure mode the whole design exists to avoid.

---

## K6 — the service-report seam · Tier 1, clean · `seams-monitor.d2`

**Contract out** (`ServiceResource.java:94`): `service/{zone}` carrying
`ServiceReported(ZoneId zone, CaseId caseId, ObservedAt at, String reason)`. Published by the
dashboard, from a POST on the operator's page. Consumed by the fault-monitor alone
(`MonitorRuntime.java:113`). Topic builder `Topics.service:47`.

It is the only thing that clears a latched diagnosis, and until it existed the exit was unreachable
from a running deployment at all (D-10): `CONFIRMED` could only be left by restarting the monitor.

It is deliberately **not** a `commands/...` topic. Everything under `commands/` is actuator
authority — the inner loop owns the dampers, the supervisor owns the fan — and a service report
moves no actuator: it states a fact about the building that no component can observe
(`ServiceReported.java:12-17`). On its own topic family the authority argument is untouched, because
nothing new publishes to an actuator. That is what makes the seam open: a CMMS, a phone, a `curl`,
any publisher at all.

The `caseId` is required rather than optional, so a report that arrives late — after the case it
names has already closed — cannot clear whatever case the room has next (`:18-20`). A replacement
publisher must therefore know which case it is answering, which is the one thing it cannot guess.

---

# Port seams

## P1 — `BuildingStatePort` · Tier 2 · `seams-plant.d2`

`BuildingStatePort.java:14`, eight methods: `readCo2`, `readDamperPosition`, `readFanSpeed`,
`readFanPower`, `writeCo2`, `writeFanPower`, `applyDamperPosition`, `applyFanSpeed`. Rationale at
`:9-13` — BuildSim as the single source of building state, nothing bypassing it to talk to a peer,
because otherwise a command could not affect the next measurement.

One production implementation, `BuildSimAdapter.java:32`, constructed in five composition roots:
physics `Main.java:66`, `Co2SensorMain.java:50`, `DamperActuatorMain.java:46`,
`DashboardRuntime.java:170`, `SupervisorConfiguration.java:65`. No `control` or `entity` class names
it anywhere.

The dependency points inward exactly as ADR-0005 claims. Three things stop this from being a
one-edit substitution:

1. **One interface covers the whole building surface.** There is no per-signal port, so an adapter
   that read *one* real CO2 sensor must still implement and sensibly delegate the other seven
   methods. That is the structural reason the real-sensor question is answered at **K1** and not
   here.
2. **Five composition roots, no factory.** Each process news the adapter itself. The swap is five
   edits — all in `boundary` or `Main`, none in `control` or `entity`, which is the property the
   port was for in the first place.
3. **BuildSim cannot be configured off for the three device processes.** They hard-default
   `BUILDSIM_URL` and block in a `/healthz` wait loop until it answers (`Co2SensorMain.java:39` and
   `:80-86`; likewise physics `Main.java:58`, `:99-105` and `DamperActuatorMain.java:36`, `:87-93`),
   so there is no "without BuildSim" mode here the way unsetting `JDBC_URL` gives one for **P5**.
   The supervisor and the dashboard do have one.

**This is the line that survives everything else.** If the room becomes real, BuildSim and the
simulator both go (**N2**) and this port is where the real building is attached.

## P2 — `BuildingOverlayPort` · Tier 1, clean · `seams-plant.d2`

`BuildingOverlayPort.java:12` — `publishLayer`, `raiseWorkOrder`, `clearWorkOrder`. Separate from
`BuildingStatePort` because this is what a human reads, not what a device measures.

Clean, and demonstrably so rather than aspirationally: **three implementations already live in the
tree** and the live one is chosen by whether `BUILDSIM_URL` is set, with no recompile —
`BuildSimAdapter.java:122-170`, `SkippedWorkOrders.java:13` (the supervisor, headless) and
`RecordingOverlay.java:11` (the harness). Selection at `SupervisorConfiguration.java:63-66`. The
operator's view is an output and never an input to any decision, which is why its absence degrades a
run instead of stopping one (`SkippedWorkOrders.java:8-12`).

**A note, not a condition on the swap.** The two halves have disjoint owners — the supervisor calls
only the work orders (`SupervisionService.java:183`, `:299`), the dashboard only the layers
(`RoomLayerPublisher.java:47`) — because both BuildSim collections are whole-collection PUTs
(`BuildSimAdapter.java:138-150`, `:250-264`). That constrains who may *hold* an implementation, not
whether one can be exchanged for another, which is why P2 stays Tier 1 while the same fact becomes
a real obligation at **K2**.

## P3 — `OccupancyPort` · Tier 1, clean · `seams-plant.d2`

`OccupancyPort.java:7` — one method, `publishOccupancy(Map<ZoneId, Integer>)`. One implementation
(`BuildSimAdapter.java:105`), one caller (`BuildSimStateAdapter.java:46`).

The least interesting seam in the set: head count is an output to BuildSim's 3D view that nothing in
the system reads back, so substituting it changes what an operator sees and nothing else. It is
recorded because leaving it out would make the port inventory look complete when it was only
flattering.

## P4 — `EventPublisherPort` + `ReadingStreamPort` · Tier 2 · `seams-transport.d2`

`EventPublisherPort.java:10` — `publish(String topic, DomainEvent)`, deliberately one port rather
than one per event kind (`:5-9`). `ReadingStreamPort.java:10` —
`subscribe(String topicFilter, Consumer<DomainEvent>)` and `close()`.

`MqttAdapter.java:27` implements both over Paho, chosen because there is no Jakarta EE or
MicroProfile MQTT API, so one Eclipse client serves both the plain-Java device processes and the
Quarkus services. Second and third implementations exist and are used in anger:
`RecordingPublisher.java:24` and `CountingPublisher.java:13` run the same control classes with no
broker at all.

**The six clients:** `co2-sensor` publishes only; `damper-actuator` subscribes only;
`dcv-controller`, `fault-monitor`, `supervisor` and `dashboard` do both.

1. **Six composition roots, no factory.** `new MqttAdapter` appears at `Co2SensorMain.java:57`,
   `DamperActuatorMain.java:53`, `DamperControlJob.java:90`, `MonitorRuntime.java:104`,
   `SupervisorConfiguration.java:54` and `DashboardRuntime.java:151` — and nowhere in `control` or
   `entity`. Kafka instead of MQTT is six edits plus a compose service, not one edit. There is no
   shared factory and no CDI alternative to flip.
2. **The wire format travels with it.** `MqttAdapter.publish` calls `EventCodec.encode` and
   `dispatch` calls `EventCodec.decode`, so a transport swap that changed the field names changes
   what every subscriber decodes, in every language. See **N3**.
3. **A deliberate asymmetry rides on this port and a replacement must preserve it.** A dead
   publisher goes *silent* rather than blocking a caller, which is what lets the monitor reach BLIND
   instead of hanging (`ReadingStreamPort.java:6-9`). Losing the connection raises nothing at all,
   because staleness is the monitor's business and not the transport's (`MqttAdapter.java:50-53`).
   One undecodable payload is dropped and logged rather than killing the subscription, and one
   handler's failure does not starve the handlers after it (`:98-121`). A transport that threw on a
   disconnect, or blocked a publisher, or let a bad payload kill a subscription, would break R-4
   **without breaking a test** — because the behaviour under test is the absence of messages.

## P5 — `ResidualJournalPort` · Tier 2 · `seams-storage.d2`

`ResidualJournalPort.java:14` — `append(DomainEvent event, String source)`, `replayCase(CaseId)`,
`replayZone(ZoneId, from, to)`. Four implementations: `TimescaleJournalAdapter.java:46`,
`SkippedJournal.java:16`, `DiscardingJournal` (fault-monitor) and `CountingJournal.java:16`. It is
the only code in the system that touches `java.sql`.

**The four clients.** `dcv-controller` appends its damper commands; `fault-monitor` appends
readings, fan power, every command it observes, residuals and decisions; `supervisor` appends
strategies, fan speed and the probe command; `dashboard` is the only reader, replaying one case
across five of the six tables.

1. **Turning it off is a configuration, and it is free.** Unset `JDBC_URL` and each of the four
   clients substitutes a no-op (`SupervisorConfiguration.java:70-74` is the clearest). It costs R-5
   reconstruction and nothing else: R-2's compensation and R-7's degradation are still produced and
   still measured (`SkippedJournal.java:10-14`). The evaluation harness runs this way by default.
   This is the cleanest substitution anywhere in the set.
2. **A different store brings its own schema.** The six hypertables are the *adapter's*, not the
   core's. The port speaks `DomainEvent`; the rows are the adapter's private business, and
   `deploy/timescaledb/init.sql:11-85` calls `create_hypertable`, a Timescale extension and not
   standard SQL. So the compose service changes with the adapter, and so does the init script. The
   queries are native SQL on purpose (ADR-0002) because the residual window is a time-ordered scan
   with a limit — a cost paid here and nowhere else in the system.
3. **Four composition roots:** `DamperControlJob.java:88`, `MonitorRuntime.java:159`,
   `SupervisorConfiguration.java:72`, `DashboardRuntime.java:179`.

**Why it is not a repository.** R-5 requires every diagnosis to be *reconstructable*, so the journal
keeps the event stream rather than a bespoke persistence shape, and reconstruction is reading back
the events that produced the decision (`ResidualJournalPort.java:9-13`). That is why there is no
`save(FaultCase)` and no `findById`: a replacement store inherits an append-and-replay contract, not
an object graph. It also means the `source` tag on `append` is part of the contract — `replayCase`
stitches five tables back into one ordered timeline (`TimescaleJournalAdapter.java:180-229`), and the
`strategies` rows in it come from the supervisor alone (**K2**, obligation 2).

## P6 — `ClockPort` · Tier 1, clean · `seams-clock.d2`

`ClockPort.java:11` — `now()` and `waitFor(Duration)`. Three implementations, all load-bearing:

| implementation | role |
|---|---|
| `SystemClock.java:8` | the wall clock, what the seven containers run on when `TIME_SCALE` is unset or 1 |
| `ScaledClock.java:55` | simulated time running `TIME_SCALE` times faster than real, anchored on one `REAL_EPOCH`: `now() = START_AT + (real − REAL_EPOCH) × TIME_SCALE` |
| `SimulatedClock.java:12` | advances instantly; `waitFor` returns at once and moves the clock forward by the span it was given |

**This is the seam a reader would not guess**, and it is worth pointing at. Every other seam exists
because the architecture says a boundary crossing gets a port. This one exists because the
*evaluation* could not otherwise be done at all, and nothing in `container.d2` or `deployment.d2`
shows it, because it never crosses a process boundary.

Nothing in `control` may call `Instant.now()` or `Thread.sleep` (`ClockPort.java:6-10`), so the
evaluation harness drives thousands of simulated hours through the **very same** control classes the
containers run — not a copy, not a test double of the logic, not a reimplementation in a notebook.
The only thing that differs between a measured experiment and a deployed container is which
`ClockPort` was handed to the composition root (`SystemHarness.java:82`,
`ScalingExperiment.java:143`). `SimulatedClock` is what turns R-3's seven-day fault-free replay and
R-1's twenty injection runs from impossible into milliseconds (`SimulatedClock.java:7-11`).

That is the strongest argument available that the measured numbers describe the shipped system. It
rests entirely on this one interface, and it would be destroyed by a single `Instant.now()` in a
control class. Tier 1: the swap is one constructor argument in one composition root, and nothing
else in the system changes.

`ScaledClock` is selected by `TIME_SCALE`, `START_AT` and `REAL_EPOCH` in the environment with no
recompile, and at `TIME_SCALE=1` `fromEnvironment` returns the `SystemClock` unchanged rather than
an equivalent wrapper around it (`:121-137`). All seven processes call it:
`Co2SensorMain.java:49`, `DamperActuatorMain.java:45`, `DamperControlJob.java:83`,
`MonitorRuntime.java:106`, `SupervisorConfiguration.java:42`, `DashboardRuntime.java:90`, physics
`Main.java:79-80`.

**A note on that profile, not a condition on the swap.** The three variables must be identical in
all seven processes, or each opens a timeline of its own and the disagreement surfaces as residuals
and staleness indistinguishable from a real fault (`ScaledClock.java:20-37`). Nothing in `now()`
depends on when the object was constructed, which is the whole point: seven processes started at
seven different real instants compute the same simulated time, and a container restarted half-way
through rejoins the same timeline instead of opening a second one. A scale above 1 with no
`REAL_EPOCH` is **refused at boot** rather than defaulted, because there is no default that could be
right: an epoch per container is precisely the bug the class exists to remove, and it would fail
silently as a wrong diagnosis rather than loudly as a container that will not start (`:126-133`).

This is a property of the deployment profile, not of substituting the port, which is why P6 stays
Tier 1. It does, however, become a real condition at **K1**.

---

# Tier 3 — the boundaries that are not seams

These are the most useful part of the set. Each one looks like a boundary on `container.d2` and is
not one.

## N1 — `Topology.java` · `seams-topology.d2`

**This is the one an examiner will ask about.**

`shared/.../ventilation/entity/Topology.java:18` hard-codes the plant:

| constant | value | line |
|---|---|---|
| the six rooms, with their areas and design flows | — | `:171-179` |
| `DESIGN_FLOW_TOTAL_LITRES_PER_SECOND` | 411.4 | `:25` |
| `NOMINAL_FLOW` | 0.59 m³/s | `:39` |
| `NOMINAL_POWER_WATTS` | 400 | `:42` |
| `NORMAL_FAN_SPEED` | 0.7 | `:54` |
| `MINIMUM_POSITION` | 0.15 | `:57` |
| `OUTDOOR` / `COMFORT_LIMIT` | 420 / 1000 ppm | `:59`, `:60` |
| `GENERATION_PER_PERSON` | 5e-6 m³/s | `:63` |
| `SENSOR_NOISE_SIGMA_PPM` | 30 | `:71` |
| `CUSUM_SLACK_PPM` / `CUSUM_LIMIT_PPM` | 30 / 150 | `:74`, `:77` |
| `FAN_POWER_NOISE_SIGMA_WATTS` | 1.5 | `:93` |

It is an interface of static members in `shared/`, which every one of the seven processes links.
**There is no port in front of it and no injection point.** ADR-0005 counts it among the seven
interfaces that declare static members only — this project's substitute for a utility class, not a
strategy.

**Who reads the plant numbers, and they are `control` classes rather than adapters:**

- `fault-monitor` — `ZoneObserver` takes `OUTDOOR`, `GENERATION_PER_PERSON` and
  `SENSOR_NOISE_SIGMA_PPM` (`:82`, `:104`, `:349`); `AhuFlowModel` takes each room's
  `damperAuthority`, which *is* its design flow, for the shared-AHU split (`:79`, `:199`);
  `FaultClassifier` and `FaultSignals` take `OUTDOOR`.
- `supervisor` — `PlantState` sizes every plan from `zones()` and `airHandler()`
  (`PlantState.java:45`); `CompensationSolver` solves against `NORMAL_FAN_SPEED` (`:54`);
  `SupervisionService` takes `COMFORT_LIMIT` and `OUTDOOR` to compute the gain it asks for
  (`:221-222`).
- `dcv-controller` — `ControlSettings` takes `COMFORT_LIMIT` and `MINIMUM_POSITION` (`:104`).
- `damper-actuator` — clamps every command up to `MINIMUM_POSITION` (`:67`).
- `physics-simulator` — `Co2MassBalance` takes `GENERATION_PER_PERSON`; `SensorModel` takes both
  noise sigmas.
- `dashboard` — `RoomLayer` scales its colour ramps from `OUTDOOR` and `CUSUM_LIMIT_PPM`.
- `evaluation` — the heaviest reader of all: twelve classes, including every threshold the
  experiments report against.

Thirty-four main-source files import it.

**So what does a real building do to this file?** A real corridor has *measured* design flows, a
*measured* air handler and a *measured* sensor noise floor. Every number above is edited — in one
file, in the library all seven services link, which recompiles all seven and redeploys all seven.

And they do not move independently. The CUSUM slack and limit are tuned to
`SENSOR_NOISE_SIGMA_PPM`, and the file says so at `:65-77`: moving one without the others
invalidates the T-04 spike that measured them together (16.7 σ separation, 0 of 20 false alarms). A
real sensor with a different noise floor therefore does not just change a constant — **it
invalidates the detection result the whole project rests on** until the spike is redone.

`NOMINAL_FLOW` is sized at the design total divided by `NORMAL_FAN_SPEED` so that compensation has
headroom at all (`:27-38`). A real air handler sized differently removes the lever R-2 is measured
on, and the file already records an earlier revision where exactly that happened: no room could be
held below 1000 ppm at design occupancy with nothing broken.

**Therefore K1 is a clean wire and not a clean substitution.** A real sensor changes nothing about
the payload: same topic, same four fields, same codec. What a real room changes is what the
monitor's residual *means*, because the residual is measured minus a prediction computed against
this file's model of the plant. The wire survives the swap; the model does not. That is the sentence
to say at an oral exam before being asked for it.

The honest claim this set supports: **the system is substitutable at its transport boundaries and at
its infrastructure boundaries, and it is not portable to a different building without editing its
plant model and re-measuring the constants tuned to it.**

**The one part that is configurable, and what it is not.** `ZONE_COUNT` (`:118`, `:203-226`) varies
how many zones a process runs, resolved once at class initialisation so the zone set cannot change
under a running container. It is read in the entity layer with `System.getenv` rather than injected —
the one reach-around in the core — and the file owns the reason at `:102-118`: all seven processes
have to agree on the zone set, and threading it through seven composition roots would turn "the
services agree" from a guarantee into a convention. It varies how many of *these* rooms run, not
what any one of them is: beyond the sixth a zone borrows a real further level-0 room's name and a
cycled copy of one of the six geometries, because BuildSim refuses to host equipment in a room its
floorplan does not have, and past 58 the ids are generated and such a set is for the harness alone.
So `ZONE_COUNT` is a scaling knob, not a seam — it cannot express a different building.

## N2 — the simulator against the plant · `seams-plant.d2`

There is no interface between "the simulator" and "the plant" that a real building could be plugged
into. BuildSim **is** the plant of record (`BuildingStatePort.java:9-13`) and the simulator is what
makes its CO2 move.

`BuildSimStateAdapter.exchange:34-48` reads back the six damper positions and the fan speed the
actuator applied (`:36`, `:38`), advances the physics, then writes the **measured** CO2 (`:43`), the
fan power (`:45`) and the occupancy (`:46`). It deliberately does *not* write the damper positions
back, which is what leaves a stuck damper showing the position that was commanded while the air
behaves as if it were frozen (`:16-18`) — the disagreement the monitor exists to notice.

Stop the simulator and BuildSim's CO2 stops changing: the plant is **gone**, not substituted.

**So be precise about what cutting K1 leaves behind: the simulator still has a job.** Only one of
its four legs goes unread — `writeCo2`. It still supplies fan power, the monitor's only cross-room
signal; still closes the actuator loop by reading back what `damper-actuator` applied, so that a
command can affect the next measurement at all; and still drives occupancy for the 3D view. A real
sensor publishing straight to MQTT simply bypasses BuildSim's CO2 value, leaving the simulator
writing something nobody reads.

**A real room is a different cut.** It retires BuildSim and the simulator together, and then **P1**
is the only line left standing: `BuildingStatePort` is where a real building attaches, and the
damper-actuator's writes become writes to real actuators.

## N3 — `EventCodec` · `seams-transport.d2`

`shared/.../shared/boundary/EventCodec.java:41` is an interface and is **not** a port: it declares
static methods only, has no abstract method, and therefore has nothing behind it to substitute.
ADR-0005 counts it among the seven such interfaces and names it as the reason the tempting claim
"the ports are the only interfaces in this system" is false — the precise claim is that nothing here
has an abstract method without an alternative implementation behind it.

`encode` is a `switch` over the sealed `DomainEvent` permits list (`DomainEvent.java:9-11`), so
adding an event kind or renaming a wire field edits **the one file that both sides of every contract
seam link**. The wire format is a shared compile-time artefact, not a negotiated boundary.

That is the price ADR-0006 knowingly accepts for the domain event *being* the payload, with no
parallel DTO hierarchy and no mapper — paid in exchange for having nothing to keep in sync between
a record and its DTO.

It is worth saying out loud, because a contract seam described as language-agnostic can read as
though the schema were negotiated per publisher. It is not. A non-Java publisher at **K1** must
match the JSON this one file writes — `type`, `ts`, `zone`, `ppm`, `quality` — and nothing tells it
so but that file. There is no schema registry, no IDL and no version field.

## N4 — the data-quality judgement · `seams-sensor.d2`

`Quality` (`ventilation/entity/Quality.java:7`) is an enum on the wire, and judging it looks exactly
like a policy that ought to be substitutable: it has a tunable window and a documented rule. It is
not behind anything.

The rule lives inline in one private class in one process:
`Co2Sampler.SignalHistory.judge:159-169`, with `STALE_INTERVALS = 3` as a private constant (`:49`)
and exact-equality duplicate detection chosen deliberately, because BuildSim round-trips sensor
values as fixed-decimal strings and a tolerance here would classify slow genuine drift as a
duplicate (`:148-152`). `MISSING` is not from `judge()` at all: it is the exception path, publishing
the *last known* value rather than a fabricated one (`:97-104`, `:116-123`), and nothing at all
before the first successful read.

There is no port, no strategy interface and no configuration knob. That is why **K1** condition 1 is
a condition and not a footnote: the judgement cannot be reused by a replacement publisher, only
reimplemented.

It is also why `Topology.FAN_POWER_NOISE_SIGMA_WATTS` exists at all (`Topology.java:79-92`). A
noiseless fan-power reading was marked `DUPLICATE` on its second sample and `STALE` on its fifth,
which silently made the monitor's only cross-room signal unusable, since the observer may consume
only `GOOD`. A judgement with no seam in front of it reached back into the plant model to protect
itself.

---

# Seams deliberately not drawn

- **The dashboard as a substitutable consumer.** It subscribes six filters
  (`DashboardRuntime.java:49-55`) and publishes only `service/{zone}`. A pure sink is substitutable
  by definition and the seam buys nothing to look at.
- **`FlowSplit` and `Co2MassBalance` as strategy seams.** Both are interfaces with static methods
  only, like `Topology` and `EventCodec`. They would be three more Tier 3 boxes making the point
  **N3** already makes, so N3 carries the general rule and these two are not drawn.
- **`OccupancySource`** (`physics/control`). It is the only interface *inside* the core with a
  genuine abstract method and two real strategies — the simulator's drawn plausible day and the
  harness's scripted occupant step (ADR-0005 names it). It is a real seam, and it is omitted because
  it is internal to one component: nothing crosses a process or a layer boundary at it, so it
  belongs at `component.d2`'s level rather than on a substitution map. Recorded so the omission is a
  decision and not a gap.
- **A separate marker per topic.** Eight topics exist; six contract seams are drawn. The unit of
  substitution is a *component*, not a topic, so each marker carries the boundary rather than the
  message, which is what lets each of the two questions be answered by pointing at a single line.
- **`ServiceRequests`**, the monitor's service-report handler. It is the consumer side of **K6** and
  adds nothing K6 does not already name.

---

# Rendering the figures

The same pipeline as the other nine figures in `project/diagrams/` — d2 v0.9.0 with the ELK layout
engine, then librsvg for the PDF (`project/report/Makefile:25` and `:47-53`):

```sh
d2 --layout=elk project/diagrams/seams-<name>.d2 project/diagrams/seams-<name>.svg
make -C project/report figures/seams-<name>.pdf
```

No `--theme`, no `--pad`, no `--scale`: this project does not use them, although the course
templates do. The `.d2` sources and the rendered `.svg` files are committed; the PDFs under
`project/report/figures/` are build output and are gitignored, as all nine existing figure PDFs are.

`seams` is deliberately **not** in `FIGS_D2` (`report/Makefile:19-20`), because that list is a
prerequisite of `report.pdf` (`:42`) and adding it would force a rebuild of the verified report. The
pattern rule `figures/%.pdf: ../diagrams/%.svg` works for any stem.

No `.tex` file references any of these figures. Inserting one into the report is a separate
decision.

# The figure budget these were drawn to

Node label at most 60 characters over at most 3 lines; edge label at most 30 characters; a seam
marker is its key plus a short noun, with the tier carried by colour alone and defined once in
`seams-legend.d2`; `seams-overview.d2` at most 14 nodes, every other figure at most 8 (12 for
`seams-loops.d2`); every figure at or under 1400 × 1000 pt. Prose belongs in this file.
