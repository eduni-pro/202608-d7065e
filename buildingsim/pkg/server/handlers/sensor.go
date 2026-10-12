package handlers

import (
	"net/http"
	"strings"

	"github.com/eislab-cps/buildingsim/pkg/model"
	"github.com/eislab-cps/buildingsim/pkg/server/websocket"
	"github.com/eislab-cps/buildingsim/pkg/store"
	"github.com/gin-gonic/gin"
)

type SensorHandlers struct {
	Store *store.MemoryStore
	Hub   *websocket.Hub
}

// @Summary		Add a sensor to equipment
// @Tags			sensors
// @Accept			json
// @Produce		json
// @Param			id		path		string			true	"Equipment ID"
// @Param			sensor	body		model.Sensor	true	"Sensor to add"
// @Success		201		{object}	model.Sensor
// @Failure		400		{object}	model.ErrorResponse
// @Failure		404		{object}	model.ErrorResponse
// @Failure		409		{object}	model.ErrorResponse
// @Router			/api/equipment/{id}/sensors [post]
func (h *SensorHandlers) AddSensor(c *gin.Context) {
	equipmentID := c.Param("id")
	var sen model.Sensor
	if err := c.ShouldBindJSON(&sen); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	sen.ID = strings.TrimSpace(sen.ID)
	if sen.ID == "" {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: "id is required"})
		return
	}
	if sen.DataType == "" {
		sen.DataType = "text"
	}
	if sen.DataType != "text" && sen.DataType != "binary" {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: "data_type must be text or binary"})
		return
	}
	version, ok, errMsg := h.Store.AddSensor(equipmentID, &sen)
	if !ok {
		if errMsg == "sensor already exists" {
			c.JSON(http.StatusConflict, model.ErrorResponse{Error: errMsg})
		} else {
			c.JSON(http.StatusNotFound, model.ErrorResponse{Error: errMsg})
		}
		return
	}
	version = broadcastEquipment(h.Hub, version)
	setMutationVersion(c, version)
	c.JSON(http.StatusCreated, sen)
}

// @Summary		Get a sensor
// @Tags			sensors
// @Produce		json
// @Param			id	path		string	true	"Sensor ID"
// @Success		200	{object}	model.Sensor
// @Failure		404	{object}	model.ErrorResponse
// @Router			/api/sensors/{id} [get]
func (h *SensorHandlers) GetSensor(c *gin.Context) {
	sensor, ok := h.Store.GetSensor(c.Param("id"))
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "sensor not found"})
		return
	}
	c.JSON(http.StatusOK, sensor)
}

// @Summary		List the sensors of one equipment item
// @Tags			sensors
// @Produce		json
// @Param			id	path	string	true	"Equipment ID"
// @Success		200	{array}		model.Sensor
// @Failure		404	{object}	model.ErrorResponse
// @Router			/api/equipment/{id}/sensors [get]
func (h *SensorHandlers) ListSensors(c *gin.Context) {
	equipmentID := c.Param("id")
	sensors, ok := h.Store.GetSensorsForEquipment(equipmentID)
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "equipment not found"})
		return
	}
	c.JSON(http.StatusOK, sensors)
}

// @Summary		Delete a sensor
// @Tags			sensors
// @Produce		json
// @Param			id	path		string	true	"Sensor ID"
// @Success		200	{object}	model.MutationResponse
// @Failure		404	{object}	model.ErrorResponse
// @Router			/api/sensors/{id} [delete]
func (h *SensorHandlers) DeleteSensor(c *gin.Context) {
	sensorID := c.Param("id")
	version, ok := h.Store.DeleteSensor(sensorID)
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "sensor not found"})
		return
	}
	version = broadcastEquipment(h.Hub, version)
	setMutationVersion(c, version)
	c.JSON(http.StatusOK, model.MutationResponse{Status: "deleted", Version: version})
}

// @Summary		Set a sensor value
// @Description	Publishes a reading. A binary sensor sets binary_value, a text sensor sets value; data_type selects which.
// @Tags			sensors
// @Accept			json
// @Produce		json
// @Param			id		path		string				true	"Sensor ID"
// @Param			value	body		model.SensorValue	true	"New reading"
// @Success		200		{object}	model.MutationResponse
// @Failure		400		{object}	model.ErrorResponse
// @Failure		404		{object}	model.ErrorResponse
// @Router			/api/sensors/{id}/value [put]
func (h *SensorHandlers) SetValue(c *gin.Context) {
	sensorID := c.Param("id")
	var val model.SensorValue
	if err := c.ShouldBindJSON(&val); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	if err := validateSensorValue(val); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	version, ok := h.Store.SetSensorValue(sensorID, val)
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "sensor not found"})
		return
	}
	version = broadcastEquipment(h.Hub, version)
	setMutationVersion(c, version)
	c.JSON(http.StatusOK, model.MutationResponse{Status: "updated", Version: version})
}
