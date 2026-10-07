package apidocs

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpserver"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpx"
)

func TestDocumentationRoutes(t *testing.T) {
	t.Parallel()

	handler := httpserver.NewHandler(NewHandler())

	t.Run("redirects slashless path", func(t *testing.T) {
		t.Parallel()

		response := serveDocumentationRequest(handler, http.MethodGet, docsPath)
		if response.Code != http.StatusPermanentRedirect {
			t.Fatalf("status = %d, want %d", response.Code, http.StatusPermanentRedirect)
		}
		if location := response.Header().Get("Location"); location != docsBasePath {
			t.Errorf("Location = %q, want %q", location, docsBasePath)
		}
		assertRequestID(t, response)
	})

	t.Run("serves embedded Swagger UI", func(t *testing.T) {
		t.Parallel()

		response := serveDocumentationRequest(handler, http.MethodGet, docsBasePath)
		if response.Code != http.StatusOK {
			t.Fatalf("status = %d, want %d", response.Code, http.StatusOK)
		}
		if contentType := response.Header().Get("Content-Type"); contentType != "text/html" {
			t.Errorf("Content-Type = %q, want text/html", contentType)
		}

		body := response.Body.String()
		for _, content := range []string{
			`"swaggerJsonUrl":"/api/docs/openapi.yaml"`,
			`supportedSubmitMethods: ["get"]`,
			`displayOperationId: true`,
			`displayRequestDuration: true`,
			`href="/api/docs/swagger-ui.css"`,
			`src="/api/docs/swagger-ui-bundle.js"`,
		} {
			if !strings.Contains(body, content) {
				t.Errorf("Swagger UI response does not contain %q", content)
			}
		}
		for _, externalAsset := range []string{`src="https://`, `href="https://`} {
			if strings.Contains(body, externalAsset) {
				t.Errorf("Swagger UI response contains external asset %q", externalAsset)
			}
		}
		assertRequestID(t, response)
	})

	t.Run("serves embedded OpenAPI contract", func(t *testing.T) {
		t.Parallel()

		response := serveDocumentationRequest(handler, http.MethodGet, specPath)
		if response.Code != http.StatusOK {
			t.Fatalf("status = %d, want %d", response.Code, http.StatusOK)
		}
		if contentType := response.Header().Get("Content-Type"); contentType != "application/yaml; charset=utf-8" {
			t.Errorf("Content-Type = %q", contentType)
		}
		if !bytes.Equal(response.Body.Bytes(), openAPISpec) {
			t.Fatal("served OpenAPI contract differs from embedded bytes")
		}
		assertRequestID(t, response)
	})

	t.Run("serves embedded UI asset", func(t *testing.T) {
		t.Parallel()

		response := serveDocumentationRequest(handler, http.MethodGet, docsBasePath+"swagger-ui.css")
		if response.Code != http.StatusOK {
			t.Fatalf("status = %d, want %d", response.Code, http.StatusOK)
		}
		if !strings.HasPrefix(response.Header().Get("Content-Type"), "text/css") {
			t.Errorf("Content-Type = %q, want text/css", response.Header().Get("Content-Type"))
		}
		assertRequestID(t, response)
	})
}

func TestDocumentationRoutesRejectUnsupportedOrUnknownRequests(t *testing.T) {
	t.Parallel()

	handler := httpserver.NewHandler(NewHandler())
	tests := []struct {
		name       string
		method     string
		path       string
		wantStatus int
	}{
		{name: "post docs", method: http.MethodPost, path: docsPath, wantStatus: http.StatusMethodNotAllowed},
		{name: "post spec", method: http.MethodPost, path: specPath, wantStatus: http.StatusMethodNotAllowed},
		{name: "unknown asset", method: http.MethodGet, path: docsBasePath + "missing.js", wantStatus: http.StatusNotFound},
		{name: "unrelated API path", method: http.MethodGet, path: "/api/v1/unknown", wantStatus: http.StatusNotFound},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			response := serveDocumentationRequest(handler, test.method, test.path)
			if response.Code != test.wantStatus {
				t.Fatalf("status = %d, want %d", response.Code, test.wantStatus)
			}
			assertRequestID(t, response)
		})
	}
}

func serveDocumentationRequest(handler http.Handler, method string, path string) *httptest.ResponseRecorder {
	request := httptest.NewRequest(method, path, nil)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response
}

func assertRequestID(t *testing.T, response *httptest.ResponseRecorder) {
	t.Helper()

	if requestID := response.Header().Get(httpx.RequestIDHeader); requestID == "" {
		t.Fatal("response has no X-Request-ID")
	}
}
