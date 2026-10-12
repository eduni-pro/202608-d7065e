package handlers

import (
	"net/http"

	"github.com/eislab-cps/buildingsim/pkg/model"
	"github.com/eislab-cps/buildingsim/pkg/store"
	"github.com/gin-gonic/gin"
)

type BuildingHandlers struct {
	Store *store.MemoryStore
}

// @Summary		Building overview
// @Description	Returns the building name and its ordered list of levels.
// @Tags			building
// @Produce		json
// @Success		200	{object}	model.Building
// @Router			/api/building [get]
func (h *BuildingHandlers) GetBuilding(c *gin.Context) {
	c.JSON(http.StatusOK, h.Store.GetBuilding())
}

// @Summary		Floor plan for one level
// @Description	Returns rooms, walls, labels, and both navigation graphs for a single level.
// @Tags			building
// @Produce		json
// @Param			level	path		string	true	"Level ID"	example(level0)
// @Success		200		{object}	model.FloorData
// @Failure		404		{object}	model.ErrorResponse
// @Router			/api/building/floors/{level} [get]
func (h *BuildingHandlers) GetFloor(c *gin.Context) {
	level := c.Param("level")
	data, ok := h.Store.GetFloorData(level)
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "level not found"})
		return
	}
	c.JSON(http.StatusOK, data)
}

// @Summary		Cross-floor connections
// @Description	Returns the stair and elevator edges that join per-level graphs into one walkable multi-floor graph.
// @Tags			building
// @Produce		json
// @Success		200	{array}	model.CrossFloorEdge
// @Router			/api/building/cross-floor-edges [get]
func (h *BuildingHandlers) GetCrossFloorEdges(c *gin.Context) {
	c.JSON(http.StatusOK, h.Store.GetCrossFloorEdges())
}
