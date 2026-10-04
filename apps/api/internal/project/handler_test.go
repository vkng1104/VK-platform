package project_test

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpserver"
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
				`{"code":"INVALID_REQUEST","message":"The request is invalid."}`+"\n",
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
		body       string
	}{
		{
			name:       "invalid slug",
			serviceErr: fmt.Errorf("validate project: %w", project.ErrInvalidSlug),
			status:     http.StatusBadRequest,
			body:       `{"code":"INVALID_REQUEST","message":"The request is invalid."}` + "\n",
		},
		{
			name:       "not found",
			serviceErr: fmt.Errorf("get project: %w", project.ErrProjectNotFound),
			status:     http.StatusNotFound,
			body:       `{"code":"PROJECT_NOT_FOUND","message":"The requested project was not found."}` + "\n",
		},
		{
			name:       "internal failure",
			serviceErr: errors.New("password=secret database unavailable"),
			status:     http.StatusInternalServerError,
			body:       `{"code":"INTERNAL_ERROR","message":"An unexpected error occurred."}` + "\n",
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
			assertErrorResponse(t, response, testCase.status, testCase.body)

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
		`{"code":"INTERNAL_ERROR","message":"An unexpected error occurred."}`+"\n",
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
	body string,
) {
	t.Helper()

	if response.Code != status {
		t.Fatalf("expected status %d, got %d", status, response.Code)
	}

	if response.Header().Get("Content-Type") != "application/json" {
		t.Fatalf("expected application/json, got %q", response.Header().Get("Content-Type"))
	}

	if response.Body.String() != body {
		t.Fatalf("expected body %s, got %s", body, response.Body.String())
	}
}
