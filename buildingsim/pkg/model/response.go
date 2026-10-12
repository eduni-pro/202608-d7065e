package model

// Fields are declared in the order encoding/json emits map keys, so replacing
// a gin.H literal with one of these types leaves the response bytes unchanged.

// StatusResponse acknowledges an operation that carries no version counter.
type StatusResponse struct {
	Status string `json:"status"`
}

// MutationResponse acknowledges a state change and carries the counter a
// WebSocket client compares against to decide whether its snapshot is stale.
type MutationResponse struct {
	Status  string `json:"status"`
	Version int64  `json:"version"`
}

// VersionResponse reports a version counter on its own.
type VersionResponse struct {
	Version int64 `json:"version"`
}

// BulkResult summarizes a bulk equipment create. Version repeats the previous
// counter when every item was skipped, so it is reported even when zero.
type BulkResult struct {
	Created int   `json:"created"`
	Skipped int   `json:"skipped"`
	Total   int   `json:"total"`
	Version int64 `json:"version"`
}

// ConfigResponse describes the running server to the browser.
type ConfigResponse struct {
	EditMode       bool   `json:"edit_mode"`
	EditPersistent bool   `json:"edit_persistent"`
	Host           string `json:"host"`
	UI             string `json:"ui"`
}

// EditorSaveResponse reports an editor write. Level is absent when saving
// inspection notes, which are not scoped to a floor.
type EditorSaveResponse struct {
	Level      string `json:"level,omitempty"`
	Persistent bool   `json:"persistent"`
	Status     string `json:"status"`
}

// ErrorResponse is the body of every non-2xx API response. Item identifies the
// offending element of a bulk request.
type ErrorResponse struct {
	Error string `json:"error"`
	Item  *int   `json:"item,omitempty"`
}

// RoomHighlightRequest is the wire form of a session highlight. Opacity is a
// pointer so an omitted value (default 0.8) is distinguishable from an
// explicit zero, which means fully transparent.
type RoomHighlightRequest struct {
	Level   string   `json:"level"`
	Room    string   `json:"room,omitempty"`
	RoomID  int      `json:"room_id,omitempty"`
	Color   string   `json:"color"`
	Opacity *float64 `json:"opacity"`
}
