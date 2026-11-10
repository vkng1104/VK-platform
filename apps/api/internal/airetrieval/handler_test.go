package airetrieval

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpserver"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpx"
)

type retrievalServiceStub struct {
	result Result
	err    error
}

func (stub *retrievalServiceStub) Retrieve(_ context.Context, _ Request) (Result, error) {
	return stub.result, stub.err
}

func TestHandlerReturnsRankedRetrievalEnvelope(t *testing.T) {
	t.Parallel()
	handler := newTestHandler(t, &retrievalServiceStub{result: Result{
		Query: "architecture",
		Matches: []Match{{
			ChunkID: "project:vk-platform:architecture:0", Title: "VK Platform",
			Heading: "Architecture", Excerpt: "Go calls Python.",
			SourceURL: "/projects/vk-platform", Score: 0.912345,
		}},
	}})
	response := serveAIRequest(handler, `{"query":"architecture"}`)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
	var body retrievalEnvelope
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if body.Retrieval.Query != "architecture" || len(body.Retrieval.Matches) != 1 {
		t.Fatalf("response = %#v", body)
	}
	if body.Retrieval.Matches[0].Score != 0.9123 {
		t.Fatalf("score = %v, want 0.9123", body.Retrieval.Matches[0].Score)
	}
}

func TestHandlerRejectsUnknownJSONFields(t *testing.T) {
	t.Parallel()
	response := serveAIRequest(newTestHandler(t, &retrievalServiceStub{}), `{"query":"q","unknown":true}`)

	assertPublicAIError(t, response, http.StatusBadRequest, invalidAIQueryCode)
}

func TestHandlerMapsStableAIErrorCodes(t *testing.T) {
	t.Parallel()
	tests := []struct {
		name      string
		err       error
		status    int
		code      string
		retryable bool
	}{
		{name: "invalid", err: ErrInvalidQuery, status: http.StatusBadRequest, code: invalidAIQueryCode},
		{name: "busy", err: ErrBusy, status: http.StatusTooManyRequests, code: aiBusyCode, retryable: true},
		{name: "unavailable", err: ErrUnavailable, status: http.StatusServiceUnavailable, code: aiUnavailableCode, retryable: true},
		{name: "timeout", err: ErrTimeout, status: http.StatusGatewayTimeout, code: aiTimeoutCode, retryable: true},
		{name: "unexpected", err: errors.New("provider secret"), status: http.StatusInternalServerError, code: internalErrorCode, retryable: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			response := serveAIRequest(newTestHandler(t, &retrievalServiceStub{err: test.err}), `{"query":"q"}`)
			body := assertPublicAIError(t, response, test.status, test.code)
			if body.Retryable != test.retryable {
				t.Fatalf("retryable = %t, want %t", body.Retryable, test.retryable)
			}
			if strings.Contains(response.Body.String(), "provider secret") {
				t.Fatal("response leaked internal error")
			}
		})
	}
}

func newTestHandler(t *testing.T, service RetrievalService) *Handler {
	t.Helper()
	handler, err := NewHandler(service, nil)
	if err != nil {
		t.Fatalf("NewHandler() error = %v", err)
	}
	return handler
}

func serveAIRequest(handler *Handler, body string) *httptest.ResponseRecorder {
	request := httptest.NewRequest(http.MethodPost, "/api/v1/ai/retrievals", strings.NewReader(body))
	response := httptest.NewRecorder()
	httpserver.NewHandler(handler).ServeHTTP(response, request)
	return response
}

func assertPublicAIError(
	t *testing.T, response *httptest.ResponseRecorder, status int, code string,
) httpx.ErrorResponse {
	t.Helper()
	if response.Code != status {
		t.Fatalf("status = %d, want %d; body = %s", response.Code, status, response.Body.String())
	}
	var body httpx.ErrorResponse
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		t.Fatalf("decode error response: %v", err)
	}
	if body.Code != code || len(body.RequestID) != 32 {
		t.Fatalf("error response = %#v", body)
	}
	return body
}
