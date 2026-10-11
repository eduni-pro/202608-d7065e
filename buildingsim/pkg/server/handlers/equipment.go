package handlers

import (
	"errors"
	"net/http"

	"github.com/eislab-cps/buildingsim/pkg/model"
	"github.com/eislab-cps/buildingsim/pkg/server/websocket"
	"github.com/eislab-cps/buildingsim/pkg/store"
	"github.com/gin-gonic/gin"
)

type EquipmentHandlers struct {
	Store *store.MemoryStore
	Hub   *websocket.Hub
}

// @Summary		Create equipment
// @Description	Registers one equipment item with its sensors and actuators. Bumps the equipment version and notifies viewers.
// @Tags			equipment
// @Accept			json
// @Produce		json
// @Param			equipment	body		model.Equipment	true	"Equipment to create"
// @Success		201			{object}	model.Equipment
// @Failure		400			{object}	model.ErrorResponse
// @Failure		409			{object}	model.ErrorResponse
// @Router			/api/equipment [post]
func (h *EquipmentHandlers) Create(c *gin.Context) {
	var eq model.Equipment
	if err := c.ShouldBindJSON(&eq); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	if err := normalizeEquipmentLocation(h.Store, &eq); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	if err := h.Store.CreateEquipment(&eq); err != nil {
		status := http.StatusBadRequest
		if errors.Is(err, store.ErrEquipmentExists) || errors.Is(err, store.ErrSensorExists) || errors.Is(err, store.ErrActuatorExists) {
			status = http.StatusConflict
		}
		c.JSON(status, model.ErrorResponse{Error: err.Error()})
		return
	}
	version := broadcastEquipment(h.Hub, eq.Version)
	setMutationVersion(c, version)
	c.JSON(http.StatusCreated, eq)
}

// @Summary		List equipment
// @Description	Returns every equipment item, narrowed by any combination of the filters.
// @Tags			equipment
// @Produce		json
// @Param			level		query	string	false	"Filter by level"	example(level0)
// @Param			room		query	string	false	"Filter by room name"	example(A109)
// @Param			type		query	string	false	"Filter by equipment type"
// @Param			category	query	string	false	"Filter by category"
// @Success		200			{array}	model.Equipment
// @Router			/api/equipment [get]
func (h *EquipmentHandlers) List(c *gin.Context) {
	level := c.Query("level")
	room := c.Query("room")
	typ := c.Query("type")
	category := c.Query("category")
	result := h.Store.ListEquipment(level, room, typ, category)
	if result == nil {
		result = []*model.Equipment{}
	}
	c.JSON(http.StatusOK, result)
}

// @Summary		Get equipment by ID
// @Tags			equipment
// @Produce		json
// @Param			id	path		string	true	"Equipment ID"
// @Success		200	{object}	model.Equipment
// @Failure		404	{object}	model.ErrorResponse
// @Router			/api/equipment/{id} [get]
func (h *EquipmentHandlers) Get(c *gin.Context) {
	id := c.Param("id")
	eq, ok := h.Store.GetEquipment(id)
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "equipment not found"})
		return
	}
	c.JSON(http.StatusOK, eq)
}

// @Summary		Update equipment
// @Description	Replaces equipment metadata. Omitted sensor and actuator collections are preserved; an explicit empty array removes them.
// @Tags			equipment
// @Accept			json
// @Produce		json
// @Param			id			path		string			true	"Equipment ID"
// @Param			equipment	body		model.Equipment	true	"Replacement equipment"
// @Success		200			{object}	model.Equipment
// @Failure		400			{object}	model.ErrorResponse
// @Failure		404			{object}	model.ErrorResponse
// @Failure		409			{object}	model.ErrorResponse
// @Router			/api/equipment/{id} [put]
func (h *EquipmentHandlers) Update(c *gin.Context) {
	id := c.Param("id")
	old, exists := h.Store.GetEquipment(id)
	if !exists {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "equipment not found"})
		return
	}
	var eq model.Equipment
	if err := c.ShouldBindJSON(&eq); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	eq.ID = id
	// PUT updates equipment metadata. Omitted child collections are preserved;
	// explicit [] replaces them and intentionally removes the old children.
	if eq.Sensors == nil {
		eq.Sensors = old.Sensors
	}
	if eq.Actuators == nil {
		eq.Actuators = old.Actuators
	}
	if err := normalizeEquipmentLocation(h.Store, &eq); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	if err := h.Store.UpdateEquipment(&eq); err != nil {
		status := http.StatusBadRequest
		if errors.Is(err, store.ErrEquipmentNotFound) {
			status = http.StatusNotFound
		} else if errors.Is(err, store.ErrSensorExists) || errors.Is(err, store.ErrActuatorExists) {
			status = http.StatusConflict
		}
		c.JSON(status, model.ErrorResponse{Error: err.Error()})
		return
	}
	version := broadcastEquipment(h.Hub, eq.Version)
	setMutationVersion(c, version)
	c.JSON(http.StatusOK, eq)
}

// @Summary		Delete equipment
// @Description	Removes the item together with its sensors and actuators.
// @Tags			equipment
// @Produce		json
// @Param			id	path		string	true	"Equipment ID"
// @Success		200	{object}	model.MutationResponse
// @Failure		404	{object}	model.ErrorResponse
// @Router			/api/equipment/{id} [delete]
func (h *EquipmentHandlers) Delete(c *gin.Context) {
	id := c.Param("id")
	version, ok := h.Store.DeleteEquipment(id)
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "equipment not found"})
		return
	}
	version = broadcastEquipment(h.Hub, version)
	setMutationVersion(c, version)
	c.JSON(http.StatusOK, model.MutationResponse{Status: "deleted", Version: version})
}

// @Summary		Create equipment in bulk
// @Description	Creates many items in one request. Items whose ID already exists are skipped rather than rejected, so the call is safe to repeat. Returns 201 when at least one item was created and 200 when every item was skipped.
// @Tags			equipment
// @Accept			json
// @Produce		json
// @Param			equipment	body		[]model.Equipment	true	"Equipment to create"
// @Success		201			{object}	model.BulkResult	"At least one item created"
// @Success		200			{object}	model.BulkResult	"Every item already existed"
// @Failure		400			{object}	model.ErrorResponse
// @Failure		409			{object}	model.ErrorResponse
// @Router			/api/equipment/bulk [post]
func (h *EquipmentHandlers) BulkCreate(c *gin.Context) {
	var items []model.Equipment
	if err := c.ShouldBindJSON(&items); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	if len(items) == 0 {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: "at least one equipment item is required"})
		return
	}
	for i := range items {
		if err := normalizeEquipmentLocation(h.Store, &items[i]); err != nil {
			c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error(), Item: &i})
			return
		}
	}
	created, skipped, version, err := h.Store.CreateEquipmentBatch(items)
	if err != nil {
		status := http.StatusBadRequest
		if errors.Is(err, store.ErrSensorExists) || errors.Is(err, store.ErrActuatorExists) {
			status = http.StatusConflict
		}
		c.JSON(status, model.ErrorResponse{Error: err.Error()})
		return
	}
	if created > 0 {
		version = broadcastEquipment(h.Hub, version)
	}
	setMutationVersion(c, version)
	status := http.StatusCreated
	if created == 0 {
		status = http.StatusOK
	}
	c.JSON(status, model.BulkResult{Created: created, Skipped: skipped, Total: len(items), Version: version})
}

// @Summary		Force an equipment notification
// @Description	Bumps the equipment version and notifies viewers without changing state. Normal mutations do this automatically, so this endpoint is rarely needed.
// @Tags			equipment
// @Produce		json
// @Success		200	{object}	model.VersionResponse
// @Router			/api/equipment/notify [post]
func (h *EquipmentHandlers) Notify(c *gin.Context) {
	version := notifyEquipment(h.Store, h.Hub)
	setMutationVersion(c, version)
	c.JSON(http.StatusOK, model.VersionResponse{Version: version})
}
