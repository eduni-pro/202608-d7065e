# ADR-0010 — The plausibility guards are bounds, not filters of the measurement

**Status:** Accepted · 2026-10-05

> **Amended 2026-10-06** — the decision stands; two cells of the Measured table have since moved,
> R-3's shipped rate and R-7's row. See the amendment at the end of this record.

## Context

R-3 asks for at most one false alarm per simulated week. Measured over 20 fault-free six-room
weeks of the deployed stack, the monitor raised **1.30 confirmed cases per week with a worst week
of 6 and only 9 weeks clean**, and the failure was unattributed: an earlier pass had blamed a
cross-room cascade through the response to a diagnosis, and T-34b disproved that by observing 270
un-injected room-runs with 0 fault events.

This record is the attribution and what followed from it.

### What the 26 false confirmations actually were

Instrumenting the same replay and classifying every confirmation by room, hour, CUSUM arm and
whether any room had already been pinned:

| | count | per week |
|---|---|---|
| total | 26 | 1.30 |
| raised with no room yet pinned (*primary*) | 13 | 0.65 |
| raised while another room was already pinned (*cascaded*) | 13 | 0.65 |
| on the upper arm | 17 | 0.85 |
| on the lower arm | 9 | 0.45 |

Every one of the six rooms confirmed at least once — A125A 7, A115 7, A125 4, A145 4, A116A 3,
A124 1 — and the hours were spread across the whole working day (peaks at 11:00, 13:00 and 15:00),
with three overnight. Of the 17 upper-arm confirmations, 11 were cascaded; of the 9 lower-arm ones,
7 were primary. Twenty weeks produced 919 lower-arm SUSPECT episodes against 67 upper-arm ones.

### The mechanism, which is one defect with two faces

`ZoneObserver.correctTowards` pulled **all three** trajectories 30 % of the way onto every reading
between commands — the believed prediction, the design-occupancy *ceiling* and the empty-room
*floor*. A bound corrected like that is not a bound: after a few cycles it is a first-order filter
of the measurement sitting a fixed offset away from it, and `measured − ceiling` is the error of
that filter rather than a statement about occupancy. Both CUSUM arms were therefore driven by a
near-zero-mean statistic, which is exactly the i.i.d. regime Siegmund's ARL₀ applies to, and over
140 room-weeks it duly fired.

Read off the trace of seed 20, room A115 — ten seats, a design-occupancy trajectory whose
asymptote is near 1500 ppm:

- 10:20 to 11:18, no command: the ceiling sits **50 to 80 ppm above the reading**, all day.
- 10:59: nine people arrive.
- 11:19: the inner loop opens the damper in response, which arms a judging window and freezes the
  ceiling at 789 ppm.
- 11:19 → 11:28: the reading walks 830 → 893 ppm, `measured − ceiling` averages +44 ppm, the upper
  arm goes 11 → 155 and A115 is confirmed `STUCK_DAMPER` with nothing wrong with it.

The class comment claimed the opposite in as many words — "the lag … does not reach the CUSUM,
because the CUSUM is driven by the plausibility ceiling, which is integrated with a generation term
large enough to follow the fill". It was not: the same method that lagged the published residual
lagged the ceiling with it.

The floor had the mirror defect, dragged **up** to the reading, so it was crossed from above by
every departure and by the first minutes after every restart. The lower-arm hours cluster at 16:00
to 19:00, when offices empty.

And the cascade is the same defect at one remove: a false `STUCK_DAMPER` pins the room's believed
opening in `AhuFlowModel`, the split is a ratio over all six rooms, so the other five are credited
with air they are not getting, their filtered ceilings fall below their readings and they confirm
in turn. Seed 20 latched four rooms in 53 minutes; seed 2 latched all six.

## Decision

Two of the three attributed causes are fixed, and the third is not, because fixing it costs three
other requirements. Both halves of that are decisions.

**Taken.**

1. **The guarded lower arm is driven by a bound that needs no anchor:** the outdoor concentration,
   less `overnightFloorTolerancePpm`. Its step-anchored empty-room trajectory is deleted. Only the
   *clamp* ever carried the soundness argument — ventilating with 420 ppm air cannot take a room
   below 420 ppm. The trajectory above it falls at the *believed* airflow while the room falls at
   the actual one, so whenever the shared split has moved and the belief has not caught up a healthy
   room drops through its own floor; and `correctTowards` pulled it *up* onto every reading, so
   between commands it was a lagging filter of the measurement and every departure crossed it from
   above. The allowance is reused rather than invented, for the reason `FaultClassifier` already
   gives for subtracting it twice on its own low-side test: a healthy room resting at 420 ppm reads
   below 420 ppm on about half of its samples, so a bound placed there has no in-control margin at
   all and a one-sided CUSUM against it is a zero-mean statistic.
2. **A confirmation that pins a damper re-anchors the other five rooms.** The split is a ratio over
   all six openings, so pinning one changes what every room's airflow is believed to be. The
   residual means "measured minus what the believed airflow predicts", so a step change in that
   belief makes every ppm banked before it a statement about a different building. This is the
   argument `resetArms` already makes for a BLIND gap and for service, applied to the third event
   that invalidates a statistic. A latched case is left alone: its diagnosis is made, its only exit
   is service, and the belief change is a consequence of a confirmation rather than new information
   about it.

**Not taken: making the upper arm's ceiling a bound as well.** The same defect is there and is the
larger half of the failure — `correctTowards` pulled the design-occupancy ceiling 30 % of the way
onto every reading too, so `measured − ceiling` was a filter error and an occupancy ramp walked
straight through it. Fixing it works and is measured on the branch `r3-bounds-variant`: R-3 passes
outright at 0.00 false confirmations per week, 20 of 20 weeks clean, and the worst arm over a
healthy six hours falls from 68.6 ppm to 0.0. It is not on the main line because of what it costs,
which is in the trade-off section below.

## Measured

Twenty fault-free six-room weeks of the deployed stack, R-3's own criterion, and every other
requirement line beside it:

| | baseline | shipped | `r3-bounds-variant` |
|---|---|---|---|
| R-3 confirmed cases per fault-free week | 1.30 | **0.60** | **0.00** |
| R-3 weeks clean, of 20 | 9 | **16** | **20** |
| R-3 worst week (the criterion) | 6 | 6 | **0** |
| R-3 verdict | FAIL | FAIL | **pass** |
| R-1 stepped p95, min | 6 | 6 | 7 |
| R-1 closed-loop detected | 15/20 | 15/20 | **1/20** |
| R-4, T-27 assertions | 23/23 | 23/23 | **19/23** |
| R-7 observed rooms failing | 0/270 | 0/270 | **2/270** |
| component week, lower arm GUARDED worst | 1 | **0** | 0 |
| component week, lower arm OFF worst | 0 | 0 | 0 |

So the shipped change halves the rate and nearly doubles the clean weeks while moving no other
requirement at all, and it does not meet R-3, because the criterion is the worst week and seed 2
still produces six.

## Alternatives rejected, each with the measurement that rejected it

- **Raise `h`.** Not attempted as a fix and deliberately so: T-24 prices it at about two minutes of
  delay while `h` triples, so it buys specificity from R-1's budget and the attribution above says
  the reference was wrong, not the limit. A limit retuned on the experiment that failed it is the
  methodological error §13.8 already names.
- **Gate both arms on the judging window explicitly**, which is the same intent as decision 3 but
  implemented as a flag rather than through the physics. It fixes R-3 just as well and it is
  strictly blinder: a 40 % blocked filter that the shipped detector confirms is not confirmed at
  all under the gate, because the arms are shut off the moment the 10 min window closes and the
  blockage signal had reached only 43 ppm of the 150 ppm it needs by then. The clamp leaves the arm
  live wherever a bound genuinely exists and inert only where it does not, which is the difference
  between a physical argument and a switch. Neither recovers R-1's closed-loop arm: 1 of 20 either
  way.
- **Never let a re-anchor lower the ceiling**, on the argument that a bound already held is still
  valid. Valid and useless: with the damper nearly shut the believed airflow is small, the
  trajectory converges somewhere absurd, and a ceiling that may only rise never comes back down to
  where a fault could cross it. **R-1's closed-loop detection fell to 1 of 20** here too.
- **Take the asymptote clamp at the design generation alone**, making it a strict
  design-occupancy bound instead of the looser `max(G_est, G_ceiling)` that ships. This is the
  tighter and more principled-looking option and it was rejected on measurement. It sees more:
  blocked-filter sensitivity improves from 35 % to 25 % of blockage, and a sensor drifting 350 ppm
  high is confirmed with no command issued at all. But what it catches it **mislabels**: the
  drifting sensor is confirmed before any overnight-floor evidence exists, so it is confirmed as a
  damper fault, and `CrossRoomSignatureTest` asserts exactly that it must not be. It also makes the
  blocked filter read as common mode, which moves a second recorded finding. And it buys nothing
  where it is needed: R-1's closed-loop detection is **1 of 20** with it too. Tighter, earlier,
  wrong more often, and no better on the arm that matters.
- **Keep the empty-room floor and give it a wider clamp.** Rejected because the floor's failure mode
  is a belief error whose sign nothing controls, not a margin that can be bought.
- **Fix only the lower arm.** Arithmetically insufficient: removing all 7 lower-arm primaries still
  leaves seed 2 at 6 confirmations, seed 5 at 5 and seed 20 at 4, because those weeks are headed by
  upper-arm primaries and carried by the cascade. Worst week would still be 6.

## Trade-off accepted

**What the shipped change costs.** A sensor drifting low is confirmed after 34 min and 170 ppm of
offset instead of 14 min and 70 ppm, because the clamp carries a 100 ppm noise allowance. The fault
is still caught well before the overnight floor would show it. The `GUARDED` lower arm no longer
sees anything the outdoor clamp does not; it never claimed to, and `TwoSidedDetectionTest` already
measured it missing the damper stuck wide open. Nothing else moves, and R-3 is not met.

**What the branch that does meet R-3 costs, which is why it is a branch.** Making the ceiling a
bound as well takes R-3 to 0.00 false confirmations per week and takes **R-1's closed-loop
detection from 15 of 20 to 1 of 20**, **T-27 from 23 of 23 assertions to 19** — it needs that
detection to set its own supervisor scenario up — and **R-7 from 0 of 270 observed rooms failing to
2**. Three requirements for one.

That loss is structural rather than a threshold, and the arithmetic says so:

- The closed-loop detections are genuine, not guesses. Run as a pair, the same 20 seeds with
  nothing injected latched A124 **0 of 20** times, before and after. T-24 now carries that control
  column so the claim is reproducible.
- In that protocol A124 draws 4 to 5 of its 8 desks. The frozen damper moves its believed opening
  from about 0.26 to 0.15, worth about **86 ppm** of steady state at that occupancy. The
  plausibility ceiling it would have to cross is the trajectory of a room at 1.25 × 8 people, which
  at the same airflow sits **270 to 600 ppm** above the reading.
- Sweeping the ceiling's headroom down to 1.00 — the literal design-occupancy trajectory, the
  tightest value that still bounds occupancy — moves the detection rate not at all: **1 of 20 at
  both 1.00 and 1.25**, measured in T-24.

So an occupancy-bounded level test cannot see this fault at any headroom that still bounds
occupancy, and the detector that can see it sees it by asking "is this room rising?" — which is the
same question an arriving occupant answers yes to, and the same mechanism that produced the 26
false confirmations. **Both numbers come from one knob: what the CUSUM's reference is**, and there
is no setting of that knob which meets R-3 and keeps R-1's closed-loop arm.

Breaking that tie needs a second, occupancy-free detection path rather than a better reference. The
step-amplitude ratio the observer already computes and the classifier already reads as a *signal*
is the obvious candidate: a damper that did not move delivers none of its predicted response
whatever the occupancy. That is design work, not a parameter, and it is not in this record.

## Defence in one sentence

Both plausibility guards were being corrected 30 % of the way onto every reading, which turned each
of them from a bound into a lagged filter of the measurement and each CUSUM arm into a zero-mean
drift detector that fired on every occupancy ramp — the lower arm's half of that is fixed, together
with the belief step that let one false confirmation drag four more rooms after it, halving R-3's
rate with nothing else moved; the upper arm's half is measured, meets R-3 outright, and is left on a
branch because an occupancy-bounded ceiling cannot see an 86 ppm fault through 270 ppm of occupancy
headroom and three other requirements depend on its seeing it.

## Amended 2026-10-06: two cells of the Measured table have since moved

The decision stands and the table above is left as it was measured on 2026-10-05, because the
`shipped` column is what this record chose. Two of its cells have since been overtaken, and neither
changes a verdict:

- **R-3's shipped rate is 0.55 confirmed cases per fault-free week, not 0.60**
  (`T-25-baseline-comparison.csv`: 11 cases over 20 fault-free weeks). The two rows beneath it are
  unchanged and are the ones that carry the verdict — **16 of 20** weeks clean and a **worst week of
  6** — so R-3 still **FAILS**, and it fails on the worst week, which is what its criterion names.
  The rate itself sits 45 % below the limit, so a sentence arguing R-3 fails *on the rate* is wrong
  in both the baseline and the shipped column.
- **The `R-7 observed rooms failing` row is the drawn weekday only.** `IsolationExperiment` ran that
  regime alone when this was measured. Its design-occupancy arm, added later, fails **231 of 270**
  observed rooms in the shipped configuration. The comparison this table was built for still holds —
  the guards-as-bounds change moved no R-7 row relative to the baseline — but `0/270` must not be
  quoted as R-7's verdict.

