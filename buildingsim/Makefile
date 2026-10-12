GO ?= go
VERSION ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo dev)
BUILDTIME ?= $(shell date -u '+%Y-%m-%dT%H:%M:%SZ')
LDFLAGS := -s -w -X main.version=$(VERSION) -X main.buildTime=$(BUILDTIME)
STRESS_DURATION ?= 30s
# swag v2 emits OpenAPI 3.1 and is still a release candidate, so the version is
# pinned: local and CI regeneration must agree. Not a module dependency -- the
# generated spec is committed, so building BuildSim never needs swag.
SWAG ?= go run github.com/swaggo/swag/v2/cmd/swag@v2.0.0-rc5

.PHONY: all web build test docs-test race stress stress-ui run run-classic run-edit docker-build release checksums openapi openapi-check clean

all: build

web:
	$(GO) generate ./...

build: web
	mkdir -p ./bin
	CGO_ENABLED=0 $(GO) build -trimpath -ldflags="$(LDFLAGS)" -o ./bin/buildsim ./cmd

test: web
	$(GO) test ./... -count=1
	cd ../tutorials/buildsim && $(GO) test ./... -count=1

docs-test: web
	$(GO) test . -run 'Test(Documented|Documentation|Tutorial)' -count=1
	cd ../tutorials/buildsim && $(GO) test ./... -count=1

race: web
	$(GO) test -race ./... -count=1
	cd ../tutorials/buildsim && $(GO) test -race ./... -count=1

stress: web
	BUILDSIM_STRESS=1 BUILDSIM_STRESS_DURATION=$(STRESS_DURATION) \
		$(GO) test -race ./pkg/server -run 'TestStress(Bounded|Soak)API' -count=1

stress-ui: web
	BUILDSIM_UI_STRESS=1 BUILDSIM_CHROMIUM="$(BUILDSIM_CHROMIUM)" \
		$(GO) test ./pkg/server -run TestUIEquipmentNotificationStorm -count=1 -v

run: build
	./bin/buildsim start --host 127.0.0.1 --port 9090 --ui modern

run-classic: build
	./bin/buildsim start --host 127.0.0.1 --port 9090 --ui classic

run-edit: build
	./bin/buildsim start --host 127.0.0.1 --port 9090 --ui modern --edit

openapi:
	$(SWAG) init --generalInfo cmd/main.go --dir ./ --output ./docs/api \
		--outputTypes json,yaml --parseInternal --parseDepth 3 --v3.1
	mv ./docs/api/swagger.json ./docs/api/openapi.json
	mv ./docs/api/swagger.yaml ./docs/api/openapi.yaml

openapi-check: openapi
	git diff --exit-code -- ./docs/api/openapi.json ./docs/api/openapi.yaml

docker-build:
	docker build -t buildingsim:local .

release: web
	mkdir -p ./dist
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 $(GO) build -trimpath -ldflags="$(LDFLAGS)" -o ./dist/buildsim-linux-amd64 ./cmd
	CGO_ENABLED=0 GOOS=linux GOARCH=arm64 $(GO) build -trimpath -ldflags="$(LDFLAGS)" -o ./dist/buildsim-linux-arm64 ./cmd
	CGO_ENABLED=0 GOOS=darwin GOARCH=amd64 $(GO) build -trimpath -ldflags="$(LDFLAGS)" -o ./dist/buildsim-darwin-amd64 ./cmd
	CGO_ENABLED=0 GOOS=darwin GOARCH=arm64 $(GO) build -trimpath -ldflags="$(LDFLAGS)" -o ./dist/buildsim-darwin-arm64 ./cmd
	CGO_ENABLED=0 GOOS=windows GOARCH=amd64 $(GO) build -trimpath -ldflags="$(LDFLAGS)" -o ./dist/buildsim-windows-amd64.exe ./cmd

checksums: release
	cd ./dist && sha256sum buildsim-* > SHA256SUMS

clean:
	rm -rf ./bin ./dist
