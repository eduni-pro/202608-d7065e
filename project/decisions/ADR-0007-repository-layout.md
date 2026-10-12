# ADR-0007 — The project lives in the course-material repository

**Status:** Accepted · 2026-09-01

## Context

The project must be submitted as a report plus a repository, and the evaluation must be
reproducible by someone who has only that repository. The system runs against BuildSim,
which the course supplies.

BuildSim is **not independently obtainable**. It exists only as a directory inside
`eislab-cps/D7065E`; `github.com/eislab-cps/buildingsim` is a Go module path, not a
repository. Its `compose.yaml` builds from source (`build: context: .`), and no image is
published to any registry. Any separate project repository would therefore have to
vendor BuildSim, submodule the entire course repository, or instruct the reader to clone
a second repository and build an image before anything runs.

## Decision

The project lives in `project/` inside this copy of the course-material repository. The
root README leads with the project; the course material is listed beneath it as what the
project was built against.

## Because

- One clone and one `docker compose up` reproduce the whole system, including the exact
  BuildSim commit the evaluation was run against. Reproducibility of the evaluation is a
  graded property, and the container diagram must match a compose file that actually runs.
- The alternative arrangements each reintroduce a second clone or a copy of BuildSim's
  source into a repository that is supposed to represent this project's own work.
- BuildSim's own commit history stays intact in the same tree, so a report claim about
  BuildSim behaviour can cite a path and a commit that the reader already has.

## Alternatives rejected

- **A separate project repository with BuildSim as a git submodule** — cleanest
  submission. Rejected because the submodule would have to be the entire course
  repository, PDFs and all, since BuildSim is not a repository of its own.
- **A separate project repository referencing a prebuilt `buildsim` image** — rejected:
  no image is published, so building it requires the course repository anyway, which is
  the friction this decision removes.
- **A separate project repository vendoring BuildSim's source** — reproducible, but it
  copies several megabytes of the examiner's code into a repository meant to show this
  project's work, and forks it silently.

## Trade-off accepted

`git log` interleaves project commits with course-material commits, and the submitted
repository contains material this project did not write. Mitigated by confining the
project to `project/`, by a root README that leads with it, and by `git log -- project/`
for an authorship view. Neither effect is graded; the reproducibility gained is.

## Defence in one sentence

BuildSim is not a repository and publishes no image, so keeping the project in the same
tree is what makes the evaluation reproducible from a single clone, at the cost of a
mixed commit history.
