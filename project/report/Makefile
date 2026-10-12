# Build the final report (T-30). Modelled on lab-assignment/final_report_template/Makefile,
# with latexmk driving the reruns the table of contents and the cross-references need.
#
# Usage:
#   make            # build report.pdf
#   make figures    # re-convert the figures to PDF (needs rsvg-convert)
#   make diagrams   # re-render ../diagrams/*.svg from their D2 sources (needs d2)
#   make clean      # remove LaTeX intermediates
#   make distclean  # also remove the PDF

DOC      := report
LATEXMK  := latexmk -pdf -interaction=nonstopmode -halt-on-error
PDFLATEX := pdflatex -interaction=nonstopmode -halt-on-error

# Every figure lives in ../diagrams as a D2 source with its rendered SVG beside it; pdflatex
# cannot read SVG, so each is converted to PDF once, into figures/ here. The deployment view
# used to be the exception, with its source under report/figures/ and rendered with a different
# layout engine; it is now one of the set like the rest.
# The seam set is drawn from the same sources and must be listed here too: a figure this file
# does not mention is a figure `make figures` cannot produce on a fresh clone, because
# report/figures/ is ignored and every PDF in it is a build artefact. It is also the stale-content
# hazard the SECTIONS and SHOTS comments below describe, a third time.
FIGS_D2 := context container component dynamic sequence state deployment \
           code-diagnosis code-compensation \
           seams-overview seams-legend seams-topology seams-loops seams-sensor \
           seams-monitor seams-supervisor seams-actuator seams-plant seams-transport \
           seams-storage seams-clock
FIGS    := $(addprefix figures/,$(addsuffix .pdf,$(FIGS_D2)))

# elk, not dagre: it is the engine that reproduces every committed SVG in ../diagrams bit for
# bit, and the two give very different aspect ratios on these graphs.
D2 := d2 --layout=elk

# report.tex is a thin master: the preamble, the front matter and one \input per section.
# The bodies live in sections/. They must be prerequisites of the PDF, or editing a section
# leaves the PDF untouched and make reports it up to date while serving stale content.
# facts.tex is the same hazard in one file: it holds every number the sections quote more than
# once, so editing it changes the text of several pages and must rebuild the PDF.
SECTIONS := facts.tex $(wildcard sections/*.tex)

# The captured UI frames are the same hazard as the sections were. They are \includegraphics
# targets rather than generated files, so nothing else in this Makefile mentions them, and a
# re-captured screenshot would otherwise leave the PDF showing the old frame while make reports
# it up to date.
SHOTS := $(wildcard screenshots/*.png) $(wildcard screenshots/*.jpg)

# The assignment requires the uploaded file to be named TeamName_FinalReport.pdf, verbatim from
# the course's assignments.json. It is a copy rather than a rename because report.pdf is the
# tracked artefact every cross-reference and the RUNBOOK name, and it is not tracked itself: it
# would be a second five-megabyte copy of a file already in the repository.
SUBMISSION := Group5_FinalReport.pdf

.PHONY: all figures diagrams clean distclean submission
all: $(DOC).pdf
	@echo "==> $(DOC).pdf ready"

$(DOC).pdf: $(DOC).tex $(SECTIONS) $(FIGS) $(SHOTS)
	$(LATEXMK) $(DOC).tex

figures: $(FIGS)

# Build the report and lay down the upload under the name the assignment asks for.
submission: $(DOC).pdf
	cp $(DOC).pdf $(SUBMISSION)
	@echo "==> $(SUBMISSION) ready to upload ($$(du -h $(SUBMISSION) | cut -f1))"

figures/%.pdf: ../diagrams/%.svg
	@mkdir -p figures
	rsvg-convert -f pdf -o $@ $<

# The SVGs are committed, so this target is only needed after editing a .d2.
diagrams:
	@for f in $(FIGS_D2); do $(D2) ../diagrams/$$f.d2 ../diagrams/$$f.svg; done

# Fallback for a machine without latexmk: two pdflatex passes, as the template does.
.PHONY: plain
plain: $(FIGS)
	$(PDFLATEX) $(DOC).tex
	$(PDFLATEX) $(DOC).tex

clean:
	latexmk -c $(DOC).tex 2>/dev/null || true
	rm -f $(DOC).aux $(DOC).log $(DOC).out $(DOC).toc $(DOC).fls $(DOC).fdb_latexmk

distclean: clean
	rm -f $(DOC).pdf $(SUBMISSION)
