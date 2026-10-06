package httpx_test

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpx"
)

type decodePayload struct {
	Name string `json:"name"`
}

func TestDecodeJSONAcceptsOneKnownObject(t *testing.T) {
	t.Parallel()

	request := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(`{"name":"VK Platform"}`))
	response := httptest.NewRecorder()
	var payload decodePayload

	if err := httpx.DecodeJSON(response, request, &payload); err != nil {
		t.Fatalf("decode valid JSON: %v", err)
	}

	if payload.Name != "VK Platform" {
		t.Fatalf("expected decoded name %q, got %q", "VK Platform", payload.Name)
	}
}

func TestDecodeJSONRejectsEmptyBody(t *testing.T) {
	t.Parallel()

	request := httptest.NewRequest(http.MethodPost, "/", http.NoBody)
	response := httptest.NewRecorder()

	err := httpx.DecodeJSON(response, request, &decodePayload{})
	if !errors.Is(err, httpx.ErrEmptyBody) {
		t.Fatalf("expected ErrEmptyBody, got %v", err)
	}
}

func TestDecodeJSONRejectsUnknownField(t *testing.T) {
	t.Parallel()

	request := httptest.NewRequest(
		http.MethodPost,
		"/",
		strings.NewReader(`{"name":"VK Platform","unexpected":true}`),
	)
	response := httptest.NewRecorder()

	err := httpx.DecodeJSON(response, request, &decodePayload{})
	if err == nil || !strings.Contains(err.Error(), `unknown field "unexpected"`) {
		t.Fatalf("expected unknown-field error, got %v", err)
	}
}

func TestDecodeJSONRejectsTrailingObject(t *testing.T) {
	t.Parallel()

	request := httptest.NewRequest(
		http.MethodPost,
		"/",
		strings.NewReader(`{"name":"first"} {"name":"second"}`),
	)
	response := httptest.NewRecorder()

	err := httpx.DecodeJSON(response, request, &decodePayload{})
	if !errors.Is(err, httpx.ErrMultipleObjects) {
		t.Fatalf("expected ErrMultipleObjects, got %v", err)
	}
}

func TestDecodeJSONRejectsBodyLargerThanLimit(t *testing.T) {
	t.Parallel()

	body := `{"name":"` + strings.Repeat("a", int(httpx.DefaultMaxBodyBytes)) + `"}`
	request := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(body))
	response := httptest.NewRecorder()

	err := httpx.DecodeJSON(response, request, &decodePayload{})
	var maximumBytesError *http.MaxBytesError
	if !errors.As(err, &maximumBytesError) {
		t.Fatalf("expected MaxBytesError, got %v", err)
	}

	if maximumBytesError.Limit != httpx.DefaultMaxBodyBytes {
		t.Fatalf(
			"expected body limit %d, got %d",
			httpx.DefaultMaxBodyBytes,
			maximumBytesError.Limit,
		)
	}
}

func TestWriteErrorIncludesActionableMetadata(t *testing.T) {
	t.Parallel()

	handler := httpx.WithRequestID(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		err := httpx.WriteError(writer, request, http.StatusBadRequest, httpx.PublicError{
			Code:      "INVALID_EMAIL",
			Message:   "Enter a valid email address.",
			Retryable: false,
			Fields: map[string][]string{
				"email": {"Enter a valid email address."},
			},
		})
		if err != nil {
			t.Errorf("write error response: %v", err)
		}
	}))
	request := httptest.NewRequest(http.MethodPost, "/", nil)
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusBadRequest)
	}
	var body httpx.ErrorResponse
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		t.Fatalf("decode error response: %v", err)
	}
	if body.Code != "INVALID_EMAIL" || body.Message != "Enter a valid email address." || body.Retryable {
		t.Fatalf("error response = %#v", body)
	}
	if len(body.Fields["email"]) != 1 || body.Fields["email"][0] != "Enter a valid email address." {
		t.Fatalf("fields = %#v", body.Fields)
	}
	if body.RequestID == "" || body.RequestID != response.Header().Get(httpx.RequestIDHeader) {
		t.Fatalf("request_id = %q, header = %q", body.RequestID, response.Header().Get(httpx.RequestIDHeader))
	}
}
