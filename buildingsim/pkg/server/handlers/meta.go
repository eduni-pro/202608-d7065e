package handlers

import (
	"io/fs"
	"net/http"
	"path"
	"strings"

	"github.com/eislab-cps/buildingsim/pkg/model"
	"github.com/gin-gonic/gin"
)

// MetaHandlers answers questions about the running process rather than about
// the simulated building.
type MetaHandlers struct {
	Config      model.ConfigResponse
	DataFS      fs.FS
	OpenAPISpec []byte
}

// @Summary		Liveness probe
// @Description	Reports that the process is up. Used by the container health check and by `buildsim health`.
// @Tags			health
// @Produce		json
// @Success		200	{object}	model.StatusResponse
// @Router			/healthz [get]
func (h *MetaHandlers) Health(c *gin.Context) {
	c.Header("Cache-Control", "no-store")
	c.JSON(http.StatusOK, model.StatusResponse{Status: "ok"})
}

// @Summary		Server configuration
// @Description	Reports which viewer is active and whether editing endpoints exist, so the browser can adapt without probing.
// @Tags			config
// @Produce		json
// @Success		200	{object}	model.ConfigResponse
// @Router			/api/config [get]
func (h *MetaHandlers) GetConfig(c *gin.Context) {
	c.JSON(http.StatusOK, h.Config)
}

// @Summary		Fetch an equipment icon
// @Description	Serves an embedded SVG icon by file name. The name must be a bare file name ending in `.svg`.
// @Tags			icons
// @Produce		image/svg+xml
// @Param			name	path		string	true	"Icon file name, including the .svg suffix"	example(sensor-temperature.svg)
// @Success		200		{string}	string	"SVG document"
// @Failure		400		{object}	model.ErrorResponse
// @Failure		404		{object}	model.ErrorResponse
// @Router			/api/icons/{name} [get]
func (h *MetaHandlers) GetIcon(c *gin.Context) {
	name := c.Param("name")
	if path.Base(name) != name || !strings.HasSuffix(strings.ToLower(name), ".svg") {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: "invalid icon name"})
		return
	}
	data, err := fs.ReadFile(h.DataFS, "data/equipment/icons/"+name)
	if err != nil {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "icon not found"})
		return
	}
	c.Header("Cache-Control", "public, max-age=3600")
	c.Data(http.StatusOK, "image/svg+xml", data)
}

// @Summary		OpenAPI description of this API
// @Description	Returns the OpenAPI 3.1 document describing every endpoint below, for use with API clients and code generators.
// @Tags			config
// @Produce		json
// @Success		200	{object}	object	"OpenAPI 3.1 document"
// @Failure		503	{object}	model.ErrorResponse
// @Router			/api/openapi.json [get]
func (h *MetaHandlers) GetOpenAPISpec(c *gin.Context) {
	if len(h.OpenAPISpec) == 0 {
		c.JSON(http.StatusServiceUnavailable, model.ErrorResponse{Error: "OpenAPI specification was not supplied to this server"})
		return
	}
	c.Header("Cache-Control", "public, max-age=300")
	c.Data(http.StatusOK, "application/json; charset=utf-8", h.OpenAPISpec)
}
