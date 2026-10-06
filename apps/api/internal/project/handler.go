package project

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"time"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpx"
)

const handlerTimeout = 5 * time.Second

const (
	invalidFeaturedFilterCode = "INVALID_FEATURED_FILTER"
	invalidProjectSlugCode    = "INVALID_PROJECT_SLUG"
	internalErrorCode         = "INTERNAL_ERROR"
	projectNotFoundCode       = "PROJECT_NOT_FOUND"
)

type ProjectService interface {
	List(ctx context.Context, filter ListFilter) ([]Project, error)
	GetBySlug(ctx context.Context, slug string) (Project, error)
}

type Handler struct {
	service ProjectService
	logger  *slog.Logger
}

func NewHandler(service ProjectService, logger *slog.Logger) (*Handler, error) {
	if service == nil {
		return nil, ErrMissingService
	}

	if logger == nil {
		logger = slog.Default()
	}

	return &Handler{service: service, logger: logger}, nil
}

func (handler *Handler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/v1/projects", handler.list)
	mux.HandleFunc("GET /api/v1/projects/{slug}", handler.getBySlug)
}

func (handler *Handler) list(writer http.ResponseWriter, request *http.Request) {
	featured, err := parseFeaturedFilter(request.URL.Query())
	if err != nil {
		handler.writePublicError(
			writer,
			request,
			http.StatusBadRequest,
			httpx.PublicError{
				Code:      invalidFeaturedFilterCode,
				Message:   "The featured filter must be either true or false.",
				Retryable: false,
				Fields: map[string][]string{
					"featured": {"Use true or false and provide the filter at most once."},
				},
			},
		)
		return
	}

	ctx, cancel := context.WithTimeout(request.Context(), handlerTimeout)
	defer cancel()

	projects, err := handler.service.List(ctx, ListFilter{Featured: featured})
	if err != nil {
		handler.logger.ErrorContext(
			request.Context(),
			"list projects failed",
			"error", err,
			"path", request.URL.Path,
			"request_id", httpx.RequestID(request.Context()),
		)
		handler.writePublicError(
			writer,
			request,
			http.StatusInternalServerError,
			httpx.PublicError{
				Code:      internalErrorCode,
				Message:   "Something went wrong. Try again later or contact support with the request ID.",
				Retryable: true,
			},
		)
		return
	}

	if err := httpx.WriteJSON(writer, http.StatusOK, newListResponse(projects)); err != nil {
		handler.logger.ErrorContext(
			request.Context(),
			"write project list response failed",
			"error", err,
			"request_id", httpx.RequestID(request.Context()),
		)
	}
}

func (handler *Handler) getBySlug(writer http.ResponseWriter, request *http.Request) {
	ctx, cancel := context.WithTimeout(request.Context(), handlerTimeout)
	defer cancel()

	result, err := handler.service.GetBySlug(ctx, request.PathValue("slug"))
	if err != nil {
		switch {
		case errors.Is(err, ErrInvalidSlug):
			handler.writePublicError(
				writer,
				request,
				http.StatusBadRequest,
				httpx.PublicError{
					Code:      invalidProjectSlugCode,
					Message:   "The project slug is invalid.",
					Retryable: false,
				},
			)
		case errors.Is(err, ErrProjectNotFound):
			handler.writePublicError(
				writer,
				request,
				http.StatusNotFound,
				httpx.PublicError{
					Code:      projectNotFoundCode,
					Message:   "The requested project was not found.",
					Retryable: false,
				},
			)
		default:
			handler.logger.ErrorContext(
				request.Context(),
				"get project failed",
				"error", err,
				"path", request.URL.Path,
				"request_id", httpx.RequestID(request.Context()),
			)
			handler.writePublicError(
				writer,
				request,
				http.StatusInternalServerError,
				httpx.PublicError{
					Code:      internalErrorCode,
					Message:   "Something went wrong. Try again later or contact support with the request ID.",
					Retryable: true,
				},
			)
		}

		return
	}

	if err := httpx.WriteJSON(writer, http.StatusOK, newDetailEnvelope(result)); err != nil {
		handler.logger.ErrorContext(
			request.Context(),
			"write project detail response failed",
			"error", err,
			"request_id", httpx.RequestID(request.Context()),
		)
	}
}

func (handler *Handler) writePublicError(
	writer http.ResponseWriter,
	request *http.Request,
	status int,
	publicError httpx.PublicError,
) {
	if err := httpx.WriteError(writer, request, status, publicError); err != nil {
		handler.logger.ErrorContext(
			request.Context(),
			"write public error response failed",
			"error", err,
			"code", publicError.Code,
			"request_id", httpx.RequestID(request.Context()),
		)
	}
}

func parseFeaturedFilter(query url.Values) (*bool, error) {
	if len(query) == 0 {
		return nil, nil
	}

	values, ok := query["featured"]
	if !ok || len(query) != 1 || len(values) != 1 {
		return nil, errors.New("only one featured query parameter is allowed")
	}

	switch values[0] {
	case "true":
		value := true
		return &value, nil
	case "false":
		value := false
		return &value, nil
	default:
		return nil, errors.New("featured query parameter must be true or false")
	}
}
