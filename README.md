# D7065E — Embedded Intelligence at the Edge

Master's course at Luleå University of Technology, 7.5 ECTS.

[Course syllabus](https://www.ltu.se/en/education/syllabuses/course-syllabus?id=D7065E)

## The project

**[`project.md`](project.md) — Ventilation fault detection, diagnosis and graceful degradation.**

Demand-controlled ventilation over six rooms of the A-building, with a supervisory loop on
top that decides whether the control loop is still *working*: a model of what each damper
command should have achieved separates a stuck damper from a full room, names the fault,
and keeps the air breathable until it is repaired. Every sensor, actuator and service is
its own process around BuildSim, which holds the building state.

- [Summary](project.md) — idea, scenario, containers, requirements, risks
- [Proposal](project/proposal/) — the accepted week-2 proposal
- [Report](project/report/report.pdf) — the final architecture document: the requirements and
  where they landed, the evaluation, and the risk register
- [Requirements](project/REQUIREMENTS.md) — R-1…R-7 in EARS form, with their verification
- [Decision log](project/decisions/) — every significant choice, its rejected
  alternatives, and the trade-off accepted

The project lives in this repository rather than in one of its own so that it is pinned
to the exact BuildSim it was built and evaluated against: one clone reproduces the whole
system, with `make up` in `project/`. The images copy host-built jars, so the jars have to
exist first — `make up` builds them and then brings the stack up, which is why it is the
entry point rather than `docker compose up` on its own. It needs a JDK 25, Maven and Docker;
[`project/README.md`](project/README.md) lists every target.

## Course material

This repository is a copy of the course's technical material; administrative material,
grading, use cases and resources live on Canvas.

- [Course Notes](course-notes/) — four chapters of reading material
- [Tutorials](tutorials/) — hands-on guides (diagrams as code, containers and tooling)
- [Lab Assignment](lab-assignment/) — proposal and final-report templates, with worked examples
- [BuildingSim](buildingsim/) — the building simulation server the project is built against
