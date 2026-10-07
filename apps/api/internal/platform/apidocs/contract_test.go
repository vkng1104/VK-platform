package apidocs

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/getkin/kin-openapi/openapi3filter"
	legacyrouter "github.com/getkin/kin-openapi/routers/legacy"
	"github.com/vkng1104/VK-platform/apps/api/internal/emailverification"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpserver"
	"github.com/vkng1104/VK-platform/apps/api/internal/project"
)

const contractChallengeID = "123e4567-e89b-42d3-a456-426614174000"

func TestHandlerResponsesConformToOpenAPIContract(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.October, 7, 9, 0, 0, 0, time.UTC)
	repositoryURL := "https://github.com/vkng1104/VK-platform"
	tests := []struct {
		name         string
		method       string
		path         string
		body         string
		projects     *contractProjectService
		verification *contractEmailVerificationService
		wantStatus   int
	}{
		{
			name:       "health success",
			method:     http.MethodGet,
			path:       "/healthz",
			wantStatus: http.StatusOK,
		},
		{
			name:   "project list success",
			method: http.MethodGet,
			path:   "/api/v1/projects?featured=true",
			projects: &contractProjectService{projects: []project.Project{{
				Slug:          "vk-platform",
				Title:         "VK Platform",
				Summary:       "Systems portfolio",
				Period:        "2026 — Present",
				Role:          "Creator",
				Technologies:  []string{"Go", "Next.js"},
				Featured:      true,
				RepositoryURL: &repositoryURL,
			}}},
			wantStatus: http.StatusOK,
		},
		{
			name:       "project invalid filter",
			method:     http.MethodGet,
			path:       "/api/v1/projects?featured=yes",
			wantStatus: http.StatusBadRequest,
		},
		{
			name:       "project list internal error",
			method:     http.MethodGet,
			path:       "/api/v1/projects",
			projects:   &contractProjectService{listErr: errors.New("database unavailable")},
			wantStatus: http.StatusInternalServerError,
		},
		{
			name:   "project detail success",
			method: http.MethodGet,
			path:   "/api/v1/projects/vk-platform",
			projects: &contractProjectService{detail: project.Project{
				Slug:            "vk-platform",
				Title:           "VK Platform",
				Summary:         "Systems portfolio",
				Period:          "2026 — Present",
				Role:            "Creator",
				ContentMarkdown: "## Overview",
				Technologies:    []string{"Go"},
				Featured:        true,
			}},
			wantStatus: http.StatusOK,
		},
		{
			name:       "project not found",
			method:     http.MethodGet,
			path:       "/api/v1/projects/missing",
			projects:   &contractProjectService{detailErr: project.ErrProjectNotFound},
			wantStatus: http.StatusNotFound,
		},
		{
			name:   "email verification start success",
			method: http.MethodPost,
			path:   "/api/v1/email-verifications",
			body:   `{"email":"visitor@example.com","purpose":"restricted_resource_access"}`,
			verification: &contractEmailVerificationService{startResult: emailverification.StartResult{
				ID:              contractChallengeID,
				MaskedEmail:     "v*****r@example.com",
				ExpiresAt:       now.Add(5 * time.Minute),
				ResendNotBefore: now.Add(time.Minute),
			}},
			wantStatus: http.StatusAccepted,
		},
		{
			name:         "email verification invalid email",
			method:       http.MethodPost,
			path:         "/api/v1/email-verifications",
			body:         `{"email":"invalid","purpose":"restricted_resource_access"}`,
			verification: &contractEmailVerificationService{startErr: emailverification.ErrInvalidEmail},
			wantStatus:   http.StatusBadRequest,
		},
		{
			name:         "email verification rate limited",
			method:       http.MethodPost,
			path:         "/api/v1/email-verifications",
			body:         `{"email":"visitor@example.com","purpose":"restricted_resource_access"}`,
			verification: &contractEmailVerificationService{startErr: &emailverification.RateLimitError{RetryAt: time.Now().Add(time.Minute)}},
			wantStatus:   http.StatusTooManyRequests,
		},
		{
			name:         "email delivery unavailable",
			method:       http.MethodPost,
			path:         "/api/v1/email-verifications",
			body:         `{"email":"visitor@example.com","purpose":"restricted_resource_access"}`,
			verification: &contractEmailVerificationService{startErr: emailverification.ErrDeliveryUnavailable},
			wantStatus:   http.StatusServiceUnavailable,
		},
		{
			name:   "email verification success",
			method: http.MethodPost,
			path:   "/api/v1/email-verifications/" + contractChallengeID + "/verify",
			body:   `{"code":"123456"}`,
			verification: &contractEmailVerificationService{verifyResult: emailverification.VerificationResult{
				ID:         contractChallengeID,
				Purpose:    emailverification.PurposeRestrictedResourceAccess,
				VerifiedAt: now,
			}},
			wantStatus: http.StatusOK,
		},
		{
			name:         "email verification invalid code format",
			method:       http.MethodPost,
			path:         "/api/v1/email-verifications/" + contractChallengeID + "/verify",
			body:         `{"code":"123"}`,
			verification: &contractEmailVerificationService{verifyErr: emailverification.ErrInvalidCode},
			wantStatus:   http.StatusBadRequest,
		},
		{
			name:         "email verification expired code",
			method:       http.MethodPost,
			path:         "/api/v1/email-verifications/" + contractChallengeID + "/verify",
			body:         `{"code":"123456"}`,
			verification: &contractEmailVerificationService{verifyErr: emailverification.ErrInvalidOrExpiredCode},
			wantStatus:   http.StatusUnprocessableEntity,
		},
		{
			name:         "email verification internal error",
			method:       http.MethodPost,
			path:         "/api/v1/email-verifications/" + contractChallengeID + "/verify",
			body:         `{"code":"123456"}`,
			verification: &contractEmailVerificationService{verifyErr: errors.New("database unavailable")},
			wantStatus:   http.StatusInternalServerError,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			projectService := test.projects
			if projectService == nil {
				projectService = &contractProjectService{}
			}
			verificationService := test.verification
			if verificationService == nil {
				verificationService = &contractEmailVerificationService{}
			}

			handler := newContractHandler(t, projectService, verificationService)
			request := httptest.NewRequest(test.method, test.path, strings.NewReader(test.body))
			request.RemoteAddr = "192.0.2.1:43210"
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, request)

			if response.Code != test.wantStatus {
				t.Fatalf("status = %d, want %d; body = %s", response.Code, test.wantStatus, response.Body.String())
			}
			validateContractResponse(t, request, response)
		})
	}
}

func newContractHandler(
	t *testing.T,
	projectService project.ProjectService,
	verificationService emailverification.ApplicationService,
) http.Handler {
	t.Helper()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	projectHandler, err := project.NewHandler(projectService, logger)
	if err != nil {
		t.Fatalf("construct project handler: %v", err)
	}
	verificationHandler, err := emailverification.NewHandler(verificationService, logger)
	if err != nil {
		t.Fatalf("construct email verification handler: %v", err)
	}

	return httpserver.NewHandler(projectHandler, verificationHandler)
}

func validateContractResponse(
	t *testing.T,
	request *http.Request,
	response *httptest.ResponseRecorder,
) {
	t.Helper()

	document := loadOpenAPIDocument(t)
	router, err := legacyrouter.NewRouter(document)
	if err != nil {
		t.Fatalf("construct OpenAPI router: %v", err)
	}
	route, pathParameters, err := router.FindRoute(request)
	if err != nil {
		t.Fatalf("find OpenAPI route for %s %s: %v", request.Method, request.URL.Path, err)
	}

	input := &openapi3filter.ResponseValidationInput{
		RequestValidationInput: &openapi3filter.RequestValidationInput{
			Request:    request,
			PathParams: pathParameters,
			Route:      route,
		},
		Status: response.Code,
		Header: response.Header(),
	}
	input.SetBodyBytes(response.Body.Bytes())

	if err := openapi3filter.ValidateResponse(context.Background(), input); err != nil {
		t.Fatalf("response does not match OpenAPI contract: %v; body = %s", err, response.Body.String())
	}
}

type contractProjectService struct {
	projects  []project.Project
	listErr   error
	detail    project.Project
	detailErr error
}

func (service *contractProjectService) List(
	context.Context,
	project.ListFilter,
) ([]project.Project, error) {
	return service.projects, service.listErr
}

func (service *contractProjectService) GetBySlug(
	context.Context,
	string,
) (project.Project, error) {
	return service.detail, service.detailErr
}

type contractEmailVerificationService struct {
	startResult  emailverification.StartResult
	startErr     error
	verifyResult emailverification.VerificationResult
	verifyErr    error
}

func (service *contractEmailVerificationService) Start(
	context.Context,
	emailverification.StartRequest,
) (emailverification.StartResult, error) {
	return service.startResult, service.startErr
}

func (service *contractEmailVerificationService) Verify(
	context.Context,
	emailverification.VerifyRequest,
) (emailverification.VerificationResult, error) {
	return service.verifyResult, service.verifyErr
}
