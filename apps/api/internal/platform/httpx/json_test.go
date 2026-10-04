package httpx_test

import (
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
