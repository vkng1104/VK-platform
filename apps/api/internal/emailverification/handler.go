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
	invalidRequestCode          = "INVALID_REQUEST"
	rateLimitedCode             = "EMAIL_VERIFICATION_RATE_LIMITED"
	deliveryUnavailableCode     = "EMAIL_DELIVERY_UNAVAILABLE"
	invalidOrExpiredCode        = "INVALID_OR_EXPIRED_CODE"
	verificationUnavailableCode = "EMAIL_VERIFICATION_UNAVAILABLE"
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
		handler.writePublicError(writer, http.StatusBadRequest, invalidRequestCode, "The request is invalid.")
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
		handler.logger.ErrorContext(request.Context(), "write email verification start response failed", "error", err)
	}
}

func (handler *Handler) verify(writer http.ResponseWriter, request *http.Request) {
	writer.Header().Set("Cache-Control", "no-store")
	request.Body = http.MaxBytesReader(writer, request.Body, verificationBodyLimit)

	var payload verifyRequestDTO
	if err := httpx.DecodeJSON(writer, request, &payload); err != nil {
		handler.writePublicError(writer, http.StatusBadRequest, invalidRequestCode, "The request is invalid.")
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
		handler.logger.ErrorContext(request.Context(), "write email verification response failed", "error", err)
	}
}

func (handler *Handler) handleStartError(
	writer http.ResponseWriter,
	request *http.Request,
	err error,
) {
	switch {
	case errors.Is(err, ErrInvalidEmail), errors.Is(err, ErrInvalidPurpose):
		handler.writePublicError(writer, http.StatusBadRequest, invalidRequestCode, "The request is invalid.")
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
			http.StatusTooManyRequests,
			rateLimitedCode,
			"Please wait before requesting another verification code.",
		)
	case errors.Is(err, ErrDeliveryUnavailable):
		handler.logger.WarnContext(
			request.Context(),
			"email verification delivery unavailable",
			"error", err,
			"path", request.URL.Path,
		)
		handler.writePublicError(
			writer,
			http.StatusServiceUnavailable,
			deliveryUnavailableCode,
			"The verification email could not be sent. Please try again later.",
		)
	default:
		handler.logUnexpected(request, "start email verification failed", err)
		handler.writePublicError(
			writer,
			http.StatusInternalServerError,
			verificationUnavailableCode,
			"Email verification is temporarily unavailable.",
		)
	}
}

func (handler *Handler) handleVerificationError(
	writer http.ResponseWriter,
	request *http.Request,
	err error,
) {
	switch {
	case errors.Is(err, ErrInvalidChallengeID), errors.Is(err, ErrInvalidCode):
		handler.writePublicError(writer, http.StatusBadRequest, invalidRequestCode, "The request is invalid.")
	case errors.Is(err, ErrInvalidOrExpiredCode):
		handler.writePublicError(
			writer,
			http.StatusUnprocessableEntity,
			invalidOrExpiredCode,
			"The verification code is invalid or expired.",
		)
	default:
		handler.logUnexpected(request, "verify email challenge failed", err)
		handler.writePublicError(
			writer,
			http.StatusInternalServerError,
			verificationUnavailableCode,
			"Email verification is temporarily unavailable.",
		)
	}
}

func (handler *Handler) logUnexpected(request *http.Request, message string, err error) {
	handler.logger.ErrorContext(
		request.Context(),
		message,
		"error", err,
		"path", request.URL.Path,
	)
}

func (handler *Handler) writePublicError(
	writer http.ResponseWriter,
	status int,
	code string,
	message string,
) {
	if err := httpx.WriteError(writer, status, code, message); err != nil {
		handler.logger.Error("write email verification error response failed", "error", err, "code", code)
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
