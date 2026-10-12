# Ventilation Fault Detection, Diagnosis and Graceful Degradation

D7065E — Embedded Intelligence at the Edge (LTU, autumn 2026), project group 5, Wassim Akachi (solo).

**Status.** Proposal accepted 2026-09-18 with one examiner condition — *"make sure to include
several rooms"* — so the system is built over **six rooms on level 0** of the A-building.
Which six, and what couples them, was settled on 2026-10-05 (ADR-0008): the cluster on
corridor A120, behind **one shared air handler with a damper each**.
Design approval week 38 · final report and repository due **12 Oct 2026** · demo and
individual oral 14/16/19 Oct.

| | |
|---|---|
| **Use case** | Demand-controlled ventilation that detects, diagnoses and survives its own equipment faults |
| **Pitch** | A stuck damper and a room full of people look identical to a CO₂ controller. A model of what the last command should have achieved tells them apart, names the fault, and keeps the air breathable until someone repairs it. |
| **Proposal** | [`project/proposal/ProjectGroup5_ProjectProposal.pdf`](project/proposal/ProjectGroup5_ProjectProposal.pdf) |
| **Build** | [`project/README.md`](project/README.md) — what it is, how to run it, how to test it |
| **Report** | [`project/report/report.pdf`](project/report/report.pdf) — the final architecture document: requirements, evaluation, risks |
| **Decisions** | [`project/decisions/`](project/decisions/) — every choice with its rejected alternative and the trade-off accepted |

## The idea

A demand-controlled ventilation loop is simple: CO₂ rises with occupancy, the controller opens a
damper, the air is diluted, the next reading falls. The project proper is the **supervisory loop
on top of it**, which decides whether that control loop is still *working*. The fault is invisible
to the controller because it only sees CO₂ — a high reading looks the same whether the damper is
stuck shut or the room is simply full. Telling those apart needs a model of what the last command
should have achieved, so the autonomous part is an inference problem rather than a threshold, and
fault injection and degraded operation are the use case itself rather than an experiment added
at the end.

| Loop | Period | Closes because |
|---|---|---|
| Inner — control | ~1 s | Damper command changes airflow, which changes CO₂, which the controller reads next |
| Outer — supervision | ~60 s | A confirmed fault changes the control strategy, which changes the commands, which changes the residual the monitor reads next |

## The scenario

A room fills up and CO₂ climbs past 1000 ppm. The controller opens the damper wide, but it is
stuck, so airflow never changes and CO₂ keeps rising. The monitor saw the same command, expected
the level to fall, and instead watches the gap grow until it is too large and too persistent to be
noise. Unchanged fan power argues against a blocked filter, a correct overnight decay argues
against a drifting sensor, and the monitor publishes a confirmed *stuck damper*. The supervisor
degrades that room, compensates to keep the occupants below the limit, and raises a work order on
the 3D view. The diagnosis stays latched even though the CO₂ curve now looks healthy again — the
single hardest property in the design, because compensation masks the very signal that found the
fault.

## What is built

Every box is its own container, one folder under `project/`, one service in
`docker-compose.yml`, one box on the container diagram. BuildSim stays the single source of
building state; MQTT carries readings, commands and fault events; TimescaleDB holds the history.

| Container | Role |
|---|---|
| `physics-simulator` | CO₂ mass balance per room, occupancy schedules, the shared air handler and its flow split, fault injection; writes sensor values to BuildSim |
| `co2-sensor` | Reads the six room CO₂ values and the handler's fan power from BuildSim, publishes to the broker |
| `damper-actuator` | Validates and applies HVAC actuator commands to BuildSim — the six dampers and the one fan speed |
| `dcv-controller` | Inner loop: damper position from CO₂ with hysteresis and a freshness check — deliberately a plain rule |
| `fault-monitor` | Outer loop, half one: predicts the response each command should produce, accumulates the residual (one-sided CUSUM), classifies the fault. **Raises events only, never commands** |
| `supervisor` | Outer loop, half two: acts on a *confirmed* fault — degrades the room, compensates, issues the work order |
| `dashboard` | Residual and fault state per room painted onto BuildSim's 3D view, with its own HTML page and JSON endpoint beside it |
| `mosquitto`, `timescaledb` | Broker and hot store (third-party images, config under `project/deploy/`) |

A tenth compose service, `buildsim`, builds the building-state server out of `buildingsim/`;
`shared/` is a library and `evaluation/` an offline harness, so those two folders have no
container, which is why there are more folders than services while the mapping stays 1:1 for
the seven above.

Monitor and supervisor are split on purpose: a crashing monitor must not be able to stop
ventilation, and a wrong diagnosis must not be able to command anything by itself.

Stack: Java 25, Quarkus in JVM mode for the four services that want scheduling, configuration
and health, plain Java for the two device processes and the simulator, BCE packages by business
component (`ventilation/`, `diagnosis/`, `supervision/`, `physics/`, `devices/`, `dashboard/`),
native SQL over six TimescaleDB hypertables for the residual and replay queries. See ADR-0002
and ADR-0005.

Where the build stands: all seven containers exist, each with its folder, its Dockerfile and its
compose service, and the suite is green --- **417 tests, 0 failures, 0 errors, 0 skipped** across
the ten reactor projects. `evaluation/` is written, so the R-1…R-7 evidence runs are automated:
`make experiments` runs all seven in about 157 s and writes a CSV per experiment under
`project/evaluation/results/`. What is *not* settled is the evidence itself --- R-1 in closed
loop, R-2 at design occupancy and R-3's worst week do not meet their criteria, and
`project/docs/arc42.md` section 11 is where that is recorded.

## Simulating the building

BuildSim provides the rooms but no physics, so both the air and the people are simulated.

- **Air:** a well-mixed CO₂ balance per room — production rises with head-count, dilution is the
  share of the shared handler's flow the commanded damper opening wins, volume is the room's
  floor area times an assumed 3 m ceiling. The handler's fan power is derived from flow, so a
  blocked filter costs more power for less air.
- **Faults**, injected *between* the command and its physical effect: a stuck damper ignores the
  command, a blocked filter scales the flow down, a drifting sensor adds a slow offset at the
  sensor rather than in the room. The fault model is invented, not measured, and the report says so.
- **People:** a weekday schedule per room type — arrivals, a lunch dip, departures, booked
  meetings, empty nights and weekends — with randomness so no two days are alike. Empty nights
  double as the reference condition for the sensor: with nobody in, CO₂ *must* decay to outdoor.
- **Six rooms:** the corridor-A120 cluster, 18 to 76.5 m² — a 4:1 span in volume, so no two
  rooms share a time constant or a schedule: a single office (54 m³), a two-person office
  (89 m³), a project room (99 m³), a meeting room (132 m³), an open-plan office (230 m³) and a
  seminar room (222 m³). **One shared air handler, one damper per room** (ADR-0008, option B);
  no inter-room air exchange. The handler is sized 2 % above the sum of the six design flows,
  so the only way one room gets more air is if another damper closes. That is what gives the
  monitor its one cross-room signal — plant fan power plus the common mode across the six
  residuals, which tells a blocked filter from a stuck damper — and it is what **"compensate"**
  means: raise the shared fan and trim the five healthy dampers so flow is pushed to the faulted
  room while the healthy five keep the airflow they had. It reaches a damper stuck *partly open*,
  the realistic case, since a demand-controlled damper holds a floor of 0.15 during occupied
  hours. A damper stuck fully shut has no air path and gets a work order instead.

## Requirements

| ID | Statement | Evidence |
|---|---|---|
| R-1 | When a damper stops responding to commands, the system shall confirm the fault | p95 confirmation ≤ 15 min, ≥ 20 injection runs |
| R-2 | While a fault is latched, the affected room shall stay below 1000 ppm by compensation | ≥ 95 % of occupied minutes, end-to-end scenario |
| R-3 | The monitor shall raise at most one false alarm per simulated week | 7-day fault-free replay, fixed seed |
| R-4 | While readings are stale, the monitor shall report unavailable and issue no diagnosis | Kill `co2-sensor`; state `BLIND`, zero fault events |
| R-5 | Every diagnosis shall be reconstructable from the store | Rebuild a random case from the journal |
| R-6 | The **zone** count at which the monitor misses its interval shall be identified | Scaling run to the breaking point |
| R-7 | While a fault is latched in one room, control and diagnosis of every other room shall stay unchanged | One room faulted, five observed on CO₂ *and* airflow, rotating the injected room |

R-2 is a claim about the reachable case: compensation works through the shared handler, so it
pins the stuck damper at its 0.15 floor, and a damper stuck fully shut is out of reach by
construction. R-6 counts **zones**, not rooms: the building has exactly six rooms, so a
requirement about a room count would be unsatisfiable. A zone is what the monitor runs one
state machine per, and the scaling experiment sweeps the count with
`Topology.syntheticZones(n)` — a statement about how many zones the monitor can process, not a
claim about a building that has them.

The full statements, acceptance criteria and the gaps still without a requirement ID are in
[`project/REQUIREMENTS.md`](project/REQUIREMENTS.md).

Planned experiments: detection delay against the CUSUM limit; model-based monitor against a plain
threshold on identical seeds; comfort during a fault with and without compensation; kill every
container in turn; zones per monitor until the 60 s cycle is missed. A learned one-class model is
an optional contender measured against the CUSUM baseline and kept only if it wins.

## Biggest risks

- The prediction wants occupancy, the one thing a real building does not have; the intended
  answer is to judge commanded *step responses*, whose shape depends mainly on airflow and volume.
- The three fault signatures may collapse into two under realistic sensor noise.
- **Masking:** once the supervisor compensates, the residual shrinks and a naive monitor clears a
  fault that is still there. Latching plus occasional active probing is the intended answer.
- The fault model and the detector are both mine, so the evaluation measures the detector against
  its own generator.

## Where things are

```
project.md                          this summary
project/README.md                   what the system is, how to run it, how to test it
project/pom.xml                     the nine-module Maven reactor
project/Makefile                    build, images, up, test, experiments
project/docker-compose.yml          the ten services and their dependency order
project/shared/                     domain language, ports and adapters — a library, not a container
project/physics-simulator/          the air, the people, the plant and the injected faults
project/co2-sensor/                 publishes the six CO₂ values and the handler's fan power
project/damper-actuator/            applies damper and fan-speed commands to BuildSim
project/dcv-controller/             the inner control loop
project/fault-monitor/              residual, CUSUM, classification — events only
project/supervisor/                 degrade, compensate, work order
project/dashboard/                  the operator view
project/evaluation/                 the offline evidence harness — declared, not yet written
project/deploy/                     mosquitto and TimescaleDB configuration
project/diagrams/                   C4 context, container and component, plus the scenario and
                                    state figures, as D2 sources and rendered SVGs
project/decisions/                  the decision log (ADRs)
project/REQUIREMENTS.md             R-1..R-7 in EARS form, with their verification
project/report/                     the final architecture document, LaTeX sources and the built PDF
project/proposal/                   the accepted week-2 proposal (LaTeX + PDF)
project/ideas/                      the idea catalogue the use case was chosen from
project/spikes/                     the throwaway experiments that fixed the detection parameters
buildingsim/                        BuildSim, the building state server everything runs against
lab-assignment/                     proposal and report guides, templates and worked examples
```

The project lives inside the course-material repository so that one clone pins it to the exact
BuildSim it was built and evaluated against (ADR-0007).
