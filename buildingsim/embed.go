package buildingsim

import "embed"

//go:generate go run ./cmd/buildweb

//go:embed all:data
var DataFS embed.FS

//go:embed all:web
var WebFS embed.FS

// OpenAPISpec is the generated OpenAPI 3.1 description of the REST API. It is
// committed rather than built, so `make build` needs no swag installation;
// regenerate it with `make openapi` after changing a handler annotation.
//
//go:embed docs/api/openapi.json
var OpenAPISpec []byte
