package emailverification_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/vkng1104/VK-platform/apps/api/internal/emailverification"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpserver"
)

func TestHandlerStartsEmailVerification(t *testing.T) {
	t.Parallel()

	expiresAt := time.Date(2026, time.October, 6, 9, 5, 0, 0, time.UTC)
	resendAfter := time.Date(2026, time.October, 6, 9, 1, 0, 0, time.UTC)
	var received emailverification.StartRequest
	service := &applicationServiceStub{
		start: func(_ context.Context, request emailverification.StartRequest) (emailverification.StartResult, error) {
			received = request
			return emailverification.StartResult{
				ID:              testChallengeID,
				MaskedEmail:     "v*****r@example.com",
				ExpiresAt:       expiresAt,
				ResendNotBefore: resendAfter,
			}, nil
		},
	}

	response := serveEmailVerificationRequest(
		t,
		service,
		http.MethodPost,
		"/api/v1/email-verifications",
		`{"email":"visitor@example.com","purpose":"restricted_resource_access"}`,
	)

	if response.Code != http.StatusAccepted {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusAccepted)
	}
	wantBody := `{"verification":{"id":"123e4567-e89b-42d3-a456-426614174000","masked_email":"v*****r@example.com","expires_at":"2026-10-06T09:05:00Z","resend_after":"2026-10-06T09:01:00Z"}}` + "\n"
	if response.Body.String() != wantBody {
		t.Fatalf("body = %s, want %s", response.Body.String(), wantBody)
	}
	if received.Email != "visitor@example.com" ||
		received.Purpose != emailverification.PurposeRestrictedResourceAccess ||
		received.RequesterAddress != "192.0.2.1" {
		t.Fatalf("Start() request = %#v", received)
	}
	if response.Header().Get("Cache-Control") != "no-store" {
		t.Errorf("Cache-Control = %q", response.Header().Get("Cache-Control"))
	}
	if strings.Contains(response.Body.String(), "visitor@example.com") {
		t.Fatal("response exposed the full email address")
	}
}

func TestHandlerRejectsInvalidStartBodiesBeforeService(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name string
		body string
	}{
		{name: "empty", body: ""},
		{name: "unknown field", body: `{"email":"visitor@example.com","purpose":"restricted_resource_access","extra":true}`},
		{name: "trailing object", body: `{"email":"visitor@example.com","purpose":"restricted_resource_access"}{}`},
		{name: "oversized", body: `{"email":"` + strings.Repeat("a", 5000) + `"}`},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			service := &applicationServiceStub{
				start: func(context.Context, emailverification.StartRequest) (emailverification.StartResult, error) {
					t.Fatal("Start() called for an invalid body")
					return emailverification.StartResult{}, nil
				},
			}
			response := serveEmailVerificationRequest(
				t,
				service,
				http.MethodPost,
				"/api/v1/email-verifications",
				test.body,
			)
			assertOTPError(t, response, http.StatusBadRequest, "INVALID_REQUEST", "The request is invalid.")
		})
	}
}

func TestHandlerMapsStartErrorsWithoutLeakingCauses(t *testing.T) {
	t.Parallel()

	retryAt := time.Now().Add(90 * time.Second)
	tests := []struct {
		name       string
		serviceErr error
		status     int
		code       string
		message    string
	}{
		{name: "invalid email", serviceErr: emailverification.ErrInvalidEmail, status: http.StatusBadRequest, code: "INVALID_REQUEST", message: "The request is invalid."},
		{name: "rate limited", serviceErr: &emailverification.RateLimitError{RetryAt: retryAt}, status: http.StatusTooManyRequests, code: "EMAIL_VERIFICATION_RATE_LIMITED", message: "Please wait before requesting another verification code."},
		{name: "delivery unavailable", serviceErr: errors.Join(emailverification.ErrDeliveryUnavailable, errors.New("oauth body secret-value")), status: http.StatusServiceUnavailable, code: "EMAIL_DELIVERY_UNAVAILABLE", message: "The verification email could not be sent. Please try again later."},
		{name: "internal", serviceErr: errors.New("postgres password=secret"), status: http.StatusInternalServerError, code: "EMAIL_VERIFICATION_UNAVAILABLE", message: "Email verification is temporarily unavailable."},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			service := &applicationServiceStub{
				start: func(context.Context, emailverification.StartRequest) (emailverification.StartResult, error) {
					return emailverification.StartResult{}, test.serviceErr
				},
			}
			response := serveEmailVerificationRequest(
				t,
				service,
				http.MethodPost,
				"/api/v1/email-verifications",
				`{"email":"visitor@example.com","purpose":"restricted_resource_access"}`,
			)
			assertOTPError(t, response, test.status, test.code, test.message)
			if strings.Contains(response.Body.String(), "secret") || strings.Contains(response.Body.String(), "postgres") || strings.Contains(response.Body.String(), "oauth") {
				t.Fatalf("response leaked internal cause: %s", response.Body.String())
			}
			if test.status == http.StatusTooManyRequests {
				retryAfter, err := strconv.Atoi(response.Header().Get("Retry-After"))
				if err != nil || retryAfter < 85 || retryAfter > 90 {
					t.Fatalf("Retry-After = %q", response.Header().Get("Retry-After"))
				}
			}
		})
	}
}

func TestHandlerVerifiesChallenge(t *testing.T) {
	t.Parallel()

	verifiedAt := time.Date(2026, time.October, 6, 9, 2, 0, 0, time.UTC)
	var received emailverification.VerifyRequest
	service := &applicationServiceStub{
		verify: func(_ context.Context, request emailverification.VerifyRequest) (emailverification.VerificationResult, error) {
			received = request
			return emailverification.VerificationResult{
				ID:         testChallengeID,
				Purpose:    emailverification.PurposeRestrictedResourceAccess,
				VerifiedAt: verifiedAt,
			}, nil
		},
	}

	response := serveEmailVerificationRequest(
		t,
		service,
		http.MethodPost,
		"/api/v1/email-verifications/"+testChallengeID+"/verify",
		`{"code":"123456"}`,
	)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusOK)
	}
	wantBody := `{"verification":{"id":"123e4567-e89b-42d3-a456-426614174000","purpose":"restricted_resource_access","verified_at":"2026-10-06T09:02:00Z"}}` + "\n"
	if response.Body.String() != wantBody {
		t.Fatalf("body = %s, want %s", response.Body.String(), wantBody)
	}
	if received.ID != testChallengeID || received.Code != testCode {
		t.Fatalf("Verify() request = %#v", received)
	}
}

func TestHandlerMapsVerificationErrors(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name       string
		serviceErr error
		status     int
		code       string
		message    string
	}{
		{name: "invalid transport value", serviceErr: emailverification.ErrInvalidCode, status: http.StatusBadRequest, code: "INVALID_REQUEST", message: "The request is invalid."},
		{name: "invalid or expired", serviceErr: emailverification.ErrInvalidOrExpiredCode, status: http.StatusUnprocessableEntity, code: "INVALID_OR_EXPIRED_CODE", message: "The verification code is invalid or expired."},
		{name: "internal", serviceErr: errors.New("database secret"), status: http.StatusInternalServerError, code: "EMAIL_VERIFICATION_UNAVAILABLE", message: "Email verification is temporarily unavailable."},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			service := &applicationServiceStub{
				verify: func(context.Context, emailverification.VerifyRequest) (emailverification.VerificationResult, error) {
					return emailverification.VerificationResult{}, test.serviceErr
				},
			}
			response := serveEmailVerificationRequest(
				t,
				service,
				http.MethodPost,
				"/api/v1/email-verifications/"+testChallengeID+"/verify",
				`{"code":"123456"}`,
			)
			assertOTPError(t, response, test.status, test.code, test.message)
			if strings.Contains(response.Body.String(), "database") || strings.Contains(response.Body.String(), "secret") {
				t.Fatalf("response leaked internal cause: %s", response.Body.String())
			}
		})
	}
}

func TestEmailVerificationRoutesRejectUnsupportedMethods(t *testing.T) {
	t.Parallel()

	response := serveEmailVerificationRequest(
		t,
		&applicationServiceStub{},
		http.MethodGet,
		"/api/v1/email-verifications",
		"",
	)
	if response.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusMethodNotAllowed)
	}
}

func TestNewEmailVerificationHandlerRejectsMissingService(t *testing.T) {
	t.Parallel()

	_, err := emailverification.NewHandler(nil, nil)
	if !errors.Is(err, emailverification.ErrMissingService) {
		t.Fatalf("NewHandler() error = %v", err)
	}
}

type applicationServiceStub struct {
	start  func(context.Context, emailverification.StartRequest) (emailverification.StartResult, error)
	verify func(context.Context, emailverification.VerifyRequest) (emailverification.VerificationResult, error)
}

func (service *applicationServiceStub) Start(
	ctx context.Context,
	request emailverification.StartRequest,
) (emailverification.StartResult, error) {
	if service.start == nil {
		return emailverification.StartResult{}, errors.New("unexpected Start call")
	}
	return service.start(ctx, request)
}

func (service *applicationServiceStub) Verify(
	ctx context.Context,
	request emailverification.VerifyRequest,
) (emailverification.VerificationResult, error) {
	if service.verify == nil {
		return emailverification.VerificationResult{}, errors.New("unexpected Verify call")
	}
	return service.verify(ctx, request)
}

func serveEmailVerificationRequest(
	t *testing.T,
	service emailverification.ApplicationService,
	method string,
	url string,
	body string,
) *httptest.ResponseRecorder {
	t.Helper()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	handler, err := emailverification.NewHandler(service, logger)
	if err != nil {
		t.Fatalf("NewHandler() error = %v", err)
	}

	request := httptest.NewRequest(method, url, strings.NewReader(body))
	request.RemoteAddr = "192.0.2.1:43210"
	response := httptest.NewRecorder()
	httpserver.NewHandler(handler).ServeHTTP(response, request)
	return response
}

func assertOTPError(
	t *testing.T,
	response *httptest.ResponseRecorder,
	status int,
	code string,
	message string,
) {
	t.Helper()

	if response.Code != status {
		t.Fatalf("status = %d, want %d", response.Code, status)
	}
	wantBody := `{"code":"` + code + `","message":"` + message + `"}` + "\n"
	if response.Body.String() != wantBody {
		t.Fatalf("body = %s, want %s", response.Body.String(), wantBody)
	}
	if response.Header().Get("Content-Type") != "application/json" {
		t.Fatalf("Content-Type = %q", response.Header().Get("Content-Type"))
	}
}
