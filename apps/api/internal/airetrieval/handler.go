package airetrieval

import (
	"context"
	"errors"
	"log/slog"
	"net/http"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpx"
)

const (
	invalidAIQueryCode = "INVALID_AI_QUERY"
	aiBusyCode         = "AI_BUSY"
	aiUnavailableCode  = "AI_UNAVAILABLE"
	aiTimeoutCode      = "AI_TIMEOUT"
	internalErrorCode  = "INTERNAL_ERROR"
)

type RetrievalService interface {
	Retrieve(ctx context.Context, request Request) (Result, error)
}

type Handler struct {
	service RetrievalService
	logger  *slog.Logger
}

func NewHandler(service RetrievalService, logger *slog.Logger) (*Handler, error) {
	if service == nil {
		return nil, errors.New("AI retrieval service is required")
	}
	if logger == nil {
		logger = slog.Default()
	}
	return &Handler{service: service, logger: logger}, nil
}

func (handler *Handler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/ai/retrievals", handler.retrieve)
}

func (handler *Handler) retrieve(writer http.ResponseWriter, request *http.Request) {
	var body retrievalRequest
	if err := httpx.DecodeJSON(writer, request, &body); err != nil {
		handler.writeError(writer, request, http.StatusBadRequest, httpx.PublicError{
			Code: invalidAIQueryCode, Message: "Provide one valid AI retrieval request.", Retryable: false,
		})
		return
	}
	result, err := handler.service.Retrieve(request.Context(), body.toServiceRequest())
	if err != nil {
		switch {
		case errors.Is(err, ErrInvalidQuery), errors.Is(err, ErrInvalidTopK):
			handler.writeError(writer, request, http.StatusBadRequest, httpx.PublicError{
				Code:      invalidAIQueryCode,
				Message:   "Use a query from 1 to 500 characters and top_k from 1 to 10.",
				Retryable: false,
			})
		case errors.Is(err, ErrBusy):
			handler.writeError(writer, request, http.StatusTooManyRequests, httpx.PublicError{
				Code: aiBusyCode, Message: "AI search is busy. Try again shortly.", Retryable: true,
			})
		case errors.Is(err, ErrUnavailable):
			handler.writeError(writer, request, http.StatusServiceUnavailable, httpx.PublicError{
				Code: aiUnavailableCode, Message: "AI search is not available right now.", Retryable: true,
			})
		case errors.Is(err, ErrTimeout):
			handler.writeError(writer, request, http.StatusGatewayTimeout, httpx.PublicError{
				Code: aiTimeoutCode, Message: "AI search took too long. Try again.", Retryable: true,
			})
		default:
			handler.logger.ErrorContext(request.Context(), "AI retrieval failed",
				"error", err, "request_id", httpx.RequestID(request.Context()))
			handler.writeError(writer, request, http.StatusInternalServerError, httpx.PublicError{
				Code:      internalErrorCode,
				Message:   "Something went wrong. Try again later or contact support with the request ID.",
				Retryable: true,
			})
		}
		return
	}
	if err := httpx.WriteJSON(writer, http.StatusOK, newRetrievalEnvelope(result)); err != nil {
		handler.logger.ErrorContext(request.Context(), "write AI retrieval response failed",
			"error", err, "request_id", httpx.RequestID(request.Context()))
	}
}

func (handler *Handler) writeError(
	writer http.ResponseWriter,
	request *http.Request,
	status int,
	publicError httpx.PublicError,
) {
	if err := httpx.WriteError(writer, request, status, publicError); err != nil {
		handler.logger.ErrorContext(request.Context(), "write AI public error failed",
			"error", err, "code", publicError.Code, "request_id", httpx.RequestID(request.Context()))
	}
}
