package httpserver_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpserver"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpx"
)

func TestHealthEndpoint(t *testing.T) {
	t.Parallel()

	request := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	response := httptest.NewRecorder()

	httpserver.NewHandler().ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("expected status %d, got %d", http.StatusOK, response.Code)
	}

	if contentType := response.Header().Get("Content-Type"); contentType != "application/json" {
		t.Errorf("expected application/json content type, got %q", contentType)
	}
	if response.Header().Get(httpx.RequestIDHeader) == "" {
		t.Fatal("expected every response to include X-Request-ID")
	}

	var body struct {
		Status  string `json:"status"`
		Service string `json:"service"`
	}

	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		t.Fatalf("decode response: %v", err)
	}

	if body.Status != "ok" {
		t.Errorf("expected healthy status, got %q", body.Status)
	}

	if body.Service != "api" {
		t.Errorf("expected api service, got %q", body.Service)
	}
}

func TestHandlerGeneratesServerOwnedRequestIDs(t *testing.T) {
	t.Parallel()

	handler := httpserver.NewHandler()
	firstRequest := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	firstRequest.Header.Set(httpx.RequestIDHeader, "client-controlled")
	firstResponse := httptest.NewRecorder()
	handler.ServeHTTP(firstResponse, firstRequest)

	secondRequest := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	secondResponse := httptest.NewRecorder()
	handler.ServeHTTP(secondResponse, secondRequest)

	firstID := firstResponse.Header().Get(httpx.RequestIDHeader)
	secondID := secondResponse.Header().Get(httpx.RequestIDHeader)
	if firstID == "" || secondID == "" || firstID == secondID {
		t.Fatalf("request IDs = %q and %q, want distinct non-empty values", firstID, secondID)
	}
	if firstID == "client-controlled" {
		t.Fatal("server trusted a client-controlled request ID")
	}
}

func TestHealthEndpointRejectsUnsupportedMethod(t *testing.T) {
	t.Parallel()

	request := httptest.NewRequest(http.MethodPost, "/healthz", nil)
	response := httptest.NewRecorder()

	httpserver.NewHandler().ServeHTTP(response, request)

	if response.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected status %d, got %d", http.StatusMethodNotAllowed, response.Code)
	}
}
