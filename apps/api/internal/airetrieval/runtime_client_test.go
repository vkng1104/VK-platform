package airetrieval

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpx"
)

func TestRuntimeClientSendsPrivateContractAndMapsResponse(t *testing.T) {
	t.Parallel()
	var gotAuthorization string
	var gotRequestID string
	runtime := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		gotAuthorization = request.Header.Get("Authorization")
		gotRequestID = request.Header.Get(httpx.RequestIDHeader)
		if request.URL.Path != "/internal/v1/retrievals" {
			t.Errorf("path = %q", request.URL.Path)
		}
		_ = httpx.WriteJSON(writer, http.StatusOK, runtimeResponse{Matches: []runtimeMatch{{
			ChunkID: "project:vk-platform:architecture:0", Title: "VK Platform",
			Heading: "Architecture", Excerpt: "Go calls Python.",
			SourceURL: "/projects/vk-platform", Score: 0.91,
		}}})
	}))
	defer runtime.Close()

	client, err := NewRuntimeClient(RuntimeClientSettings{
		BaseURL: runtime.URL, Token: "private-token", HTTPClient: runtime.Client(),
	})
	if err != nil {
		t.Fatalf("NewRuntimeClient() error = %v", err)
	}
	var result Result
	var retrieveErr error
	wrapped := httpx.WithRequestID(http.HandlerFunc(func(_ http.ResponseWriter, request *http.Request) {
		result, retrieveErr = client.Retrieve(request.Context(), Request{Query: "architecture", TopK: 1})
	}))
	wrapped.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/", nil))

	if retrieveErr != nil {
		t.Fatalf("Retrieve() error = %v", retrieveErr)
	}
	if gotAuthorization != "Bearer private-token" {
		t.Fatalf("Authorization = %q", gotAuthorization)
	}
	if len(gotRequestID) != 32 {
		t.Fatalf("request ID = %q", gotRequestID)
	}
	if len(result.Matches) != 1 || result.Matches[0].ChunkID != "project:vk-platform:architecture:0" {
		t.Fatalf("result = %#v", result)
	}
}

func TestRuntimeClientMapsUnavailableAndRejectsMalformedMatches(t *testing.T) {
	t.Parallel()
	tests := []struct {
		name      string
		status    int
		body      string
		wantError error
	}{
		{name: "unavailable", status: http.StatusServiceUnavailable, body: `{}`, wantError: ErrUnavailable},
		{name: "bad source", status: http.StatusOK, body: `{"matches":[{"chunk_id":"id","title":"title","heading":"h","excerpt":"text","source_url":"https://internal","score":0.5}]}`, wantError: ErrUnavailable},
		{name: "unknown field", status: http.StatusOK, body: `{"matches":[],"provider":"secret"}`, wantError: ErrUnavailable},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			runtime := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
				writer.WriteHeader(test.status)
				_, _ = writer.Write([]byte(test.body))
			}))
			defer runtime.Close()
			client, err := NewRuntimeClient(RuntimeClientSettings{
				BaseURL: runtime.URL, Token: "private-token", HTTPClient: runtime.Client(),
			})
			if err != nil {
				t.Fatalf("NewRuntimeClient() error = %v", err)
			}
			_, err = client.Retrieve(context.Background(), Request{Query: "query", TopK: 1})
			if !errors.Is(err, test.wantError) {
				t.Fatalf("Retrieve() error = %v, want %v", err, test.wantError)
			}
		})
	}
}

func TestRuntimeClientBoundsResponseBody(t *testing.T) {
	t.Parallel()
	runtime := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		_, _ = writer.Write([]byte(strings.Repeat("x", int(maximumRuntimeResponseBytes)+1)))
	}))
	defer runtime.Close()
	client, err := NewRuntimeClient(RuntimeClientSettings{
		BaseURL: runtime.URL, Token: "private-token", HTTPClient: runtime.Client(),
	})
	if err != nil {
		t.Fatalf("NewRuntimeClient() error = %v", err)
	}
	_, err = client.Retrieve(context.Background(), Request{Query: "query", TopK: 1})
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("Retrieve() error = %v, want ErrUnavailable", err)
	}
}
