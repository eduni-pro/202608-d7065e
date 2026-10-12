# ADR-0008 — Room set and coupling topology

**Status:** Accepted · 2026-10-05

## Context

The proposal was accepted on 2026-09-18 on one condition: *"make sure to include several
rooms"* (plan constraint 8). Six rooms on level 0 is decided; which six, and what physically
couples them, is not. The coupling fixes two things downstream: what the supervisor's
*compensate* (T-16, R-2) can physically do, and whether the monitor has any cross-room signal
at all. The plan's risk register names the failure this ADR exists to avoid: "several rooms"
becoming replication — six copies of the single-room spike with nothing one room can do to
another.

BuildSim provides geometry and no physics (`tutorials/buildsim.md`), so both the air and the
plant are simulated, and every coupling parameter below is invented rather than measured.
The options therefore count their invented parameters, because each one is something the
report's critique (T-32) has to own.

Two requirements pull against each other:

- **R-2** — compensation keeps the faulted room ≤ 1000 ppm for ≥ 95 % of occupied minutes.
  That needs a physical lever that reaches the faulted room from outside it.
- **R-7** — a fault in one room leaves control and diagnosis of the other five unchanged.
  Every lever that reaches across rooms puts that at risk.

## Part 1 — the six rooms

Source: `buildingsim/data/abuilding/level0/floorplan_data.json` (`rooms[]` and
`walkable_graph`). Coordinates are metres — the page is 384.6 × 365.9 and an 11.0 × 7.2 m
office polygon carries the listed 76.5 m² — so `area` is taken as m² as given.
**Assumption:** ceiling height 3 m, so volume = area × 3 m; the JSON carries no height.

Chosen: the cluster on corridor **A120**.

| Room (BuildSim key) | Area m² | Volume m³ | Intended type and schedule | Centre (x, y) m |
|---|---|---|---|---|
| `level0/A125A` | 18.0 | 54.0 | single office, 1 person, 08–17 with a lunch dip | (310.66, 312.92) |
| `level0/A125` | 29.8 | 89.4 | two-person office, staggered arrival and departure | (316.38, 315.58) |
| `level0/A116A` | 33.0 | 99.0 | project room, 3–4 people, intermittent use | (322.63, 302.33) |
| `level0/A115` | 44.0 | 132.0 | meeting room, 10 seats, booked 1-hour slots | (315.25, 292.88) |
| `level0/A124` | 76.5 | 229.5 | open-plan office, 8 desks, continuous daytime occupancy | (319.30, 308.10) |
| `level0/A145` | 74.0 | 222.0 | seminar room, 20 seats, booked 2-hour slots, empty between | (337.75, 305.95) |

Why these six:

- **Adjacency.** Every one of the six has a `room → entry → corridor` path in the walkable
  graph that ends at the corridor node `A120` and at no other corridor, and the six are
  within 59 m of one another along it. Wall adjacency, from shared polygon edges (parallel
  within 1 m — walls are drawn as solids about 0.85 m thick — overlapping ≥ 0.5 m):
  A124–A125 share 4.7 m of wall; A125 and A125A each share a wall with the 8 m² anteroom
  A125B; A145 sits directly across the corridor from A124 and A116A; A115 and A116A touch
  only closets (A115A, A115B, A116). So "neighbour" means *same corridor branch* for all six
  and *shared wall* for A124–A125.
- **Sizes.** Five distinct sizes spanning 18–76.5 m², a 4:1 span in volume, so the time
  constant V/Q and the occupancy schedule differ room by room. At least two room types
  (offices, meeting rooms) as the task requires.
- **Addressable.** BuildSim qualifies rooms by `level` plus name or by the key
  `level0/<name>`; all six names are unique on level 0. A116A has no entry in `labels[]`
  (that array only drives label rendering) but is in `rooms[]` and in the graph.

Sixth-room tie-break: A142–A153 across the corridor are twelve near-identical ~74 m²
offices. A145 is the one with the smallest mean walkable distance to the other five
(31.4 m) and faces A124 directly, so the choice is reproducible.

**Rejected: the corridor A170 cluster** (A162–A169, A17, A171C). Its wall adjacency is
better — A16 to A169 is a chain of 11 m shared walls — but it is one size class: eight of
ten rooms are 69–79 m², the other two 55 and 61 m², and A17/A171C sit across the corridor
25 m from the row. Distinct sizes matter more than shared walls here: they are what make
six schedules and six time constants different, and that difference is what R-7's
"unchanged" is measured against. Six same-size rooms would pass R-7 more easily and mean
less.

## Part 2 — the coupling topology

Common to all three options: per room, a well-mixed CO₂ balance
`V·dC/dt = n·g·10⁶ − Q·(C − C_out)` with occupancy `n` from the schedule, generation
`g ≈ 0.005 l/s` per person, outdoor `C_out ≈ 420 ppm`, and airflow `Q` set by the damper.
The options differ in where `Q` comes from and whether anything else enters the balance.

The `10⁶` converts a volumetric generation rate into the ppm the concentration is measured
in: `V` is m³, `Q` m³/s and `g` m³/s, so without it the production term is a volume fraction
while the dilution term is ppm, and the two cannot be added. It is written out here because
an earlier revision of this record omitted it, and because it is the factor that makes the
sizing above come out right — 165.9 l/s holds A145 at roughly 1000 ppm with its 20 design
occupants, which is the coincidence the fan sizing leans on.

### Option A — independent rooms, one fan and one damper each

- **Simulator models:** six unconnected copies of the balance; a fan per room, so fan power
  is a per-room signal.
- **Monitor infers:** one residual per room from the local command; a blocked filter is a
  local fan-power anomaly (six filters, one per unit). There is no cross-room signal.
- **Compensate means:** nothing reaches the faulted room from outside it. Raising the
  room's own fan is the only ventilation lever left, and it is B's mechanism without B's
  plant-level signal; beyond that, a work order with occupancy advice. "Over-ventilate the
  neighbour" has no referent.
- **Invented:** six fan curves and six filter factors — the single-room parameters, six
  times.
- **At the whiteboard:** easy to draw, hard to defend. The examiner asked for several rooms
  and this is one room replicated; R-7 holds by construction and its experiment (T-34b)
  measures nothing.

### Option B — one shared air-handling unit (fan and filter), a damper per room

- **Simulator models:** one AHU with fan flow `Q_fan = Q_nom·s·(1 − β)` and power
  `P = P_nom·s³·(1 + β)` (affinity law in the speed `s`; `β` is filter blockage, so a
  blocked filter costs more power for less air, as the proposal states), and a flow split
  `Q_i = Q_fan·a_i / Σ a_j` over the six damper openings `a_i` — the simplest law under
  which the dampers interact. `Q_nom` is sized from the rooms with the Swedish workplace
  guideline (7 l/s per person plus 0.35 l/s per m²): A145 at 20 seats needs ≈ 170 l/s,
  which is also the flow that holds 1000 ppm at 0.005 l/s CO₂ per person, so the two rules
  agree.
- **Monitor infers:** the per-room residual as before, plus two plant-level facts — fan
  power belongs to the AHU, and the six residuals have a common mode. Stuck damper: one
  residual rises, fan power unchanged. Blocked filter: all six rise together and power per
  unit of flow rises. Sensor drift: one residual, step-response shape unchanged, overnight
  floor wrong. Localisation comes from comparing rooms, not from a fourth invented signal.
- **Compensate means:** trim the five healthy dampers, which under the split pushes flow
  to the frozen one — that is "compensating from a neighbour" — and raise the fan speed so
  the neighbours' own flows stay where they were (R-7). Works if and only if the damper is
  stuck partly open — the realistic case, since a
  demand-controlled damper holds a minimum position during occupied hours. Stuck shut:
  there is no path; work order plus occupancy advice, and the report says so.
- **Invented:** `Q_nom`, the proportional split, the affinity exponent, `β`, the minimum
  damper position. All are textbook VAV quantities with a stated basis, and none of them
  alone decides whether R-2 passes — the stuck position does.
- **At the whiteboard:** AHU → duct → six dampers. Why fan power is a plant signal. Why
  one frozen damper barely moves the other five (the split shifts by that damper's share,
  which the healthy controllers absorb — the small coupling T-34b must show does not trip
  R-3). Why compensation needs `a_f > 0`.

### Option C — B plus inter-room air exchange between adjacent rooms

- **Simulator models:** B, plus an exchange term `Σ_j E_ij·(C_j − C_i)` in each balance.
  On this floor only A124–A125 share a wall, so coupling six rooms means making the
  corridor A120 a seventh volume with a door-leakage coefficient per room.
- **Monitor infers:** everything B gives, but a healthy room's residual now moves with its
  neighbour's CO₂. Either every observer takes the neighbours' readings as inputs — six
  coupled observers — or a faulted room produces false residuals next door.
- **Compensate means:** the proposal's literal wording — over-ventilate the neighbours so
  the exchange dilutes the faulted room. It reaches a damper stuck shut, which B cannot.
  The catch: through a closed office door the exchange is of the order of tens of m³/h
  against a supply need of hundreds, so R-2 passes only if `E_ij` is set large, and then
  the result is a property of the coefficient rather than of the design.
- **Invented:** everything in B, plus the exchange matrix and its dependence on door state.
  `E_ij` is the parameter that decides R-2, and there is nothing to measure it against.
- **At the whiteboard:** defend a number nobody measured, and explain why R-7 still holds
  when the physics couples every room to its neighbour by construction.

## Recommendation

**Option B.**

1. It turns the examiner's condition into design rather than replication: the shared plant
   couples the rooms, so keeping five rooms unchanged while compensating the sixth is
   something the supervisor must actively do (trim the dampers), and T-34b can catch it
   failing.
2. It gives the monitor its one cross-room signal — plant fan power and the common-mode
   residual — which separates a blocked filter from a stuck damper without inventing a
   fourth signal (risk register: "do not invent a fourth signal").
3. Its invented parameters are the ones any VAV textbook carries, and none of them alone
   decides whether R-2 passes; C's exchange coefficient does.
4. It is how office corridors are typically ventilated — one AHU per duct branch, one
   damper per room — and corridor A120 is the natural place to put the AHU as one `hvac`
   equipment item in BuildSim.

### Sub-decision — who applies fan-speed commands

Consequence 4 below left this to T-06. Decided: **`damper-actuator` also applies the AHU
fan-speed commands.** No ninth container is added. A separate `fan-actuator` would be a
folder under `project/`, a service in `docker-compose.yml` and a box on the C4 L2 diagram
(constraint 4, container ↔ compose ↔ folder is 1:1) for a single PUT against BuildSim's
`fan-speed` actuator, which is not worth a container.

Stated honestly: the container keeps the name `damper-actuator` while its responsibility is
now *apply validated HVAC actuator commands to BuildSim* — dampers and the one fan. The name
is narrower than the job. Renaming it would touch the folder, the compose service, the
diagrams and §3 of the plan for no behavioural gain, so the name stays and this record is
where the mismatch is written down.

## Parameters

Decided with the topology, so that the six rooms, the simulator, the controller and the
observer are all written against one set of numbers. **Every value here is invented, not
measured** — BuildSim provides geometry and no physics — but each is derived from a stated
rule rather than picked. The derivations are listed so that **T-32's critique names them**:
they are precisely the quantities the report may not claim to have validated.

### Per-room design flow

From the Swedish workplace guideline already cited in Option B: **7 l/s per person plus
0.35 l/s per m²**. Design occupancy is the room's intended use from Part 1; A116A's
"3–4 people" is designed at 4, its upper bound.

| Room | Design occupancy | Area m² | 7 l/s × persons | 0.35 l/s × m² | Design flow l/s |
|---|---|---|---|---|---|
| `level0/A125A` | 1 | 18.0 | 7.0 | 6.3 | **13.3** |
| `level0/A125` | 2 | 29.8 | 14.0 | 10.4 | **24.4** |
| `level0/A116A` | 4 | 33.0 | 28.0 | 11.6 | **39.6** |
| `level0/A115` | 10 | 44.0 | 70.0 | 15.4 | **85.4** |
| `level0/A124` | 8 | 76.5 | 56.0 | 26.8 | **82.8** |
| `level0/A145` | 20 | 74.0 | 140.0 | 25.9 | **165.9** |
| **Σ** | **45** | **275.3** | **315.0** | **96.4** | **411.4** |

A145's 165.9 l/s is the ≈ 170 l/s Option B quotes, and it is also the flow that holds
1000 ppm at 20 people and `g` below — the two rules agree, as Option B claims.

### Air-handling unit

| Parameter | Value | Derivation |
|---|---|---|
| `Q_nom` | **0.59 m³/s** (590 l/s) | Σ design flow 411.4 l/s ÷ `NORMAL_FAN_SPEED` 0.7 = 587.7 l/s, rounded up. The plant is sized to cover the whole simultaneous design condition **at the speed it normally runs**, not at full speed — see below. |
| `P_nom` | **400 W** | 0.59 m³/s × 400 Pa ÷ 0.6 fan efficiency ≈ 393 W, rounded up. At the normal speed this is 400 × 0.7³ = **137 W**. |
| Fan speed `s` | **[0.2, 1.0]** | The supervisor's compensation lever; invariant on `FanSpeed` |
| `NORMAL_FAN_SPEED` | **0.7** | The speed while nothing is wrong. Raising fan speed *is* the compensation lever, so the plant must not already sit at maximum: at `s` = 1 there would be no headroom and R-2 would rest on the damper trims alone. 0.7 leaves **43 %** for compensation to spend (1.0/0.7 − 1). |

`P = P_nom·s³·(1 + β)` and `Q_fan = Q_nom·s·(1 − β)` as in Option B. The blockage `β` is a
fault-injection parameter, not a design parameter, and is fixed by T-14 rather than here.

**Why `Q_nom` is not the design total.** The obvious sizing — `Q_nom` = Σ design flow, rounded
up — was the first choice here, and it is wrong once the fan runs below maximum. It makes the
plant meet the simultaneous design condition *only* at `s` = 1, which is exactly the speed
compensation needs to borrow from. Running such a plant at 0.7 delivers 0.42 × 0.7 = 294 l/s
against a 411.4 l/s design total: **71 % of design flow**. The evaluation measured what that
costs, and it is not subtle — with **nothing broken**, no room could be held below 1000 ppm at
design occupancy (comfort fraction 0.000) and the monitor confirmed a fault in **all six**
rooms, because a uniformly starved building looks exactly like a building with a problem.

Sizing instead from `Σ design ÷ NORMAL_FAN_SPEED` separates the two jobs the fan rating was
doing at once: the plant covers its design condition at the speed it actually runs, and the
margin to `s` = 1 is reserved for compensation rather than being spent on normal operation.
Both numbers now hold simultaneously, which they did not before.

This is a real engineering trade and not a free fix. A correctly sized plant has margin, and
**margin masks faults**: at the new rating a filter blocking 35 % no longer starves the rooms
enough for the monitor to confirm it, where at the undersized rating it did. Detection
sensitivity was being bought with occupant comfort. That is the wrong currency, so the trade is
made deliberately in comfort's favour and its cost is reported rather than hidden.

### Damper opening law

`a_i = position_i × A_i_max`, where `A_i_max` is proportional to that room's design flow:

```
A_i_max ∝ 13.3 (A125A) · 24.4 (A125) · 39.6 (A116A) · 85.4 (A115) · 82.8 (A124) · 165.9 (A145)
Σ = 411.4
```

and the flow split is `Q_i = Q_fan · a_i / Σ a_j` as in Option B.

**Consequence:** at the normal speed with every damper fully open, each room receives its
design flow × 413/411.4 ≈ **1.004** — the simultaneous design condition, met with nothing to
spare. At `s` = 1 the same rooms receive `Q_i = 590 × design_i / 411.4` ≈ **1.43** × design
flow, and that 43 % is the whole of the compensation budget. The only way a room gets more
than its share at a fixed speed is if another damper closes. That is the coupling, and it is
what makes compensation possible and R-7 non-trivial at the same time.

| Parameter | Value | Why |
|---|---|---|
| `minimumDamperPosition` | **0.15** | Ventilation never fully closes. This is the floor a demand-controlled damper holds during occupied hours, so it is also the position a "stuck shut" damper actually sticks at, and the position **R-2's scenario pins**. Compensation has to work from here, not from zero. |

### Inner-loop control band

The `dcv-controller`'s two thresholds, which had been attributed to the plan and appear in no
other document. Recorded here because this table is the single source for every number:

| Parameter | Value | Derivation |
|---|---|---|
| `openAbove` | **800 ppm** | 200 ppm below the comfort limit. The proportional ramp runs from `minimumDamperPosition` at this threshold to fully open **at** 1000 ppm, not past it — a ramp still accelerating when the room leaves comfort would make R-2 a claim about a controller that had not finished acting. 200 ppm of travel is the part of the 420 → 1000 ppm span a room may spend before the damper is doing everything it can. |
| `closeBelow` | **650 ppm** | 150 ppm below `openAbove`, and the gap *is* the hysteresis band. Sized against sensor noise rather than picked round: at σ = 30 ppm the band is **5 σ**, so noise alone cannot walk a reading across both edges and set the damper chattering. Chatter is not only inelegant here — the observer keys its prediction on command steps, so a controller oscillating on noise would bury the one step response the diagnosis reads. |

Both sit strictly between `C_out` = 420 ppm and the 1000 ppm comfort limit, which the
`ControlSettings` compact constructor enforces rather than assumes.

### CO₂ and detection

| Parameter | Value | Source |
|---|---|---|
| `C_out` | **420 ppm** | Outdoor concentration; Part 2's common balance |
| `g` | **5e-6 m³/s** (0.005 l/s) per person | CO₂ generation; Part 2's common balance |
| Sensor noise σ | **30 ppm** | T-04 spike |
| CUSUM `k` | **30 ppm** (1 σ) | T-04 spike |
| CUSUM `h` | **150 ppm** (5 σ) | T-04 spike |

All four are carried over unchanged from the T-04 spike, which measured **16.7 σ** separation
between the healthy and the stuck-damper post-command mean residual and **0 of 20** false
alarms at `k` = 30 ppm, `h` = 150 ppm. T-24 sweeps `h` over 4 σ–8 σ anyway; 150 ppm is the
starting point, not a claim that it is optimal.

## Trade-off accepted

- A seventh simulated element, the AHU, with a fan curve and a flow-split law — more
  invented physics than A, and it must appear in T-32's critique.
- Compensation has a floor: a damper stuck shut cannot be reached. R-2's scenario pins the
  stuck position at the damper's minimum, and the report states which cases compensation
  does not cover.
- The proposal's "over-ventilates its neighbour" becomes "over-drives the shared air
  handler and trims the neighbours' dampers" — to be noted in the report's "changes since
  the proposal" account.
- A small cross-room effect in normal operation (one frozen damper shifts the split for the
  others) that must be shown not to raise false alarms (R-3) or change control modes (R-7).
- Every per-room observer needs the AHU state — fan speed and, through the split, the other
  five damper positions — as an input. The price of a cross-room signal is a cross-room
  model.

## Consequences for the plan's fixed vocabulary (§3)

**Applied.** With this record Accepted on 2026-10-05, all ten items below are written into
`project/aiadocs/PLAN-A06-implementation.md` §3, and the plan — not this list — is what
workers read. The list stays as the record of what the topology cost the vocabulary.

1. Topic `readings/{zone}/fan-power` becomes `readings/{ahu}/fan-power`
   `{ahu, watts, ts, quality}` — there is one fan.
2. A fan-speed command topic `commands/{ahu}/fan-speed`
   `{ahu, speed, ts, reason, commandId}` and an owner for it — the supervisor, since it is
   the compensation lever; T-16 fixes this.
3. A new aggregate `AirHandlingUnit` (id, nominal flow, commanded speed, the zone ids it
   serves; invariant: commanded speed within its range) with value objects `AhuId` and
   `FanSpeed`. `VentilationZone` gains `ahuId` and `minimumDamperPosition`.
4. `co2-sensor` reads six room CO₂ values and one AHU fan-power value — the plan already
   has it reading fan power; only the location moves from room to AHU. `damper-actuator`
   also applies the AHU fan-speed commands; no `fan-actuator` container is added. See the
   sub-decision under Recommendation for why, and for the name/responsibility mismatch it
   leaves behind.
5. BuildSim placement: one `hvac` equipment item `AHU-A120` in room `A120` with a
   `fan-power` sensor and a `fan-speed` actuator; six `co2` sensors and six `damper`
   actuators in the rooms above.
6. T-16 compensation: raise fan speed, trim the healthy dampers to hold their flows, carry
   the trim in `strategy/{zone}` for the healthy five; stuck shut → work order plus
   occupancy advice.
7. T-11 observer inputs: fan speed and the other five damper positions (via the split).
   T-15 classifier gains the common-mode residual across rooms.
8. T-18 active probe stays per room (a damper step); a fan-speed probe would be plant-wide
   and is out.
9. R-7's test gains an assertion on the healthy rooms' *airflow*, not only their CO₂,
   during compensation.
10. The exchange matrix and the corridor as a seventh volume were Option C's consequence
    alone, and C is rejected: `physics-simulator` models **no** inter-room air exchange, and
    an observer subscribes to its own room's `readings/{zone}/co2` plus the AHU state, not to
    all six rooms. Nothing to apply.

Monitor states, `FaultCase`, and the remaining topics are unchanged. The six rooms and
their volumes are carried into plan §3 as a table as well, so that every worker reads one
room list; the Parameters section above is the single source for every number in it.

## Defence in one sentence

One air handler feeding six dampers is how an office corridor is actually ventilated, it
hands the monitor the one cross-room signal that tells a blocked filter from a stuck damper,
and it makes keeping five rooms unchanged while compensating the sixth something the
supervisor has to earn rather than something the topology gives away.
