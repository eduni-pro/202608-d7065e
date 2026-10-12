# ADR-0009 — The healthy rooms' airflow bound stays at 5 %, in both directions

**Status:** Accepted · 2026-10-05

> **Amended 2026-10-06** — the decision stands; three figures it was measured on have since moved,
> including "R-7 still passes 270 of 270". See the amendment at the end of this record.

## Context

R-2 asks that a latched fault leave the affected zone at or below 1000 ppm for at least
95 % of occupied minutes. T-26 measures it in four regimes and it passes three of them on
5 of 5 seeds. It fails one: **design occupancy**, every room simultaneously at the head
count [ADR-0008](ADR-0008-room-topology.md) sized its damper authority from, where the
supervised arm holds **0.024** of occupied minutes and **0 of 5** seeds meet the
criterion. The no-fault arm in that same regime scores **1.000 on 5 of 5**, so the plant
is correctly sized and the failure belongs to the fault plus the compensation limit, not
to the building.

`CompensationSolver` bounded a healthy room's airflow by one symmetric constant,
`HEALTHY_FLOW_TOLERANCE = 0.05`: the trim may take at most 5 % of a healthy room's own
flow, and a fan raise may give it at most 5 % on top. The second half was the suspect.
Over-supplying a healthy room costs fan energy and not comfort, so capping it at 5 % looks
like treating surplus air as a harm — and the limitation text the solver itself prints
names that cap as the binding one:

> *trim stops at 71 %; above fan 79 % the healthy rooms move by more than 5 %*

The question this record answers is therefore: **should the over-supply side be loosened,
asymmetrically, while a fault is latched, to buy R-2 its design-occupancy case?**

## Decision

**No.** The two sides are separated in the code — `healthyStarveTolerance` and
`healthyOversupplyTolerance` — because they are genuinely different quantities, but they
are **set equal at 0.05** and the solver's behaviour is unchanged.

The separation is kept because it is the seam T-26 measures the question through, and
because the asymmetry is real: taking air from a healthy room can cost it comfort, which
is what R-7's airflow criterion is about; giving it air costs only fan power.

## Because

Every number below is from a measured run on seeds 1…5 at design occupancy. Each sweep
setting is a clean rebuild of `supervisor` and `evaluation` followed by T-26, T-25 and
T-34b — the three experiments that carry R-2, R-3 and R-7 — so each row's R-3 and R-7
columns are measured at that setting and not carried over. The whole suite and the whole
harness were re-run on the committed state, which is the 0.05 row.

**1. No setting of the over-supply allowance buys a single occupied minute.** The
allowance was swept closed-loop, one build per setting:

| healthy over-supply allowance | R-2 comfort fraction, design occupancy | seeds met | mean CO₂ in A124 | worst healthy ΔQ the plan claims |
|---|---|---|---|---|
| **0.05 (in force)** | **0.024** | **0 of 5** | 1606 ppm | 0.0500 |
| 0.10 | 0.024 | 0 of 5 | 1506 ppm | 0.1000 |
| 0.20 | 0.024 | 0 of 5 | 1479 ppm | 0.2000 |
| 0.43 (the fan's own ceiling) | 0.024 | 0 of 5 | 1472 ppm | 0.2743 |
| 0.99 (unbounded in practice) | 0.024 | 0 of 5 | 1472 ppm | 0.2743 |

The mean concentration does move — 1606 → 1472 ppm, 134 ppm of real air — and **the
criterion does not move at all.** R-2 is a count of minutes at or below 1000 ppm, and the
room is 472 ppm above the limit at the best setting. Paying for a requirement's mechanism
and getting none of its verdict is the whole result.

**2. The arithmetic says why, and it is the plant and not the bound.** With no healthy
damper left to trim, `A(t) = A₀` and both ratios collapse to `r = g = s/s₀`: the frozen
room's gain *is* the healthy rooms' over-supply, so the lever is not selective and is
capped by the plant at `FanSpeed.MAXIMUM / NORMAL_FAN_SPEED` = **1.43**. The gain the
regime asks for, read off the room's own measurement, is **2.00 to 3.30**. A bound that
is loosened past the thing it bounds buys nothing, which is why rows 0.43 and 0.99 above
are identical. This is asserted rather than recounted, in
`CompensationSolverTest.shouldNotExceedThePlantsFanCeilingHoweverLargeTheOverSupplyAllowance_R2`.

**3. Where R-2 *is* reachable it costs 31 to 39 times R-7's bound.** T-26 now measures the
plant's own bound: every room the monitor is not holding latched driven to its own minimum
position — per room, not by one common trim, so it beats any trim shape — and the fan at
its ceiling. That is the most air the shared split can route into the faulted room.

| seed | zones frozen | healthy rooms | gain asked | A124 at the bound | A124 ppm | most given a healthy room | most taken | worst healthy ppm | reachable |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 6 | 0 | 3.30 | 25.9 l/s | 1964 | +0 % | +0 % | – | **no** |
| 2 | 2 | 4 | 2.00 | 112.7 l/s | 775 | **+153 %** | −8 % | 863 | yes |
| 3 | 6 | 0 | 2.81 | 29.2 l/s | 1791 | +0 % | +0 % | – | **no** |
| 4 | 2 | 4 | 1.00 | 101.9 l/s | 812 | **+193 %** | −14 % | 910 | yes |
| 5 | 3 | 3 | 2.56 | 81.4 l/s | 912 | **+132 %** | **−29 %** | **1033** | **no** |

The reachability column requires *every* room under the limit, because reaching the faulted
room by starving a healthy one is not reaching it — and that is exactly what happens on
seed 5. **The premise that surplus air costs energy rather than comfort does not survive
the measurement:** a per-room trim takes air from the open healthy rooms as well as giving
it to the nearly-closed ones, so the `most taken` column is negative wherever the lever is
used at all, and on seed 5 it settles A145 at 1033 ppm.

So R-2's design-occupancy case is **unreachable on 3 of 5 seeds by anything the supervisor
can command**, and on the 2 where it is reachable the price is +153 % and +193 % on a
healthy room's airflow. R-2 needs 5 of 5. **The change cannot make R-2 pass, and it would
break R-7 to fail R-2 anyway.**

**4. What actually binds is outside this component.** On seeds 1 and 3 the monitor holds
**all six** rooms latched at design occupancy, so there is no healthy damper to trim and
the fan is the only lever — that is R-3's false-alarm rate (measured 1.30 confirmed cases
per fault-free week, already failing) arriving in R-2's experiment. On seed 4 A124 is never
latched at all, so the supervisor is never asked — that is R-1's closed-loop arm (15 of 20
detected, p95 257 min, already failing). Neither is in `supervisor/`'s gift.

**5. The energy is not free either.** The affinity law charges `(s/s₀)³`: 0.70 → 1.00 is
**2.92×** the fan power, about 137 W to 400 W on this plant. Spending it for 158 ppm of
mean concentration and zero occupied minutes is not a trade worth making.

## Alternatives rejected

- **Loosen the over-supply side to 0.10 / 0.20 / 0.43.** Rejected on the measurement in
  §1: 0.024 and 0 of 5 at every setting, while the plan's claimed healthy deviation rises
  to 0.2743 — five times R-7's bound, which `IsolationExperiment` gates on. R-3 stays at
  1.30 and R-7 still passes 270 of 270 at every setting, because `IsolationExperiment`
  runs the drawn weekday, where the solve asks for a gain near one and the over-supply
  lever never fires. That was measured rather than assumed.
- **A per-room trim instead of one common multiplicative factor.** Strictly the stronger
  lever: one satisfied room sitting at 0.15 currently pins the common trim floor for all
  five, and a per-room trim removes that. It is the lever the §3 bound is computed with,
  and it reaches 2 of 5 seeds. Rejected because 2 of 5 still fails R-2 while costing
  +193 % on a healthy room's airflow, because on a third seed it starves A145 to 1033 ppm
  and so buys R-2 by breaking R-2 next door, and because per-room trims cannot be held by
  any single fan speed, so unlike the common trim they have no cost-free operating point at
  all. Recorded rather than discarded: it is the only lever that reached the limit, and if
  R-7's airflow criterion is ever re-priced this is where to look.
- **Make the starve side comfort-based rather than flow-based**, so a healthy room may give
  up air down to the flow that holds it at the comfort target. Rejected: 5 % of a healthy
  room's airflow is precisely the quantity R-7 is asserted against, and replacing it with a
  looser rule derived from the same CO₂ the compensation is chasing is weakening an
  assertion, not finding air.
- **Raise `Q_nom` or `NORMAL_FAN_SPEED`.** Out of this component's hands and already
  rejected by ADR-0008: the margin is what masks faults.

## Trade-off accepted

**R-2 keeps a named, measured failure instead of a bought pass.** The requirement passes
on 5 of 5 seeds in three regimes and fails 0 of 5 in one, and this record is why that
failure is not closed: the only levers that would reach it are a false-alarm rate that is
R-3's, a detection miss that is R-1's, and a healthy-room airflow budget that is R-7's —
and on one seed in five not even all three together, because the air to fix A124 would have
to come out of A145 and put A145 over the limit instead.
The supervisor is left spending exactly the 5 % it is allowed and reporting the gain it
actually achieved, including when that gain is short.

The cost of keeping the bound is 134 ppm of mean concentration in the faulted room at
design occupancy that the plant could have delivered. That is stated rather than absorbed.

## Defence in one sentence

Loosening what a fan raise may give a healthy room was swept from 5 % to unbounded and
bought R-2 exactly zero occupied minutes in the regime it fails, because with every healthy
damper on its floor the fan lever is not selective and the plant caps it at 1.43 against the
2.0–3.3 the regime needs — so the 5 % bound stays, and R-2's failure is recorded with its
real causes rather than traded against R-7.

## Amended 2026-10-06: three of the figures this record was decided on have since moved

The decision stands — the bound stays at 5 % in both directions — and the figures above are left as
they were measured on 2026-10-05, because they are what the decision was taken on. What later
measurement showed, recorded rather than quietly applied:

- **"R-7 still passes 270 of 270 at every setting" was true of the experiment as it then stood and
  is no longer true of R-7.** The sentence says why in its own clause: `IsolationExperiment` ran the
  drawn weekday only. It has since grown a design-occupancy arm, and in that arm R-7 **fails 231 of
  270** observed rooms — **215** accused of a fault they did not have over **5 215** fault events,
  **16** outside the 5 % airflow band, the two clauses disjoint (`T-34b-room-isolation.csv`). R-7
  passes on the drawn weekday and fails at design occupancy; both arms have to be stated together.
  This strengthens rather than weakens the decision: the regime this record declined to loosen the
  bound for is the regime where R-7 is now visibly failing, and loosening it there would have been
  trading a measured failure for a worse one.
- **R-3's rate is 0.55 per fault-free week, not 1.30.** ADR-0010's lower-arm fix landed after this
  record. The verdict is unchanged — R-3 still FAILS, on its worst week of 6, which is what its
  criterion names — but the rate is now 45 % below the limit rather than 30 % above it.
- **The design-occupancy comfort fraction is 0.022, not 0.024**, and the supervisory loop is worth
  **+0.012** (`T-26-comfort.csv`). The verdict is unchanged: 0 of 5 seeds.

- **§3's five-seed bound table was measured on the pre-fix plant too, and the current run makes its
  own argument stronger.** T-26's bound table has since grown two columns and moved on every row;
  the figures above are left as they were taken. The change that matters is seed 1. This record has
  it at six zones frozen, no healthy rooms left and therefore nothing taken from one — a row that
  reads as a plant limit. The committed run (`T-26-comfort.csv`) has it at **five** zones frozen with
  **one** healthy room surviving, and the per-room trim takes **−69 %** of that room's air, leaving it
  at **1784 ppm** while the faulted room still only reaches 2028 ppm and R-2 stays unreachable.

  So the starvation mechanism §3 identified on seed 5 alone is now measured on **two of five seeds**,
  and on seed 1 it starves a healthy room past R-2's own limit for no gain at all. The conclusion
  this record drew — "reaching the faulted room by starving a healthy one is not reaching it" — holds
  on more evidence than it was drawn from, and the decision to keep the bound at 5 % is better
  supported than when it was taken. The quoted range "31 to 39 times R-7's bound" is likewise a
  pre-fix figure; §3's claim does not rest on it.

The over-supply sweep table in §1 needs one rebuild per row and cannot be regenerated by the
harness's `all` run, so it is left at the values it was measured at, under this note.

