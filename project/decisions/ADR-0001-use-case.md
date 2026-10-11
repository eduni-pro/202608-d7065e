# ADR-0001 — Use case: ventilation fault detection, diagnosis and graceful degradation (A-06)

**Status:** Accepted · 2026-09-10 — the date of the proposal. The examiner accepted the
proposal on 2026-09-18 with one condition, *"make sure to include several rooms"*, resolved
as six rooms on level 0 (plan §2, constraint 8). This record replaces the access-control
decision recorded under the same number on 2026-08-31; that decision's reasoning is kept
below under *Alternatives rejected*.

## Context

BuildSim, the building server the course supplies, is a state store only: it holds rooms,
equipment, sensors, actuators, doors and mobile entities and shows them in a 3D viewer, but
it simulates no physics and keeps no history. Physics, sensing, decisions and history are
all processes this project writes. The course requires a physical simulator with occupancy
patterns, an autonomous decision-making component, a data pipeline, a dashboard and AI
integration. The project is solo.

The idea catalogue in [`../ideas/`](../ideas/) scored twenty-two candidate closed loops on
loop strength, visual demo, architecture depth, AI fit, oral defensibility and effort. The
first pick was A-01, zone access control with a coupled air-quality loop, and it produced
ADR-0001, ADR-0003 and ADR-0004 before the proposal deadline. The use case changed on
2026-09-10, with the proposal: what was submitted and accepted is A-06, ventilation fault
detection, diagnosis and graceful degradation.

## Decision

Demand-controlled ventilation over six rooms on level 0 of the A-building, whose
supervisory loop detects, diagnoses and survives the system's own equipment faults — a
stuck damper, a blocked filter, a drifting sensor.

The system has two nested loops, and the second one is the project:

- **Inner loop — control, ~1 s.** `dcv-controller` sets each room's damper position from
  CO₂ with hysteresis and a freshness check, deliberately a plain rule. The damper command
  changes airflow, which changes CO₂, which the controller reads next.
- **Outer loop — supervision, ~60 s.** `fault-monitor` predicts the CO₂ response each
  command should produce, accumulates the residual between prediction and measurement
  (one-sided CUSUM), and classifies the fault from residual, fan power and overnight decay,
  with an explicit `UNCLASSIFIED` branch. It **raises events only** and never commands
  anything. `supervisor` acts on a *confirmed* fault: it degrades the room, compensates to
  keep the occupants below the limit, and raises a work order on the 3D view. A confirmed
  fault changes the control strategy, which changes the commands, which changes the
  residual the monitor reads next.

The monitor's states are `WARMUP → HEALTHY → SUSPECT → CONFIRMED → COMPENSATING →
SERVICED → WARMUP`, plus `BLIND` on stale data — and `BLIND` is not a fault. A `FaultCase`
is latched: it leaves `CONFIRMED`/`COMPENSATING` only through an explicit clear, so the
compensation that answers a fault cannot also erase the evidence for it.

The containers are those of plan §3: `physics-simulator`, `co2-sensor`, `damper-actuator`,
`dcv-controller`, `fault-monitor`, `supervisor`, `dashboard`, plus `mosquitto` and
`timescaledb`. Faults are injected in the simulator between the command and its physical
effect, in one room while the other five stay live.

## Because

- The fault is invisible to a controller that only sees CO₂: a high reading looks the same
  whether the damper is stuck shut or the room is simply full. Telling them apart needs a
  model of what the last command should have achieved, so the autonomous part is an
  inference over the expected effect of a command, not a threshold on a reading (R-1).
- The residual is accumulated by a one-sided CUSUM with two tunable numbers, the slack `k`
  and the limit `h`, so detection delay against false alarms is a measurable trade-off
  rather than an assertion, and the simulator supplies its own ground truth for every
  injected fault (R-1, R-3).
- Graceful degradation is the use case, not an extension: the supervisor's job is to keep
  the affected room breathable while the repair is scheduled, so comfort during a fault is
  a first-class result with its own experiment (R-2).
- The monitor/supervisor split bounds what a wrong or crashed monitor can do:
  `fault-monitor` raises events only, so a wrong diagnosis cannot command anything by
  itself and a crashing monitor cannot stop ventilation; stale readings drive the monitor
  to `BLIND`, which is reported as unavailable and is not a fault (R-4).
- Every diagnosis is a residual series computed against a command that carries a
  `commandId`, filed under a `caseId` with its evidence and classification, so a diagnosis
  is reconstructable by construction rather than by a logging afterthought (R-5).
- The 60 s outer loop gives the monitor a fixed budget per cycle across all its rooms, so
  "rooms per monitor until the cycle is missed" is a natural breaking-point experiment
  with a stated failure criterion (R-6).
- Six rooms with one faulted and five observed make "several rooms" a property the
  evaluation exercises — a fault in one room must leave control and diagnosis of the other
  five unchanged — rather than a number in a configuration file (R-7).
- The report guide asks for fault injection and quantitative evidence at the higher
  grades; here fault injection is the subject, so detection delay, false-alarm rate,
  comfort during a fault and the scaling breaking point are the results the system exists
  to produce, not an experiment section added at the end (R-1, R-2, R-3, R-6).
- Every mechanism — a mass balance, a residual, a CUSUM, a short rule tree — can be derived
  at a whiteboard, which is what a solo oral without notes rewards; a learned one-class
  model is an optional contender measured against that baseline on identical seeds and
  kept only if it wins (R-1, R-3).

## Alternatives rejected

- **Zone access control with a coupled air-quality loop (A-01)** — the predecessor this
  record held from 2026-08-31 to 2026-09-10, and the reason ADR-0003 and ADR-0004 exist.
  Rejected because its defining authority question — fail-safe versus fail-secure per door
  class — was in truth the fire-egress question from A-03, outside the chosen domain, which
  is why the catalogue's own recommendation was to graft A-03's safety spine onto it; its
  physical loop was a bolt-on second simulator that was easy to under-build into decoration
  (the catalogue's first-listed risk, and the old record's own trade-off); BuildSim's
  `PUT /api/doors` replaces the whole collection, which forced a door-gateway process just
  to write state (ADR-0004) — infrastructure for the given interface rather than for the
  problem; its AI layer was advisory anomaly detection with no authority (ADR-0003), a thin
  measurable story; and fault injection was an experiment added to it, whereas in A-06 it
  is the subject. As a consequence, ADR-0003 and ADR-0004 are superseded by this record.
- **Fire growth, sprinkler suppression and egress routing (A-03)**, including the
  catalogue's recommended graft of its safety controller onto A-01 — the best demo on the
  sheet and the largest scope. Rejected because the smoke model is easy to over-model and
  because there is no defensible baseline to measure the system against, which weakens the
  quantitative evaluation the higher grades require.
- **Outdoor-air damper: particulates against CO₂ (A-11)** — the best effort-to-grade ratio
  on the sheet: one actuator, two objectives that genuinely fight, a Pareto plot. Rejected
  as the primary use case and **retained as the fallback**: if the P1 spike (T-04) shows no
  residual separation at realistic sensor noise, the plan's kill criterion switches the
  project to A-11 before week 38.
- **Energy-aware ventilation and climate control (A-02)** — the course's own worked
  example, whose container diagram, interface table and breaking point are already in the
  report example. Rejected because every design choice would be graded against the model
  answer the examiner wrote.
- **A digital-twin dashboard, reinforcement learning for HVAC, multi-floor everything** —
  open-loop, unsafe under comfort constraints, and the wrong starting scope respectively;
  the catalogue's *Considered and rejected* keeps each with its reason.

## Trade-off accepted

- The demo is charts — residuals, CUSUM traces and a work order on the 3D view — rather
  than a burning building; A-06 is the catalogue's safest grade-5 route and its most
  modest to watch.
- The fault model and the detector are both this author's, so the evaluation measures the
  detector against its own generator; the fault model is invented rather than measured,
  and the report says so.
- The observer wants occupancy, the one thing a real building does not have; judging
  commanded step responses, whose shape depends mainly on airflow and volume, is the least
  bad answer, and it is unproven under realistic noise.
- Three fault types are not a taxonomy: real air handling fails in dozens of ways, several
  of which look identical to these three, and two of the three signatures may collapse into
  one class under noise.
- A second, supervisory loop is harder to explain in the oral than a single loop, and it
  carries the masking trap: compensation shrinks the very residual that found the fault, so
  latching and active probing must be built and defended rather than assumed.
- "Several rooms" adds a coupling decision — what physically connects the six rooms
  (ADR-0008, T-34) — that must be taken before the physics simulator can be written, and
  it fixes what "compensate" means.

## Defence in one sentence

A-06 was chosen because a stuck damper and a full room look identical to a controller that
only sees CO₂, so the autonomous part must be an inference over what the last command
should have achieved — and fault injection and degraded operation are then the use case
itself rather than an experiment added at the end.
