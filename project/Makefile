# Build and run the supervisory ventilation system.
#
# Images are built from host-built jars, so `build` must precede `images`. The folder set maps
# 1:1 onto the seven project-authored compose services; shared/ is a library and evaluation/ the
# offline harness, so neither is a container. Compose runs three more services that have no
# folder here at all, because they are off the shelf: buildsim, mosquitto and timescaledb. Ten
# services, seven of them built from this reactor.

MAVEN ?= mvn
COMPOSE ?= docker compose

# The two host ports compose publishes are overridable there, so `up` prints the URLs from the
# same variables rather than from hardcoded numbers that would lie whenever either is set.
BUILDSIM_PORT ?= 9090
DASHBOARD_PORT ?= 8080

.PHONY: all build test images up down logs ps clean experiments journal-test verify

all: build images

build:
	$(MAVEN) -q package

test:
	$(MAVEN) test

images: build
	$(COMPOSE) build

up: images
	$(COMPOSE) up -d
	@echo "BuildSim 3D view: http://127.0.0.1:$(BUILDSIM_PORT)/?layer=residual&floor=level0"
	@echo "Dashboard:        http://127.0.0.1:$(DASHBOARD_PORT)"

down:
	$(COMPOSE) down -v

logs:
	$(COMPOSE) logs -f --tail=50

ps:
	$(COMPOSE) ps

# R-5's SQL against a real PostgreSQL, which is the only place it can run. Starts the journal
# service, runs the test and removes the container again; with the stack already up, plain
# `make test` runs it too, because the test uses a journal it finds and only starts one it does not.
# Not folded into `test`, so that target keeps needing nothing but a JVM.
journal-test:
	$(MAVEN) -o test -pl shared -Dtest=TimescaleJournalIntegrationTest \
		-Dsurefire.failIfNoSpecifiedTests=false -Dr5.journal.autostart=true

# The R-1..R-7 evidence. Runs the same control and diagnosis classes the containers run,
# against a simulated clock, so a seven-day replay and twenty injection runs are minutes.
experiments:
	$(MAVEN) -q -pl evaluation -am package
	java -jar evaluation/target/evaluation-1.0.0-SNAPSHOT.jar all

verify: test experiments

clean:
	$(MAVEN) -q clean
	$(COMPOSE) down -v --remove-orphans 2>/dev/null || true
