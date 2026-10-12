package handlers

import (
	"net/http"

	"github.com/eislab-cps/buildingsim/pkg/model"
	"github.com/eislab-cps/buildingsim/pkg/server/websocket"
	"github.com/eislab-cps/buildingsim/pkg/store"
	"github.com/gin-gonic/gin"
)

// VisualizationHandlers exposes full-replacement snapshots. Producers can
// update each domain independently, and browsers fetch the canonical snapshot
// after receiving its small version notification over WebSocket.
type VisualizationHandlers struct {
	Store *store.MemoryStore
	Hub   *websocket.Hub
}

// @Summary		Get room layers
// @Tags			visualization
// @Produce		json
// @Success		200	{array}	model.RoomLayer
// @Router			/api/room-layers [get]
func (h *VisualizationHandlers) GetRoomLayers(c *gin.Context) {
	c.JSON(http.StatusOK, h.Store.GetRoomLayers())
}

// @Summary		Replace room layers
// @Description	Full replacement. A layer is a scalar field keyed by canonical room key. Viewers are notified with a version counter and re-fetch this snapshot.
// @Tags			visualization
// @Accept			json
// @Produce		json
// @Param			body	body		[]model.RoomLayer	true	"Room layers"
// @Success		200		{object}	model.MutationResponse
// @Failure		400		{object}	model.ErrorResponse
// @Router			/api/room-layers [put]
func (h *VisualizationHandlers) SetRoomLayers(c *gin.Context) {
	var layers []model.RoomLayer
	if !bindCollection(c, &layers) || !validateCollection(c, normalizeRoomLayers(h.Store, layers)) {
		return
	}
	h.updated(c, "room_layers", h.Store.SetRoomLayers(layers))
}

// @Summary		Get visual effects
// @Tags			visualization
// @Produce		json
// @Success		200	{array}	model.VisualEffect
// @Router			/api/effects [get]
func (h *VisualizationHandlers) GetEffects(c *gin.Context) {
	c.JSON(http.StatusOK, h.Store.GetEffects())
}

// @Summary		Replace visual effects
// @Description	Full replacement. BuildSim renders the effect; the simulator owns its evolution. Viewers are notified with a version counter and re-fetch this snapshot.
// @Tags			visualization
// @Accept			json
// @Produce		json
// @Param			body	body		[]model.VisualEffect	true	"Visual effects"
// @Success		200		{object}	model.MutationResponse
// @Failure		400		{object}	model.ErrorResponse
// @Router			/api/effects [put]
func (h *VisualizationHandlers) SetEffects(c *gin.Context) {
	var effects []model.VisualEffect
	if !bindCollection(c, &effects) || !validateCollection(c, normalizeEffects(h.Store, effects)) {
		return
	}
	h.updated(c, "effects", h.Store.SetEffects(effects))
}

// @Summary		Get mobile entities
// @Tags			visualization
// @Produce		json
// @Success		200	{array}	model.MobileEntity
// @Router			/api/entities [get]
func (h *VisualizationHandlers) GetEntities(c *gin.Context) {
	c.JSON(http.StatusOK, h.Store.GetEntities())
}

// @Summary		Replace mobile entities
// @Description	Full replacement. The browser interpolates between successive positions. Viewers are notified with a version counter and re-fetch this snapshot.
// @Tags			visualization
// @Accept			json
// @Produce		json
// @Param			body	body		[]model.MobileEntity	true	"Mobile entities"
// @Success		200		{object}	model.MutationResponse
// @Failure		400		{object}	model.ErrorResponse
// @Router			/api/entities [put]
func (h *VisualizationHandlers) SetEntities(c *gin.Context) {
	var entities []model.MobileEntity
	if !bindCollection(c, &entities) || !validateCollection(c, normalizeEntities(h.Store, entities)) {
		return
	}
	h.updated(c, "entities", h.Store.SetEntities(entities))
}

// @Summary		Get room appearance
// @Tags			visualization
// @Produce		json
// @Success		200	{array}	model.RoomAppearance
// @Router			/api/room-appearance [get]
func (h *VisualizationHandlers) GetRoomAppearance(c *gin.Context) {
	c.JSON(http.StatusOK, h.Store.GetRoomAppearance())
}

// @Summary		Replace room appearance
// @Description	Full replacement. Controls room illumination independently of any numeric layer. Viewers are notified with a version counter and re-fetch this snapshot.
// @Tags			visualization
// @Accept			json
// @Produce		json
// @Param			body	body		[]model.RoomAppearance	true	"Room appearance"
// @Success		200		{object}	model.MutationResponse
// @Failure		400		{object}	model.ErrorResponse
// @Router			/api/room-appearance [put]
func (h *VisualizationHandlers) SetRoomAppearance(c *gin.Context) {
	var appearance []model.RoomAppearance
	if !bindCollection(c, &appearance) || !validateCollection(c, normalizeRoomAppearance(h.Store, appearance)) {
		return
	}
	h.updated(c, "room_appearance", h.Store.SetRoomAppearance(appearance))
}

// @Summary		Get decision alerts
// @Tags			visualization
// @Produce		json
// @Success		200	{array}	model.DecisionAlert
// @Router			/api/alerts [get]
func (h *VisualizationHandlers) GetAlerts(c *gin.Context) {
	c.JSON(http.StatusOK, h.Store.GetAlerts())
}

// @Summary		Replace decision alerts
// @Description	Full replacement. Alerts are current operational messages, not a historical log. Viewers are notified with a version counter and re-fetch this snapshot.
// @Tags			visualization
// @Accept			json
// @Produce		json
// @Param			body	body		[]model.DecisionAlert	true	"Decision alerts"
// @Success		200		{object}	model.MutationResponse
// @Failure		400		{object}	model.ErrorResponse
// @Router			/api/alerts [put]
func (h *VisualizationHandlers) SetAlerts(c *gin.Context) {
	var alerts []model.DecisionAlert
	if !bindCollection(c, &alerts) || !validateCollection(c, normalizeAlerts(h.Store, alerts)) {
		return
	}
	h.updated(c, "alerts", h.Store.SetAlerts(alerts))
}

// @Summary		Get doors
// @Tags			visualization
// @Produce		json
// @Success		200	{array}	model.Door
// @Router			/api/doors [get]
func (h *VisualizationHandlers) GetDoors(c *gin.Context) {
	c.JSON(http.StatusOK, h.Store.GetDoors())
}

// @Summary		Replace doors
// @Description	Full replacement. A locked or blocked entry node is removed from walkable routing. Viewers are notified with a version counter and re-fetch this snapshot.
// @Tags			visualization
// @Accept			json
// @Produce		json
// @Param			body	body		[]model.Door	true	"Doors"
// @Success		200		{object}	model.MutationResponse
// @Failure		400		{object}	model.ErrorResponse
// @Router			/api/doors [put]
func (h *VisualizationHandlers) SetDoors(c *gin.Context) {
	var doors []model.Door
	if !bindCollection(c, &doors) || !validateCollection(c, normalizeDoors(h.Store, doors)) {
		return
	}
	h.updated(c, "doors", h.Store.SetDoors(doors))
}

func (h *VisualizationHandlers) updated(c *gin.Context, messageType string, version int64) {
	if h.Hub != nil {
		h.Hub.BroadcastToAll(websocket.Message{Type: messageType, Version: version})
	}
	setMutationVersion(c, version)
	c.JSON(http.StatusOK, model.MutationResponse{Status: "updated", Version: version})
}

func bindCollection(c *gin.Context, destination interface{}) bool {
	if err := c.ShouldBindJSON(destination); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return false
	}
	return true
}

func validateCollection(c *gin.Context, err error) bool {
	if err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return false
	}
	return true
}
