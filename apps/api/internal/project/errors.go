package project

import "errors"

var (
	ErrInvalidSlug              = errors.New("project slug is invalid")
	ErrMissingPersistenceClient = errors.New("persistence client is required")
	ErrMissingRepository        = errors.New("project repository is required")
	ErrProjectNotFound          = errors.New("project not found")
	ErrMissingService           = errors.New("project service is required")
)
