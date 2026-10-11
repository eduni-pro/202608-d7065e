package handlers

import (
	"net/http"

	"github.com/eislab-cps/buildingsim/pkg/model"
	"github.com/eislab-cps/buildingsim/pkg/server/websocket"
	"github.com/eislab-cps/buildingsim/pkg/store"
	"github.com/gin-gonic/gin"
)

type CoverageHandlers struct {
	Store *store.MemoryStore
	Hub   *websocket.Hub
}

// @Summary		Get coverage zones
// @Tags			coverage
// @Produce		json
// @Success		200	{array}	model.CoverageZone
// @Router			/api/coverage [get]
func (h *CoverageHandlers) Get(c *gin.Context) {
	c.JSON(http.StatusOK, h.Store.GetCoverage())
}

// @Summary		Replace coverage zones
// @Description	Replaces every zone. A zone centres on `room` when given, otherwise on explicit `center` coordinates.
// @Tags			coverage
// @Accept			json
// @Produce		json
// @Param			zones	body		[]model.CoverageZone	true	"Coverage zones"
// @Success		200		{object}	model.MutationResponse
// @Failure		400		{object}	model.ErrorResponse
// @Router			/api/coverage [put]
func (h *CoverageHandlers) Set(c *gin.Context) {
	var zones []model.CoverageZone
	if err := c.ShouldBindJSON(&zones); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	if err := normalizeCoverage(h.Store, zones); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	version := h.Store.SetCoverage(zones)
	// Global state is fetched from the canonical REST snapshot. Omitting Data
	// distinguishes this broadcast from a session-specific coverage overlay.
	h.Hub.BroadcastToAll(websocket.Message{Type: "coverage", Version: version})
	c.JSON(http.StatusOK, model.MutationResponse{Status: "updated", Version: version})
}
