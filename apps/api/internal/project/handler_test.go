package project_test

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpserver"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpx"
	"github.com/vkng1104/VK-platform/apps/api/internal/project"
)

func TestHandlerListsProjectsWithSnakeCaseResponse(t *testing.T) {
	t.Parallel()

	repositoryURL := "https://github.com/vkng1104/VK-platform"
	service := &serviceStub{
		list: func(_ context.Context, filter project.ListFilter) ([]project.Project, error) {
			if filter.Featured != nil {
				t.Fatalf("expected no featured filter, got %#v", filter.Featured)
			}

			return []project.Project{{
				Slug:          "vk-platform",
				Title:         "VK Platform",
				Summary:       "Systems portfolio",
				Period:        "2026 — Present",
				Role:          "Creator",
				Technologies:  []string{"Next.js", "Go"},
				Featured:      true,
				RepositoryURL: &repositoryURL,
			}}, nil
		},
	}

	response := serveProjectRequest(t, service, http.MethodGet, "/api/v1/projects")

	if response.Code != http.StatusOK {
		t.Fatalf("expected status %d, got %d", http.StatusOK, response.Code)
	}
	if response.Header().Get("Content-Type") != "application/json" {
		t.Fatalf("expected application/json, got %q", response.Header().Get("Content-Type"))
	}

	wantBody := `{"projects":[{"slug":"vk-platform","title":"VK Platform","summary":"Systems portfolio","period":"2026 — Present","role":"Creator","technologies":["Next.js","Go"],"featured":true,"repository_url":"https://github.com/vkng1104/VK-platform","live_url":null}]}` + "\n"
	if response.Body.String() != wantBody {
		t.Fatalf("expected body %s, got %s", wantBody, response.Body.String())
	}
}

func TestHandlerForwardsFeaturedFilter(t *testing.T) {
	t.Parallel()

	testCases := []struct {
		name  string
		value bool
	}{
		{name: "featured projects", value: true},
		{name: "non-featured projects", value: false},
	}

	for _, testCase := range testCases {
		t.Run(testCase.name, func(t *testing.T) {
			t.Parallel()

			service := &serviceStub{
				list: func(_ context.Context, filter project.ListFilter) ([]project.Project, error) {
					if filter.Featured == nil || *filter.Featured != testCase.value {
						t.Fatalf("expected featured=%t, got %#v", testCase.value, filter.Featured)
					}

					return []project.Project{}, nil
				},
			}

			response := serveProjectRequest(
				t,
				service,
				http.MethodGet,
				fmt.Sprintf("/api/v1/projects?featured=%t", testCase.value),
			)

			if response.Code != http.StatusOK {
				t.Fatalf("expected status %d, got %d", http.StatusOK, response.Code)
			}

			if response.Body.String() != "{\"projects\":[]}\n" {
				t.Fatalf("expected empty projects array, got %s", response.Body.String())
			}
		})
	}
}

func TestHandlerRejectsInvalidFeaturedQueries(t *testing.T) {
	t.Parallel()

	testCases := []struct {
		name string
		url  string
	}{
		{name: "unsupported value", url: "/api/v1/projects?featured=yes"},
		{name: "duplicate value", url: "/api/v1/projects?featured=true&featured=false"},
		{name: "unknown parameter", url: "/api/v1/projects?published=true"},
	}

	for _, testCase := range testCases {
		t.Run(testCase.name, func(t *testing.T) {
			t.Parallel()

			service := &serviceStub{
				list: func(context.Context, project.ListFilter) ([]project.Project, error) {
					t.Fatal("expected invalid query to be rejected before service call")
					return nil, nil
				},
			}

			response := serveProjectRequest(t, service, http.MethodGet, testCase.url)
			assertErrorResponse(
				t,
				response,
				http.StatusBadRequest,
				"INVALID_FEATURED_FILTER",
				"The featured filter must be either true or false.",
				false,
				map[string][]string{
					"featured": {"Use true or false and provide the filter at most once."},
				},
			)
		})
	}
}

func TestHandlerReturnsProjectDetail(t *testing.T) {
	t.Parallel()

	service := &serviceStub{
		getBySlug: func(_ context.Context, slug string) (project.Project, error) {
			if slug != "vk-platform" {
				t.Fatalf("expected slug %q, got %q", "vk-platform", slug)
			}

			return project.Project{
				Slug:            "vk-platform",
				Title:           "VK Platform",
				Summary:         "Systems portfolio",
				Period:          "2026 — Present",
				Role:            "Creator",
				ContentMarkdown: "## Overview",
				Technologies:    []string{"Go"},
				Featured:        true,
			}, nil
		},
	}

	response := serveProjectRequest(
		t,
		service,
		http.MethodGet,
		"/api/v1/projects/vk-platform",
	)

	if response.Code != http.StatusOK {
		t.Fatalf("expected status %d, got %d", http.StatusOK, response.Code)
	}

	wantBody := `{"project":{"slug":"vk-platform","title":"VK Platform","summary":"Systems portfolio","period":"2026 — Present","role":"Creator","content_markdown":"## Overview","technologies":["Go"],"featured":true,"repository_url":null,"live_url":null}}` + "\n"
	if response.Body.String() != wantBody {
		t.Fatalf("expected body %s, got %s", wantBody, response.Body.String())
	}
}

func TestHandlerMapsProjectErrors(t *testing.T) {
	t.Parallel()

	testCases := []struct {
		name       string
		serviceErr error
		status     int
		code       string
		message    string
		retryable  bool
	}{
		{
			name:       "invalid slug",
			serviceErr: fmt.Errorf("validate project: %w", project.ErrInvalidSlug),
			status:     http.StatusBadRequest,
			code:       "INVALID_PROJECT_SLUG",
			message:    "The project slug is invalid.",
		},
		{
			name:       "not found",
			serviceErr: fmt.Errorf("get project: %w", project.ErrProjectNotFound),
			status:     http.StatusNotFound,
			code:       "PROJECT_NOT_FOUND",
			message:    "The requested project was not found.",
		},
		{
			name:       "internal failure",
			serviceErr: errors.New("password=secret database unavailable"),
			status:     http.StatusInternalServerError,
			code:       "INTERNAL_ERROR",
			message:    "Something went wrong. Try again later or contact support with the request ID.",
			retryable:  true,
		},
	}

	for _, testCase := range testCases {
		t.Run(testCase.name, func(t *testing.T) {
			t.Parallel()

			service := &serviceStub{
				getBySlug: func(context.Context, string) (project.Project, error) {
					return project.Project{}, testCase.serviceErr
				},
			}

			response := serveProjectRequest(
				t,
				service,
				http.MethodGet,
				"/api/v1/projects/vk-platform",
			)
			assertErrorResponse(
				t,
				response,
				testCase.status,
				testCase.code,
				testCase.message,
				testCase.retryable,
				nil,
			)

			if strings.Contains(response.Body.String(), "password=secret") {
				t.Fatalf("response leaked internal error: %s", response.Body.String())
			}
		})
	}
}

func TestHandlerMapsListFailureWithoutLeakingCause(t *testing.T) {
	t.Parallel()

	service := &serviceStub{
		list: func(context.Context, project.ListFilter) ([]project.Project, error) {
			return nil, errors.New("postgres connection refused")
		},
	}

	response := serveProjectRequest(t, service, http.MethodGet, "/api/v1/projects")
	assertErrorResponse(
		t,
		response,
		http.StatusInternalServerError,
		"INTERNAL_ERROR",
		"Something went wrong. Try again later or contact support with the request ID.",
		true,
		nil,
	)

	if strings.Contains(response.Body.String(), "postgres") {
		t.Fatalf("response leaked internal error: %s", response.Body.String())
	}
}

func TestProjectRoutesRejectUnsupportedMethod(t *testing.T) {
	t.Parallel()

	response := serveProjectRequest(t, &serviceStub{}, http.MethodPost, "/api/v1/projects")
	if response.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected status %d, got %d", http.StatusMethodNotAllowed, response.Code)
	}
}

func TestNewHandlerRejectsMissingService(t *testing.T) {
	t.Parallel()

	_, err := project.NewHandler(nil, nil)
	if !errors.Is(err, project.ErrMissingService) {
		t.Fatalf("expected ErrMissingService, got %v", err)
	}
}

type serviceStub struct {
	list      func(context.Context, project.ListFilter) ([]project.Project, error)
	getBySlug func(context.Context, string) (project.Project, error)
}

func (service *serviceStub) List(
	ctx context.Context,
	filter project.ListFilter,
) ([]project.Project, error) {
	if service.list == nil {
		return nil, errors.New("unexpected List call")
	}

	return service.list(ctx, filter)
}

func (service *serviceStub) GetBySlug(
	ctx context.Context,
	slug string,
) (project.Project, error) {
	if service.getBySlug == nil {
		return project.Project{}, errors.New("unexpected GetBySlug call")
	}

	return service.getBySlug(ctx, slug)
}

func serveProjectRequest(
	t *testing.T,
	service project.ProjectService,
	method string,
	url string,
) *httptest.ResponseRecorder {
	t.Helper()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	handler, err := project.NewHandler(service, logger)
	if err != nil {
		t.Fatalf("construct project handler: %v", err)
	}

	request := httptest.NewRequest(method, url, nil)
	response := httptest.NewRecorder()
	httpserver.NewHandler(handler).ServeHTTP(response, request)
	return response
}

func assertErrorResponse(
	t *testing.T,
	response *httptest.ResponseRecorder,
	status int,
	code string,
	message string,
	retryable bool,
	fields map[string][]string,
) {
	t.Helper()

	if response.Code != status {
		t.Fatalf("expected status %d, got %d", status, response.Code)
	}

	if response.Header().Get("Content-Type") != "application/json" {
		t.Fatalf("expected application/json, got %q", response.Header().Get("Content-Type"))
	}

	var actual httpx.ErrorResponse
	if err := json.NewDecoder(response.Body).Decode(&actual); err != nil {
		t.Fatalf("decode error response: %v", err)
	}
	if actual.Code != code || actual.Message != message || actual.Retryable != retryable {
		t.Fatalf("error response = %#v", actual)
	}
	if !reflect.DeepEqual(actual.Fields, fields) {
		t.Fatalf("fields = %#v, want %#v", actual.Fields, fields)
	}
	if actual.RequestID == "" || actual.RequestID != response.Header().Get(httpx.RequestIDHeader) {
		t.Fatalf("request_id = %q, header = %q", actual.RequestID, response.Header().Get(httpx.RequestIDHeader))
	}
	decodedRequestID, err := hex.DecodeString(actual.RequestID)
	if err != nil || len(decodedRequestID) != 16 {
		t.Fatalf("request_id = %q, want 128-bit hexadecimal value", actual.RequestID)
	}
}
