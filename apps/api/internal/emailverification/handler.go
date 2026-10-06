package emailverification

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpx"
)

const (
	handlerTimeout        = 10 * time.Second
	startBodyLimit        = int64(4 << 10)
	verificationBodyLimit = int64(1 << 10)
)

const (
	invalidRequestBodyCode  = "INVALID_REQUEST_BODY"
	invalidEmailCode        = "INVALID_EMAIL"
	invalidPurposeCode      = "INVALID_VERIFICATION_PURPOSE"
	rateLimitedCode         = "EMAIL_VERIFICATION_RATE_LIMITED"
	deliveryUnavailableCode = "EMAIL_DELIVERY_UNAVAILABLE"
	invalidChallengeIDCode  = "INVALID_VERIFICATION_ID"
	invalidCodeFormatCode   = "INVALID_CODE_FORMAT"
	invalidOrExpiredCode    = "INVALID_OR_EXPIRED_CODE"
	internalErrorCode       = "INTERNAL_ERROR"
)

type ApplicationService interface {
	Start(ctx context.Context, request StartRequest) (StartResult, error)
	Verify(ctx context.Context, request VerifyRequest) (VerificationResult, error)
}

type Handler struct {
	service ApplicationService
	logger  *slog.Logger
}

func NewHandler(service ApplicationService, logger *slog.Logger) (*Handler, error) {
	if service == nil {
		return nil, ErrMissingService
	}
	if logger == nil {
		logger = slog.Default()
	}

	return &Handler{service: service, logger: logger}, nil
}

func (handler *Handler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/email-verifications", handler.start)
	mux.HandleFunc("POST /api/v1/email-verifications/{id}/verify", handler.verify)
}

func (handler *Handler) start(writer http.ResponseWriter, request *http.Request) {
	writer.Header().Set("Cache-Control", "no-store")
	request.Body = http.MaxBytesReader(writer, request.Body, startBodyLimit)

	var payload startRequestDTO
	if err := httpx.DecodeJSON(writer, request, &payload); err != nil {
		handler.writePublicError(writer, request, http.StatusBadRequest, httpx.PublicError{
			Code:    invalidRequestBodyCode,
			Message: "The request body is invalid.",
		})
		return
	}

	ctx, cancel := context.WithTimeout(request.Context(), handlerTimeout)
	defer cancel()

	result, err := handler.service.Start(ctx, StartRequest{
		Email:            payload.Email,
		Purpose:          payload.Purpose,
		RequesterAddress: remoteHost(request.RemoteAddr),
	})
	if err != nil {
		handler.handleStartError(writer, request, err)
		return
	}

	if err := httpx.WriteJSON(writer, http.StatusAccepted, newChallengeEnvelope(result)); err != nil {
		handler.logger.ErrorContext(
			request.Context(),
			"write email verification start response failed",
			"error", err,
			"request_id", httpx.RequestID(request.Context()),
		)
	}
}

func (handler *Handler) verify(writer http.ResponseWriter, request *http.Request) {
	writer.Header().Set("Cache-Control", "no-store")
	request.Body = http.MaxBytesReader(writer, request.Body, verificationBodyLimit)

	var payload verifyRequestDTO
	if err := httpx.DecodeJSON(writer, request, &payload); err != nil {
		handler.writePublicError(writer, request, http.StatusBadRequest, httpx.PublicError{
			Code:    invalidRequestBodyCode,
			Message: "The request body is invalid.",
		})
		return
	}

	ctx, cancel := context.WithTimeout(request.Context(), handlerTimeout)
	defer cancel()

	result, err := handler.service.Verify(ctx, VerifyRequest{
		ID:   request.PathValue("id"),
		Code: payload.Code,
	})
	if err != nil {
		handler.handleVerificationError(writer, request, err)
		return
	}

	if err := httpx.WriteJSON(writer, http.StatusOK, newVerificationEnvelope(result)); err != nil {
		handler.logger.ErrorContext(
			request.Context(),
			"write email verification response failed",
			"error", err,
			"request_id", httpx.RequestID(request.Context()),
		)
	}
}

func (handler *Handler) handleStartError(
	writer http.ResponseWriter,
	request *http.Request,
	err error,
) {
	switch {
	case errors.Is(err, ErrInvalidEmail):
		handler.writePublicError(writer, request, http.StatusBadRequest, httpx.PublicError{
			Code:      invalidEmailCode,
			Message:   "Enter a valid email address.",
			Retryable: false,
			Fields: map[string][]string{
				"email": {"Enter a valid email address."},
			},
		})
	case errors.Is(err, ErrInvalidPurpose):
		handler.writePublicError(writer, request, http.StatusBadRequest, httpx.PublicError{
			Code:      invalidPurposeCode,
			Message:   "The requested verification purpose is not supported.",
			Retryable: false,
			Fields: map[string][]string{
				"purpose": {"Choose a supported verification purpose."},
			},
		})
	case errors.Is(err, ErrRateLimited):
		var rateLimitError *RateLimitError
		if errors.As(err, &rateLimitError) {
			retryAfter := int(time.Until(rateLimitError.RetryAt).Seconds())
			if retryAfter < 1 {
				retryAfter = 1
			}
			writer.Header().Set("Retry-After", strconv.Itoa(retryAfter))
		}
		handler.writePublicError(
			writer,
			request,
			http.StatusTooManyRequests,
			httpx.PublicError{
				Code:      rateLimitedCode,
				Message:   "Please wait before requesting another verification code.",
				Retryable: true,
			},
		)
	case errors.Is(err, ErrDeliveryUnavailable):
		handler.logger.WarnContext(
			request.Context(),
			"email verification delivery unavailable",
			"error", err,
			"path", request.URL.Path,
			"request_id", httpx.RequestID(request.Context()),
		)
		handler.writePublicError(
			writer,
			request,
			http.StatusServiceUnavailable,
			httpx.PublicError{
				Code:      deliveryUnavailableCode,
				Message:   "We could not send the verification email. Try again later.",
				Retryable: true,
			},
		)
	default:
		handler.logUnexpected(request, "start email verification failed", err)
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
}

func (handler *Handler) handleVerificationError(
	writer http.ResponseWriter,
	request *http.Request,
	err error,
) {
	switch {
	case errors.Is(err, ErrInvalidChallengeID):
		handler.writePublicError(writer, request, http.StatusBadRequest, httpx.PublicError{
			Code:      invalidChallengeIDCode,
			Message:   "The verification request is invalid. Request a new code and try again.",
			Retryable: false,
			Fields: map[string][]string{
				"id": {"Use the verification ID returned when the code was requested."},
			},
		})
	case errors.Is(err, ErrInvalidCode):
		handler.writePublicError(writer, request, http.StatusBadRequest, httpx.PublicError{
			Code:      invalidCodeFormatCode,
			Message:   "Enter the six-digit verification code.",
			Retryable: false,
			Fields: map[string][]string{
				"code": {"Enter exactly six digits."},
			},
		})
	case errors.Is(err, ErrInvalidOrExpiredCode):
		handler.writePublicError(
			writer,
			request,
			http.StatusUnprocessableEntity,
			httpx.PublicError{
				Code:      invalidOrExpiredCode,
				Message:   "The verification code is invalid or expired. Request a new code and try again.",
				Retryable: false,
			},
		)
	default:
		handler.logUnexpected(request, "verify email challenge failed", err)
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
}

func (handler *Handler) logUnexpected(request *http.Request, message string, err error) {
	handler.logger.ErrorContext(
		request.Context(),
		message,
		"error", err,
		"path", request.URL.Path,
		"request_id", httpx.RequestID(request.Context()),
	)
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
			"write email verification error response failed",
			"error", err,
			"code", publicError.Code,
			"request_id", httpx.RequestID(request.Context()),
		)
	}
}

func remoteHost(address string) string {
	address = strings.TrimSpace(address)
	host, _, err := net.SplitHostPort(address)
	if err == nil {
		return host
	}

	if net.ParseIP(address) != nil {
		return address
	}

	return fmt.Sprintf("unavailable:%x", sha256Sum(address))
}

func sha256Sum(value string) []byte {
	sum := sha256.Sum256([]byte(value))
	return sum[:]
}
