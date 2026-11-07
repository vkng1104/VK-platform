package airetrieval

import "errors"

var (
	ErrBusy           = errors.New("AI retrieval capacity is full")
	ErrInvalidQuery   = errors.New("AI query is invalid")
	ErrInvalidTopK    = errors.New("AI top_k is invalid")
	ErrMissingRuntime = errors.New("AI runtime is required when AI is enabled")
	ErrTimeout        = errors.New("AI retrieval timed out")
	ErrUnavailable    = errors.New("AI retrieval is unavailable")
)
