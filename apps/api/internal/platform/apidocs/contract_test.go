package apidocs

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/getkin/kin-openapi/openapi3filter"
	legacyrouter "github.com/getkin/kin-openapi/routers/legacy"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpserver"
	"github.com/vkng1104/VK-platform/apps/api/internal/project"
)

func TestHandlerResponsesConformToOpenAPIContract(t *testing.T) {
	t.Parallel()

	repositoryURL := "https://github.com/vkng1104/VK-platform"
	tests := []struct {
		name       string
		method     string
		path       string
		projects   *contractProjectService
		wantStatus int
	}{
		{name: "health success", method: http.MethodGet, path: "/healthz", wantStatus: http.StatusOK},
		{
			name:   "project list success",
			method: http.MethodGet,
			path:   "/api/v1/projects?featured=true",
			projects: &contractProjectService{projects: []project.Project{{
				Slug: "vk-platform", Title: "VK Platform", Summary: "Systems portfolio",
				Period: "2026 — Present", Role: "Creator", Technologies: []string{"Go", "Next.js"},
				Featured: true, RepositoryURL: &repositoryURL,
			}}},
			wantStatus: http.StatusOK,
		},
		{name: "project invalid filter", method: http.MethodGet, path: "/api/v1/projects?featured=yes", wantStatus: http.StatusBadRequest},
		{
			name: "project list internal error", method: http.MethodGet, path: "/api/v1/projects",
			projects:   &contractProjectService{listErr: errors.New("database unavailable")},
			wantStatus: http.StatusInternalServerError,
		},
		{
			name: "project detail success", method: http.MethodGet, path: "/api/v1/projects/vk-platform",
			projects: &contractProjectService{detail: project.Project{
				Slug: "vk-platform", Title: "VK Platform", Summary: "Systems portfolio",
				Period: "2026 — Present", Role: "Creator", ContentMarkdown: "## Overview",
				Technologies: []string{"Go"}, Featured: true,
			}},
			wantStatus: http.StatusOK,
		},
		{
			name: "project not found", method: http.MethodGet, path: "/api/v1/projects/missing",
			projects:   &contractProjectService{detailErr: project.ErrProjectNotFound},
			wantStatus: http.StatusNotFound,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			projectService := test.projects
			if projectService == nil {
				projectService = &contractProjectService{}
			}

			handler := newContractHandler(t, projectService)
			request := httptest.NewRequest(test.method, test.path, nil)
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, request)

			if response.Code != test.wantStatus {
				t.Fatalf("status = %d, want %d; body = %s", response.Code, test.wantStatus, response.Body.String())
			}
			validateContractResponse(t, request, response)
		})
	}
}

func newContractHandler(t *testing.T, projectService project.ProjectService) http.Handler {
	t.Helper()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	projectHandler, err := project.NewHandler(projectService, logger)
	if err != nil {
		t.Fatalf("construct project handler: %v", err)
	}

	return httpserver.NewHandler(projectHandler)
}

func validateContractResponse(t *testing.T, request *http.Request, response *httptest.ResponseRecorder) {
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
			Request: request, PathParams: pathParameters, Route: route,
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

func (service *contractProjectService) List(context.Context, project.ListFilter) ([]project.Project, error) {
	return service.projects, service.listErr
}

func (service *contractProjectService) GetBySlug(context.Context, string) (project.Project, error) {
	return service.detail, service.detailErr
}
