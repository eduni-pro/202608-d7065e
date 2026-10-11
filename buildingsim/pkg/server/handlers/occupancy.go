package handlers

import (
	"net/http"

	"github.com/eislab-cps/buildingsim/pkg/model"
	"github.com/eislab-cps/buildingsim/pkg/server/websocket"
	"github.com/eislab-cps/buildingsim/pkg/store"
	"github.com/gin-gonic/gin"
)

type OccupancyHandlers struct {
	Store *store.MemoryStore
	Hub   *websocket.Hub
}

// @Summary		Get building occupancy
// @Description	Returns the global occupancy map keyed by canonical `<level>/<room-name>`.
// @Tags			occupancy
// @Produce		json
// @Success		200	{object}	map[string]model.RoomOccupancy
// @Router			/api/occupancy [get]
func (h *OccupancyHandlers) Get(c *gin.Context) {
	c.JSON(http.StatusOK, h.Store.GetOccupancy())
}

// @Summary		Replace building occupancy
// @Description	Replaces the whole occupancy map. Keys may be `<level>/<room-name>`, or a bare room name when it is unique across the building.
// @Tags			occupancy
// @Accept			json
// @Produce		json
// @Param			occupancy	body		map[string]model.RoomOccupancy	true	"Occupancy by room key"
// @Success		200			{object}	model.MutationResponse
// @Failure		400			{object}	model.ErrorResponse
// @Router			/api/occupancy [put]
func (h *OccupancyHandlers) Set(c *gin.Context) {
	var occ map[string]model.RoomOccupancy
	if err := c.ShouldBindJSON(&occ); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	if occ == nil {
		occ = make(map[string]model.RoomOccupancy)
	}
	normalized, err := normalizeOccupancy(h.Store, occ)
	if err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	version := h.Store.SetOccupancy(normalized)
	h.Hub.BroadcastToAll(websocket.Message{Type: "occupancy", Version: version})
	c.JSON(http.StatusOK, model.MutationResponse{Status: "updated", Version: version})
}
