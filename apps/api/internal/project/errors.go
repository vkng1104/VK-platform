package project

import "errors"

var (
	ErrInvalidSlug       = errors.New("project slug is invalid")
	ErrMissingPool       = errors.New("database pool is required")
	ErrMissingRepository = errors.New("project repository is required")
	ErrProjectNotFound   = errors.New("project not found")
	ErrMissingService    = errors.New("project service is required")
)
