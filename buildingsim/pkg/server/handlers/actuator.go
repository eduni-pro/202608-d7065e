package handlers

import (
	"net/http"
	"strings"

	"github.com/eislab-cps/buildingsim/pkg/model"
	"github.com/eislab-cps/buildingsim/pkg/server/websocket"
	"github.com/eislab-cps/buildingsim/pkg/store"
	"github.com/gin-gonic/gin"
)

type ActuatorHandlers struct {
	Store *store.MemoryStore
	Hub   *websocket.Hub
}

// @Summary		Add an actuator to equipment
// @Tags			actuators
// @Accept			json
// @Produce		json
// @Param			id			path		string			true	"Equipment ID"
// @Param			actuator	body		model.Actuator	true	"Actuator to add"
// @Success		201			{object}	model.Actuator
// @Failure		400			{object}	model.ErrorResponse
// @Failure		404			{object}	model.ErrorResponse
// @Failure		409			{object}	model.ErrorResponse
// @Router			/api/equipment/{id}/actuators [post]
func (h *ActuatorHandlers) AddActuator(c *gin.Context) {
	equipmentID := c.Param("id")
	var act model.Actuator
	if err := c.ShouldBindJSON(&act); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	act.ID = strings.TrimSpace(act.ID)
	if act.ID == "" {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: "id is required"})
		return
	}
	version, ok, errMsg := h.Store.AddActuator(equipmentID, &act)
	if !ok {
		if errMsg == "actuator already exists" {
			c.JSON(http.StatusConflict, model.ErrorResponse{Error: errMsg})
		} else {
			c.JSON(http.StatusNotFound, model.ErrorResponse{Error: errMsg})
		}
		return
	}
	version = broadcastEquipment(h.Hub, version)
	setMutationVersion(c, version)
	c.JSON(http.StatusCreated, act)
}

// @Summary		Get an actuator
// @Tags			actuators
// @Produce		json
// @Param			id	path		string	true	"Actuator ID"
// @Success		200	{object}	model.Actuator
// @Failure		404	{object}	model.ErrorResponse
// @Router			/api/actuators/{id} [get]
func (h *ActuatorHandlers) GetActuator(c *gin.Context) {
	actuator, ok := h.Store.GetActuator(c.Param("id"))
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "actuator not found"})
		return
	}
	c.JSON(http.StatusOK, actuator)
}

// @Summary		List the actuators of one equipment item
// @Tags			actuators
// @Produce		json
// @Param			id	path	string	true	"Equipment ID"
// @Success		200	{array}		model.Actuator
// @Failure		404	{object}	model.ErrorResponse
// @Router			/api/equipment/{id}/actuators [get]
func (h *ActuatorHandlers) ListActuators(c *gin.Context) {
	equipmentID := c.Param("id")
	actuators, ok := h.Store.GetActuatorsForEquipment(equipmentID)
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "equipment not found"})
		return
	}
	c.JSON(http.StatusOK, actuators)
}

// @Summary		Delete an actuator
// @Tags			actuators
// @Produce		json
// @Param			id	path		string	true	"Actuator ID"
// @Success		200	{object}	model.MutationResponse
// @Failure		404	{object}	model.ErrorResponse
// @Router			/api/actuators/{id} [delete]
func (h *ActuatorHandlers) DeleteActuator(c *gin.Context) {
	actuatorID := c.Param("id")
	version, ok := h.Store.DeleteActuator(actuatorID)
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "actuator not found"})
		return
	}
	version = broadcastEquipment(h.Hub, version)
	setMutationVersion(c, version)
	c.JSON(http.StatusOK, model.MutationResponse{Status: "deleted", Version: version})
}

// @Summary		Set an actuator state
// @Tags			actuators
// @Accept			json
// @Produce		json
// @Param			id		path		string					true	"Actuator ID"
// @Param			state	body		model.ActuatorState	true	"New state"
// @Success		200		{object}	model.MutationResponse
// @Failure		400		{object}	model.ErrorResponse
// @Failure		404		{object}	model.ErrorResponse
// @Router			/api/actuators/{id}/state [put]
func (h *ActuatorHandlers) SetState(c *gin.Context) {
	actuatorID := c.Param("id")
	var state model.ActuatorState
	if err := c.ShouldBindJSON(&state); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	state.State = strings.TrimSpace(state.State)
	if state.State == "" {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: "state is required"})
		return
	}
	version, ok := h.Store.SetActuatorState(actuatorID, state)
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "actuator not found"})
		return
	}
	version = broadcastEquipment(h.Hub, version)
	setMutationVersion(c, version)
	c.JSON(http.StatusOK, model.MutationResponse{Status: "updated", Version: version})
}
