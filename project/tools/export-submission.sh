#!/usr/bin/env bash
#
# Export the submission into a fresh single-commit repository.
#
# The course wants "source code in a git repository" and a link to it in the report. This script
# produces that repository: the course material laid out as the course publishes it, plus project/,
# which is the work.
#
# WHAT IS INCLUDED, AND WHY IT CHANGED
#
# An earlier revision excluded the course's own lab-assignment/, tutorials/, course-notes/ and
# occupancysim/, on the reasoning that they are the instructor's rather than this project's. That
# was wrong twice over, and the export now mirrors github.com/eislab-cps/D7065E — which is PUBLIC
# and carries exactly those directories — with project/ and project.md added.
#
#   * Nothing is redistributed that the instructor has not already published at that URL.
#   * More importantly, the project CITES them as evidence. ADR-0002 justifies the runtime against
#     lab-assignment/final_report_guide/report_guide.tex:384 and :259, sizes the breaking-point
#     experiment against final_report_example.tex:900, and ADR-0008 cites tutorials/buildsim.md.
#     Ten files under project/ reference those paths. Excluding them left an examiner holding
#     decision records whose stated evidence was not in the repository they were reading.
#
# .claude/ stays out. It is the owner's local tooling, it is not course material, and the course
# repository has no equivalent — so it is the one directory whose absence needs no explanation.
#
# project/aiadocs/ stays out as well, and that one does need explaining, because eleven exported
# files point into it: the root README.md and project.md link the implementation plan,
# project/README.md, RUNBOOK.md, REQUIREMENTS.md, docs/arc42.md and ADR-0008 cite it in prose, and
# two javadoc comments under project/evaluation/ name a results file. Those pointers go dangling in
# the export. That is accepted rather than overlooked: the directory holds working notes, and every
# measurement an examiner needs is in the report itself — Appendix F for the working behind each
# verdict, Appendix G for the register of what each number actually is.
#
#   usage: tools/export-submission.sh [--dry-run] [--force] [remote-url]
#
#     --dry-run   build and verify the tree, print the summary, push nothing
#     --force     overwrite a remote that already has commits (safe here, see PUSHING below)
#     remote-url  defaults to the repository the report's section 1 points at
#
# WHY git archive AND NOT cp -r
#
# `git archive HEAD` emits exactly the tracked files at a commit. That is not a convenience: it is
# what keeps project/storage/ out of the export. That directory is a live bind mount holding a
# PostgreSQL cluster and the broker's spool — on the order of 1700 files and 98 MB — and it is
# ignored rather than absent, so any copy that walks the filesystem picks it up. Exporting from the
# index instead makes the ignore rules do the work, and makes the export correspond to a commit
# rather than to whatever happened to be on disk.
#
# PUSHING, AND WHY --force IS NOT DANGEROUS HERE
#
# The export repository holds no unique history: every byte of it is derived from this one, and
# re-running this script reproduces it. Force-pushing it therefore destroys nothing that exists
# anywhere else. That is true of the EXPORT repository only. Never point this at the working
# repository.

set -euo pipefail

DEFAULT_REMOTE="git@github.com:eduni-pro/202608-d7065e.git"
# The course repository's own top level, plus the two this project adds. Kept in that order so a
# reader comparing the two sees the same shape.
PATHS=(.github .gitignore README.md buildingsim course-notes lab-assignment occupancysim tutorials
       project project.md)
# Carved back out of project/. git archive honours exclude pathspecs, so this happens in the index:
# nothing is written to the tree and then deleted from it, and the export still corresponds to a
# commit read through one pathspec rather than to a commit plus a cleanup step.
EXCLUDES=(':(exclude)project/aiadocs')

DRY_RUN=0
FORCE=0
REMOTE=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --force)   FORCE=1 ;;
    -*)        echo "unknown option: $arg" >&2; exit 2 ;;
    *)         REMOTE="$arg" ;;
  esac
done
REMOTE="${REMOTE:-$DEFAULT_REMOTE}"

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

say() { printf '\n== %s\n' "$1"; }
die() { printf '\nFAILED: %s\n' "$1" >&2; exit 1; }

# ---------------------------------------------------------------------------------------------
# 1. The export must correspond to a commit, or it cannot be reproduced or reasoned about.

say "checking the working tree"
if [ -n "$(git status --porcelain)" ]; then
  git status --short
  die "the working tree is dirty. Commit or stash first: an export taken from a dirty tree
       matches no commit, so nobody can later say what was submitted."
fi
HEAD_SHA="$(git rev-parse --short HEAD)"
echo "   exporting from $HEAD_SHA on $(git rev-parse --abbrev-ref HEAD)"

# ---------------------------------------------------------------------------------------------
# 2. The report is a tracked build artefact, which is a trap worth checking for.
#
# report.pdf is in git. A checkout therefore gives it an mtime newer than its own sources, and the
# next `make` reports success without rebuilding — so a stale PDF can look current. The only honest
# check is against the content: the PDF that goes out must carry the link the sources claim.

say "checking the committed report against its sources"
PDF="project/report/report.pdf"
[ -f "$PDF" ] || die "$PDF is missing. Run: make -C project/report distclean submission"

SRC_URL="$(grep -o 'https://github.com/[A-Za-z0-9._/-]*' project/report/sections/01-summary.tex | head -1)"
PDF_URL="$(pdftotext "$PDF" - 2>/dev/null | grep -o 'https://github.com/[A-Za-z0-9._/-]*' | head -1 || true)"
PAGES="$(pdfinfo "$PDF" 2>/dev/null | awk '/^Pages/{print $2}')"

echo "   sources say: ${SRC_URL:-<none>}"
echo "   the PDF says: ${PDF_URL:-<none>}"
echo "   pages:        ${PAGES:-?}"

if [ -z "$PDF_URL" ] || [ "$SRC_URL" != "$PDF_URL" ]; then
  die "the committed PDF does not carry the repository link its sources state.
       It is stale. Rebuild and commit it: make -C project/report distclean submission"
fi

# ---------------------------------------------------------------------------------------------
# 3. Build the tree.

OUT="$(mktemp -d "${TMPDIR:-/tmp}/d7065e-submission.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT

say "building the export tree"
git archive HEAD "${PATHS[@]}" "${EXCLUDES[@]}" | tar -x -C "$OUT"
echo "   from:     ${PATHS[*]}"
echo "   excluded: ${EXCLUDES[*]}"

# ---------------------------------------------------------------------------------------------
# 4. Verify the invariants. Each of these has been got wrong at least once.

say "verifying the tree"
fail=0
check() { # check <description> <expected-count> <actual-count>
  if [ "$2" = "$3" ]; then printf '   ok    %-46s %s\n' "$1" "$3"
  else printf '   FAIL  %-46s %s (expected %s)\n' "$1" "$3" "$2"; fail=1; fi
}

check "build output (target/, .jar, .class)" 0 \
  "$(find "$OUT" \( -name target -o -name '*.jar' -o -name '*.class' \) | wc -l | tr -d ' ')"
check "live database / broker spool under storage" 0 \
  "$(find "$OUT/project/storage" -type f ! -name '.gitkeep' 2>/dev/null | wc -l | tr -d ' ')"
check "figure PDFs (they rebuild from committed SVGs)" 0 \
  "$(find "$OUT/project/report/figures" -name '*.pdf' 2>/dev/null | wc -l | tr -d ' ')"
check "the course material the ADRs cite as evidence" 4 \
  "$(find "$OUT" -maxdepth 1 \( -name lab-assignment -o -name tutorials -o -name course-notes -o -name occupancysim \) | wc -l | tr -d ' ')"
check "the report guide ADR-0002 cites by line" 1 \
  "$(find "$OUT/lab-assignment" -name 'report_guide.tex' 2>/dev/null | wc -l | tr -d ' ')"
check "the BuildSim tutorial ADR-0008 cites" 1 \
  "$(find "$OUT/tutorials" -name 'buildsim.md' 2>/dev/null | wc -l | tr -d ' ')"
check "the owner's .claude tooling" 0 \
  "$(find "$OUT" -maxdepth 1 -name '.claude' | wc -l | tr -d ' ')"
check "the working notes under project/aiadocs" 0 \
  "$(find "$OUT/project" -maxdepth 1 -name 'aiadocs' | wc -l | tr -d ' ')"
check "the report itself" 1 \
  "$(find "$OUT/project/report" -maxdepth 1 -name 'report.pdf' | wc -l | tr -d ' ')"
check "the root .gitignore (keeps storage/ ignored)" 1 \
  "$(find "$OUT" -maxdepth 1 -name '.gitignore' | wc -l | tr -d ' ')"
check "buildingsim (docker-compose builds from it)" 1 \
  "$(find "$OUT" -maxdepth 1 -name 'buildingsim' | wc -l | tr -d ' ')"

[ "$fail" -eq 0 ] || die "the export tree is wrong. Nothing was pushed."

# ---------------------------------------------------------------------------------------------
# 5. Commit.

say "committing"
git -C "$OUT" init -q -b main
git -C "$OUT" add -A
git -C "$OUT" -c commit.gpgsign=false commit -q -m "Ventilation fault detection, diagnosis and graceful degradation

Demand-controlled ventilation over six rooms of an office floor, with a supervisory loop on top
that decides whether the control loop is still working. Exported from $HEAD_SHA."

FILES="$(git -C "$OUT" ls-files | wc -l | tr -d ' ')"
SIZE="$(du -sh "$OUT" | cut -f1 | tr -d ' ')"
echo "   $FILES files, $SIZE"

# ---------------------------------------------------------------------------------------------
# 6. Push.

if [ "$DRY_RUN" -eq 1 ]; then
  say "dry run: nothing pushed"
  echo "   tree was built and verified at $OUT (removed on exit)"
  exit 0
fi

say "pushing to $REMOTE"
REMOTE_HEADS="$(git ls-remote --heads "$REMOTE" 2>/dev/null | wc -l | tr -d ' ')"
if [ "$REMOTE_HEADS" -gt 0 ] && [ "$FORCE" -eq 0 ]; then
  die "that remote already has branches. Re-run with --force to replace it.
       Safe for the export repository, which holds no history of its own; never for the working one."
fi

git -C "$OUT" remote add origin "$REMOTE"
if [ "$FORCE" -eq 1 ]; then
  git -C "$OUT" push -q --force -u origin main
else
  git -C "$OUT" push -q -u origin main
fi

say "done"
echo "   pushed $FILES files from $HEAD_SHA"
echo "   $REMOTE"
echo
echo "   The report links to this repository. If it is private, that link resolves for nobody"
echo "   but its owner — check its visibility before relying on it."
