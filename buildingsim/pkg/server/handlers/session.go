package handlers

import (
	"net/http"

	"github.com/eislab-cps/buildingsim/pkg/model"
	"github.com/eislab-cps/buildingsim/pkg/server/websocket"
	"github.com/eislab-cps/buildingsim/pkg/store"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type SessionHandlers struct {
	Store *store.MemoryStore
	Hub   *websocket.Hub
}

// @Summary		List viewer sessions
// @Tags			sessions
// @Produce		json
// @Success		200	{array}	model.Session
// @Router			/api/sessions [get]
func (h *SessionHandlers) List(c *gin.Context) {
	sessions := h.Store.ListSessions()
	if sessions == nil {
		sessions = []*model.Session{}
	}
	c.JSON(http.StatusOK, sessions)
}

// @Summary		Create a viewer session
// @Description	Allocates a session ID. Connect a WebSocket to /ws/{id} to receive its updates.
// @Tags			sessions
// @Produce		json
// @Success		201	{object}	model.Session
// @Router			/api/sessions [post]
func (h *SessionHandlers) Create(c *gin.Context) {
	id := uuid.New().String()
	sess := h.Store.CreateSession(id)
	c.JSON(http.StatusCreated, sess)
}

// @Summary		Get a session
// @Tags			sessions
// @Produce		json
// @Param			id	path		string	true	"Session ID"
// @Success		200	{object}	model.Session
// @Failure		404	{object}	model.ErrorResponse
// @Router			/api/sessions/{id} [get]
func (h *SessionHandlers) Get(c *gin.Context) {
	id := c.Param("id")
	sess, ok := h.Store.GetSession(id)
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "session not found"})
		return
	}
	c.JSON(http.StatusOK, sess)
}

// @Summary		Delete a session
// @Tags			sessions
// @Produce		json
// @Param			id	path		string	true	"Session ID"
// @Success		200	{object}	model.StatusResponse
// @Failure		404	{object}	model.ErrorResponse
// @Router			/api/sessions/{id} [delete]
func (h *SessionHandlers) Delete(c *gin.Context) {
	id := c.Param("id")
	if !h.Store.DeleteSession(id) {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "session not found"})
		return
	}
	c.JSON(http.StatusOK, model.StatusResponse{Status: "deleted"})
}

// @Summary		Set the session viewport
// @Description	Moves one viewer: which room is centred, the zoom, and 2D or 3D mode.
// @Tags			sessions
// @Accept			json
// @Produce		json
// @Param			id			path		string			true	"Session ID"
// @Param			viewport	body		model.Viewport	true	"Viewport"
// @Success		200			{object}	model.MutationResponse
// @Failure		400			{object}	model.ErrorResponse
// @Failure		404			{object}	model.ErrorResponse
// @Router			/api/sessions/{id}/viewport [put]
func (h *SessionHandlers) SetViewport(c *gin.Context) {
	id := c.Param("id")
	var vp model.Viewport
	if err := c.ShouldBindJSON(&vp); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	if err := normalizeViewport(h.Store, &vp); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	version, ok := h.Store.UpdateSessionViewport(id, vp)
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "session not found"})
		return
	}
	h.Hub.SendToSession(id, websocket.Message{Type: "viewport", Data: vp, Version: version})
	c.JSON(http.StatusOK, model.MutationResponse{Status: "updated", Version: version})
}

// @Summary		Set session room highlights
// @Description	Replaces the highlighted rooms for one viewer. Omitted opacity defaults to 0.8.
// @Tags			sessions
// @Accept			json
// @Produce		json
// @Param			id			path		string							true	"Session ID"
// @Param			highlights	body		[]model.RoomHighlightRequest	true	"Highlights"
// @Success		200			{object}	model.MutationResponse
// @Failure		400			{object}	model.ErrorResponse
// @Failure		404			{object}	model.ErrorResponse
// @Router			/api/sessions/{id}/highlights [put]
func (h *SessionHandlers) SetHighlights(c *gin.Context) {
	id := c.Param("id")
	var requested []model.RoomHighlightRequest
	if err := c.ShouldBindJSON(&requested); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	highlights := make([]model.RoomHighlight, len(requested))
	for i, request := range requested {
		opacity := 0.8
		if request.Opacity != nil {
			opacity = *request.Opacity
		}
		highlights[i] = model.RoomHighlight{
			Level: request.Level, Room: request.Room, RoomID: request.RoomID,
			Color: request.Color, Opacity: opacity,
		}
	}
	if err := normalizeHighlights(h.Store, highlights); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	version, ok := h.Store.UpdateSessionHighlights(id, highlights)
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "session not found"})
		return
	}
	h.Hub.SendToSession(id, websocket.Message{Type: "highlights", Data: highlights, Version: version})
	c.JSON(http.StatusOK, model.MutationResponse{Status: "updated", Version: version})
}

// @Summary		Set session occupancy
// @Description	Replaces the occupancy overlay for one viewer, leaving the global map untouched.
// @Tags			sessions
// @Accept			json
// @Produce		json
// @Param			id			path		string							true	"Session ID"
// @Param			occupancy	body		map[string]model.RoomOccupancy	true	"Occupancy by room key"
// @Success		200			{object}	model.MutationResponse
// @Failure		400			{object}	model.ErrorResponse
// @Failure		404			{object}	model.ErrorResponse
// @Router			/api/sessions/{id}/occupancy [put]
func (h *SessionHandlers) SetOccupancy(c *gin.Context) {
	id := c.Param("id")
	var occupancy map[string]model.RoomOccupancy
	if err := c.ShouldBindJSON(&occupancy); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	if occupancy == nil {
		occupancy = make(map[string]model.RoomOccupancy)
	}
	normalized, err := normalizeOccupancy(h.Store, occupancy)
	if err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	version, ok := h.Store.UpdateSessionOccupancy(id, normalized)
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "session not found"})
		return
	}
	h.Hub.SendToSession(id, websocket.Message{Type: "occupancy", Data: normalized, Version: version})
	c.JSON(http.StatusOK, model.MutationResponse{Status: "updated", Version: version})
}

// @Summary		Show a route in a session
// @Description	Draws a path in one viewer. Usually the body is a result from /api/graph/route.
// @Tags			sessions
// @Accept			json
// @Produce		json
// @Param			id		path		string				true	"Session ID"
// @Param			route	body		model.RouteResult	true	"Route to display"
// @Success		200		{object}	model.MutationResponse
// @Failure		400		{object}	model.ErrorResponse
// @Failure		404		{object}	model.ErrorResponse
// @Router			/api/sessions/{id}/route [put]
func (h *SessionHandlers) SetRoute(c *gin.Context) {
	id := c.Param("id")
	var route model.RouteResult
	if err := c.ShouldBindJSON(&route); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	version, ok := h.Store.UpdateSessionRoute(id, &route)
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "session not found"})
		return
	}
	h.Hub.SendToSession(id, websocket.Message{Type: "route", Data: route, Version: version})
	c.JSON(http.StatusOK, model.MutationResponse{Status: "updated", Version: version})
}

// @Summary		Set session coverage zones
// @Description	Replaces the coverage overlay for one viewer, leaving the global zones untouched.
// @Tags			sessions
// @Accept			json
// @Produce		json
// @Param			id			path		string					true	"Session ID"
// @Param			coverage	body		[]model.CoverageZone	true	"Coverage zones"
// @Success		200			{object}	model.MutationResponse
// @Failure		400			{object}	model.ErrorResponse
// @Failure		404			{object}	model.ErrorResponse
// @Router			/api/sessions/{id}/coverage [put]
func (h *SessionHandlers) SetCoverage(c *gin.Context) {
	id := c.Param("id")
	var coverage []model.CoverageZone
	if err := c.ShouldBindJSON(&coverage); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	if err := normalizeCoverage(h.Store, coverage); err != nil {
		c.JSON(http.StatusBadRequest, model.ErrorResponse{Error: err.Error()})
		return
	}
	version, ok := h.Store.UpdateSessionCoverage(id, coverage)
	if !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "session not found"})
		return
	}
	h.Hub.SendToSession(id, websocket.Message{Type: "coverage", Data: coverage, Version: version})
	c.JSON(http.StatusOK, model.MutationResponse{Status: "updated", Version: version})
}

// @Summary		Subscribe to session updates
// @Description	Upgrades to a WebSocket carrying small typed notifications with a version counter. Clients re-fetch the matching REST snapshot rather than trusting the message body. Origins are restricted to the same origin, with a loopback-to-loopback allowance.
// @Tags			sessions
// @Produce		json
// @Param			id	path		string	true	"Session ID"
// @Success		101	{string}	string	"Switching Protocols"
// @Failure		404	{object}	model.ErrorResponse
// @Router			/ws/{id} [get]
func (h *SessionHandlers) HandleWebSocket(c *gin.Context) {
	id := c.Param("id")
	if _, ok := h.Store.GetSession(id); !ok {
		c.JSON(http.StatusNotFound, model.ErrorResponse{Error: "session not found"})
		return
	}

	upgrader := websocket.Upgrader()
	conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		return
	}

	h.Hub.HandleConnection(conn, id)
}
