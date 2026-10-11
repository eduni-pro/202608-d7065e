package buildingsim_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sort"
	"strings"
	"testing"

	buildingsim "github.com/eislab-cps/buildingsim"
	buildserver "github.com/eislab-cps/buildingsim/pkg/server"
)

// ginPathToOpenAPI rewrites gin's ":param" wildcards into OpenAPI's "{param}".
func ginPathToOpenAPI(path string) string {
	segments := strings.Split(path, "/")
	for i, segment := range segments {
		if strings.HasPrefix(segment, ":") {
			segments[i] = "{" + segment[1:] + "}"
		}
	}
	return strings.Join(segments, "/")
}

// routerOperations lists every documentable operation the server registers.
// Static file serving is excluded: it is not part of the API contract.
func routerOperations(t *testing.T) map[string]bool {
	t.Helper()
	application := buildserver.New(0, buildingsim.DataFS, buildingsim.WebFS, true)
	application.OpenAPISpec = buildingsim.OpenAPISpec
	router, err := application.Router()
	if err != nil {
		t.Fatal(err)
	}
	operations := make(map[string]bool)
	for _, route := range router.Routes() {
		if strings.Contains(route.Path, "*") || route.Method == http.MethodHead {
			continue
		}
		operations[route.Method+" "+ginPathToOpenAPI(route.Path)] = true
	}
	return operations
}

func specOperations(t *testing.T) map[string]bool {
	t.Helper()
	var document struct {
		OpenAPI string                               `json:"openapi"`
		Paths   map[string]map[string]map[string]any `json:"paths"`
	}
	if err := json.Unmarshal(buildingsim.OpenAPISpec, &document); err != nil {
		t.Fatalf("decode embedded OpenAPI spec: %v", err)
	}
	if !strings.HasPrefix(document.OpenAPI, "3.") {
		t.Fatalf("openapi version = %q, want 3.x", document.OpenAPI)
	}
	operations := make(map[string]bool)
	for path, item := range document.Paths {
		for method := range item {
			operations[strings.ToUpper(method)+" "+path] = true
		}
	}
	return operations
}

func sorted(operations map[string]bool) []string {
	list := make([]string, 0, len(operations))
	for operation := range operations {
		list = append(list, operation)
	}
	sort.Strings(list)
	return list
}

// TestOpenAPISpecMatchesRegisteredRoutes is the reason the generated spec can
// be committed rather than rebuilt: adding a route without annotating it, or
// annotating a route that no longer exists, fails here without needing swag.
func TestOpenAPISpecMatchesRegisteredRoutes(t *testing.T) {
	registered := routerOperations(t)
	documented := specOperations(t)

	var undocumented, stale []string
	for _, operation := range sorted(registered) {
		if !documented[operation] {
			undocumented = append(undocumented, operation)
		}
	}
	for _, operation := range sorted(documented) {
		if !registered[operation] {
			stale = append(stale, operation)
		}
	}
	if len(undocumented) > 0 {
		t.Errorf("routes missing from the OpenAPI spec (annotate the handler, then run `make openapi`):\n  %s",
			strings.Join(undocumented, "\n  "))
	}
	if len(stale) > 0 {
		t.Errorf("OpenAPI spec documents routes the server does not register:\n  %s",
			strings.Join(stale, "\n  "))
	}
}

func TestOpenAPISpecIsServed(t *testing.T) {
	application := buildserver.New(0, buildingsim.DataFS, buildingsim.WebFS, false)
	application.OpenAPISpec = buildingsim.OpenAPISpec
	router, err := application.Router()
	if err != nil {
		t.Fatal(err)
	}
	httpServer := httptest.NewServer(router)
	defer httpServer.Close()

	response, err := httpServer.Client().Get(httpServer.URL + "/api/openapi.json")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("GET /api/openapi.json = %d, want 200", response.StatusCode)
	}
	if contentType := response.Header.Get("Content-Type"); !strings.HasPrefix(contentType, "application/json") {
		t.Errorf("Content-Type = %q, want application/json", contentType)
	}
	var document map[string]any
	if err := json.NewDecoder(response.Body).Decode(&document); err != nil {
		t.Fatalf("served spec is not valid JSON: %v", err)
	}
	if _, ok := document["paths"]; !ok {
		t.Error("served spec has no paths")
	}
}

// TestOpenAPISpecOmittedReportsUnavailable keeps the endpoint from answering
// 200 with an empty body when an embedder forgets to supply the spec.
func TestOpenAPISpecOmittedReportsUnavailable(t *testing.T) {
	application := buildserver.New(0, buildingsim.DataFS, buildingsim.WebFS, false)
	router, err := application.Router()
	if err != nil {
		t.Fatal(err)
	}
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/api/openapi.json", nil))
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("GET /api/openapi.json without a spec = %d, want 503", recorder.Code)
	}
}
