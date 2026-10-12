# ADR-0012 — Service state survives a restart, as a JSON document on the service's own volume

- **Status:** Accepted
- **Date:** 2026-10-10
- **Decision owner:** Wassim Akachi
- **Scope:** `fault-monitor` and `supervisor`. Explicitly **not** `dcv-controller` — see below.

> **This record changed its mind once, on the day it was written, and the reversal is kept rather
> than tidied away because the reason was measured.** The first version chose **MongoDB** as a
> dedicated state store and accepted an eleventh compose service as its price. Building it showed
> the price was higher than stated: regenerating `diagrams/container.d2` with the extra box flips
> the C4 container diagram from **portrait 2147×2542 to landscape 3106×2592 and overprints edge
> labels**, and §4.2 includes it at `height=0.70\textheight`. The course's own submission checklist
> verifies that the container diagram is consistent with `docker-compose.yml`, so degrading that
> figure to document a store is a bad trade — and a store that no code used yet would have shipped
> as a service with no answer to "what is it for?". MongoDB is now a rejected alternative below,
> with the others.

## Context

Container isolation is structural here and it works: every service is its own OS process,
`restart: unless-stopped`, and §11.6 measures a killed container back in `running` within 3 s with
restart count 1. What a restart does **not** preserve is anything the restarted service had
concluded.

- `fault-monitor` holds a latched diagnosis per zone (`diagnosis/control/FaultMonitor.java:44`).
  The latch is the system's headline property — a confirmed fault stays confirmed until a
  technician clears it, which is what stops the supervisor oscillating — and it dies with the
  process. A restart is a back door through the one exit the design reserves for a human.
- `supervisor` holds its open cases and the plant baseline
  (`supervision/control/SupervisionService.java:82`, `:90`) and publishes work orders by PUTting
  the **whole** alert collection, so a restart does not merely forget them: the next publish
  **erases** them. The baseline is worse than forgotten — restarted empty, the first republished
  latched event re-snapshots a plant that is already trimmed, and the next solve compounds on top
  of its own trim.
- `dcv-controller` holds per-zone hysteresis (`ventilation/control/DcvControlLoop.java:50`).

The journal in TimescaleDB is not a recovery store and is not being made into one. ADR-0002 chose
it for R-5 — every diagnosis reconstructable — and `shared/control/ResidualJournalPort.java` is
append plus two replay queries over `timestamptz` windows. It is an **append-only record of what
happened**, which is a different lifetime from **what a service currently believes**.

Two requirements make this worth building rather than noting. The course's implementation
requirement 5 asks that processes be *"independently startable, stoppable, and restartable"*, and
a process that restarts into an empty conclusion meets that only in the weakest sense. And *"what
happens if your decision-making component becomes unavailable?"* is on the course's published list
of oral questions.

## Decision

**A JSON document on the service's own bind-mounted volume was chosen because the state is a
single whole-object snapshot per service with no query requirement beyond "give me my last state"
and exactly one writer; the alternatives were a dedicated MongoDB, a second PostgreSQL, and
replaying the existing TimescaleDB journal; and the trade-off accepted is that the state is
host-local, so it survives a container restart and not a move to another machine.**

Three facts make this the cheap option rather than the lazy one, and each is already true in the
repository:

| What it needs | What already exists |
|---|---|
| A JSON encoder | `jakarta.json-api` 2.1.3, declared in the parent `pom.xml` and used by `shared/boundary/EventCodec.java`, with 25 round-trip tests |
| A place to put it | `project/storage/`, already bind-mounted per service and already ignored by the root `.gitignore` |
| A precedent in this stack | `mosquitto` persists its own session state to `./storage/mosquitto/data`. A service keeping its state on a mounted volume is what the broker in this very compose file already does |

So the dependency hierarchy is satisfied at **step 2 — a Jakarta EE API** — rather than step 4,
and the compose file keeps its **ten services** and its one-box-one-service mapping onto the C4
container diagram.

**Where the code goes.** A port in `shared/control`, its adapter in `shared/boundary`, and no file
or JSON import anywhere in a `control/` or `entity/` package — the same rule ADR-0005 sets and the
same shape `TimescaleJournalAdapter` already has. The state written is an **immutable snapshot the
owning thread publishes**, never a live reference the adapter reads, which is the rule ADR-0011
applies to the telemetry gauges and for the same reason.

**The write must be atomic.** A process that crashes midway through rewriting its own state file
would otherwise come back to a truncated document, which is a worse failure than the one this
record is fixing: write a temporary file in the same directory and `Files.move` it over the target
with `ATOMIC_MOVE`. A document that cannot be read is treated exactly as a document that is not
there.

**`dcv-controller` persists nothing, deliberately.** Its hysteresis re-derives from the next CO₂
reading one second later, and the belief it restarts with is the *minimum* damper position, so
every recomputed demand is an opening instruction relative to it — the error is always in the
over-ventilating direction and R-2 cannot be breached by it. Its two pieces of state that *did*
matter, the control mode and the supervisory trim, were owned by the supervisor's publisher side
all along: the supervisor announced `DEGRADED` once per case and never again, so a restarted
controller silently resumed commanding a confiscated damper. That is fixed by re-asserting the
mode every `compensate()` cycle, not by persisting anything. **Writing state that the next message
overwrites anyway buys nothing and costs a document to keep honest.**

*Defence in one sentence:* the journal records what happened and this records what a service
currently believes; one writer, one reader, one whole-object read at boot is a file, not a
database, and the port in front of it is the seam that makes it a database the day the services
stop sharing a host.

## Alternatives considered

### A dedicated MongoDB — adopted, built, and then rejected

The document shape is a genuinely good fit: three unrelated state shapes with no shared schema,
written and read whole. It was adopted first for that reason. It was rejected on the measured cost
above — the C4 container diagram degrades when the box is added, and that figure is checked
against `docker-compose.yml` by the course's own checklist. Underneath that sits a simpler
objection: an eleventh container, a new driver at step 4 of the dependency hierarchy, and a second
store to explain, bought nothing over a file for a read pattern that is one point lookup per
service per boot. The document shape was never the argument for MongoDB; it is an argument for a
document, and a document is what this writes.

### A second, dedicated PostgreSQL — rejected

Costs the same extra container and buys a schema this data does not want: three unrelated shapes
become three tables plus migrations, to serve a lookup by service name. Two PostgreSQL instances
on one laptop for six rooms is also hard to defend as proportionate.

### Replaying the existing TimescaleDB journal — rejected, and it was close

The strongest alternative. `replayCase` and `replayZone` already exist and are already exercised
against a real PostgreSQL by `TimescaleJournalIntegrationTest`, so a boot-time rebuild of the latch
from the `FaultStateChanged` rows already written would need no new storage of any kind. Rejected
for three reasons:

1. **Replaying history to get state is a different operation from reading state.** A latch rebuilt
   by folding events has an answer that depends on the window the fold reads, and that window is a
   tuning parameter nothing measures. A document that says what the service believed has no window.
2. **It couples the evidence record to the recovery path.** A schema change made for recovery
   lands in the hypertables R-5 reconstructs from, and R-5 is a filled requirement with a measured
   result.
3. **One datum is not in the journal at all.** The `AhuFlowModel` damper pin exists only as
   formatted prose inside `decisions.evidence` (`ZoneMonitor.java:553-558`), and that pin is R-7's
   fix — `AhuFlowModel.java:37-52` records losing it as the measured cause of 411 spurious fault
   events and R-7 failing 80 of 270 rooms. Recovering a latch without its pin re-creates that
   defect. Putting it in the journal means a new column applied **by hand** against an
   already-initialised volume, because `init.sql` is mounted into `docker-entrypoint-initdb.d` and
   will not re-run — and possibly a change to a wire schema ADR-0006 freezes. In a document it is
   a field.

### Accept the loss and document it — rejected, having been the position until now

The two restart defects were filed as post-submission items and the limitation is stated honestly
in three places in the report. It is a defensible whiteboard answer. Rejected because the fix is
bounded and now cheap, and because a restarted supervisor does not merely forget work orders, it
erases them.

## Consequences

**Positive**

- A restarted `fault-monitor` keeps its latched diagnoses, which is the property the outer loop
  rests on; a restarted `supervisor` stops erasing live work orders and stops compounding its trim
  onto an already-trimmed baseline.
- **Ten compose services, unchanged**, so §4.2, `container.d2`, §9, Appendix G, the `Makefile` and
  the `RUNBOOK` all keep saying ten and the C4 mapping stays 1:1.
- **No new dependency.** The JSON API is already declared and already tested.
- Crash recovery becomes testable end to end without a database: write a document, restart the
  process, assert the conclusion survived.

**Negative / accepted risks**

- **The state is host-local.** A bind-mounted file follows the volume, not the service. The moment
  these containers are scheduled across machines this must become a shared store — which is why it
  sits behind a port rather than being called directly, and the port is the seam. This bound is
  stated in the report rather than left for a reader to find.
- **No history, no audit, no query.** Each write replaces the last. That is correct for state and
  would be wrong for evidence, which is why the journal is untouched.
- **Two stores with one rule between them**, which has to be sayable in one sentence or it will be
  got wrong under questioning: *the journal is what happened, the document is what is believed.*
- **A measured limitation is traded for a built capability.** The honest account of what the system
  could not do was itself evidence; what replaces it must be the recovery test and this record.
- Three report passages that state the old limitation become false and are rewritten with the
  change rather than after it: `04-architecture.tex:243`, `13-risks.tex:215`,
  `appendix-i-risks.tex:229`.

## Follow-up

- **Recovery must run before `subscribe`, and under the live path's lock.** `MqttAdapter` uses
  `cleanSession(true)` with a random client id, so there is no backlog to race — but equally no
  second chance at a `SERVICED` published during the gap. More sharply: `SupervisionService` and
  `FaultMonitor` are **not synchronised at all**, and are single-threaded today only by accident of
  one Paho callback thread plus `ConcurrentExecution.SKIP`. A boot-time writer makes that
  assumption false, and that is the most likely way this goes wrong.
- **The published figures must not move.** `evaluation/` constructs the control classes directly
  with a `SimulatedClock` and never calls recovery, so R-1/R-3/R-6 are safe **provided recovery
  lives in `boundary/`**. Prove it rather than argue it: run `make experiments` before and after
  and diff the result files byte for byte.
- A restored document carries the simulated timeline (`TIME_SCALE`, `REAL_EPOCH`) it was written
  under. At `TIME_SCALE = 1` the clock is the raw system clock and nothing is lost; above it, a
  restored instant is only comparable if the epoch is unchanged, which `restart: unless-stopped`
  preserves and a fresh `up` does not.
