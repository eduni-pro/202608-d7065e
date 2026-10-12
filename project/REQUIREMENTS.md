# Requirements

Requirements R-1…R-7, stated here in full. The report carries the same seven rows twice over: §3
adds each requirement's measured verdict and the regime that verdict holds in, and Appendix A adds
the evidence column behind them. The requirements are those of use case
**A-06** — ventilation fault detection, diagnosis and graceful degradation
([ADR-0001](decisions/ADR-0001-use-case.md)) — over the six rooms of corridor A120 and the one
shared air handler fixed by [ADR-0008](decisions/ADR-0008-room-topology.md).

Statements are written in **EARS** form: an optional `When` (event), `While` (state) or `If`
(unwanted condition) precondition, then `the <system> shall <response>`. A statement with no
precondition is a ubiquitous requirement that holds at all times.

| ID | Statement (EARS) | Acceptance criterion | Verification |
|---|---|---|---|
| R-1 | When a damper stops responding to commands, the system shall confirm the fault. | p95 confirmation ≤ 15 min after the first ignored command | Injection harness, ≥20 runs, report p95 |
| R-2 | While a fault is latched, the system shall keep the affected zone below 1000 ppm by compensation. | Zone CO₂ ≤ 1000 ppm for ≥95 % of occupied minutes during the fault | End-to-end scenario test |
| R-3 | The monitor shall raise no more than one false alarm per simulated week. | ≤1 `FaultStateChanged` reaching `CONFIRMED` in a 7-day fault-free replay | Replay test on fixed seed |
| R-4 | While readings are stale beyond three intervals, the monitor shall report unavailable and issue no diagnosis. | State is `BLIND`; zero fault events published | Fault injection: kill `co2-sensor` |
| R-5 | The system shall make every diagnosis reconstructable. | Inputs, command, residual series, classification and timestamp recoverable for any `caseId` | Rebuild a random case from the journal |
| R-6 | The system shall identify the zone count at which the monitor misses its interval. | Sustained zone count where p95 monitor cycle exceeds 60 s is reported | Scaling experiment to breaking point |
| R-7 | While a fault is latched in one room, the system shall keep control and diagnosis of every other room unchanged. | Injecting a fault in one of six rooms produces zero fault events and no control-mode change in the other five; their CO₂ **and their airflow `Q_i`** stay within the healthy-run band | Injection in one room, assertions over the other five on both CO₂ and airflow, ≥3 rooms as the injected one |

## Traceability

Plan §2, constraint 5: **every requirement has an ID; every test names the ID it verifies;
every experiment names the requirement it produces evidence for.** A test that cannot name a
requirement is either testing something nobody asked for or testing a requirement that is
missing from this file — both are findings, not reasons to skip the ID.

| Requirement | Produces evidence in |
|---|---|
| R-1 | T-24 (delay vs CUSUM limit `h`), T-25 (baseline comparison), T-29 (optional learned contender) |
| R-2 | T-26 (comfort during a fault, with and without compensation) |
| R-3 | T-25, T-29 |
| R-4 | T-27 (kill each container in turn) |
| R-5 | T-20 (journal writer with provenance) |
| R-6 | T-28 (scaling to breaking point) |
| R-7 | T-34b (room isolation, rotating the injected room) |

## Bound on R-2

R-2 is bounded by the case [ADR-0008](decisions/ADR-0008-room-topology.md) identifies:
compensation works by raising the shared fan and trimming the five healthy dampers, which
pushes flow to the faulted room **only if that room's damper is stuck partly open**. R-2's
scenario therefore pins the stuck position at `minimumDamperPosition` = 0.15, the floor a
demand-controlled damper holds during occupied hours. **A damper stuck fully shut cannot be
reached at all** — there is no air path, and the response is a work order plus occupancy
advice, not compensation. R-2 is a claim about the reachable case, and the report says which
cases it does not cover.

## Not yet covered

Kept here so the gaps are visible rather than discovered during the report. Each of these is
real work the plan already schedules, but with no requirement ID attached, so nothing in §4
constrains it and no test can name an ID for it.

- **Data quality (T-21)** — missing, stale and duplicate readings. R-4 covers *stale* only,
  and only insofar as it drives `BLIND`. There is no requirement on duplicate suppression, on
  out-of-order arrivals, or on what the `quality` field in `readings/{zone}/co2` must mean.
  T-21 will have to invent its own acceptance criteria unless a requirement is written first.
- **Dashboard (T-23)** — no requirement states what the 3D view must show, how fresh it must
  be, or what counts as the fault being visible. T-23's verification is "screenshot evidence
  for the report", which is a deliverable, not a criterion.
- **R-2's bound** — see above. The bound is recorded here and in ADR-0008 but is not itself a
  requirement, so no test asserts that the stuck-shut case produces a work order rather than a
  compensation attempt.
