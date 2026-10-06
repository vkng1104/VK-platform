package mail

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	stdmail "net/mail"
	"strings"
	"testing"
	"time"

	"github.com/vkng1104/VK-platform/apps/api/internal/emailverification"
)

func TestBuildVerificationMessageCreatesPlainTextAndHTMLParts(t *testing.T) {
	t.Parallel()

	raw, err := buildVerificationMessage("owner@gmail.com", emailverification.EmailMessage{
		To:        "visitor@example.com",
		Code:      "123456",
		ExpiresAt: time.Date(2026, time.October, 6, 9, 5, 0, 0, time.UTC),
	}, newTestEmailRenderer(t))
	if err != nil {
		t.Fatalf("buildVerificationMessage() error = %v", err)
	}

	message, err := stdmail.ReadMessage(strings.NewReader(string(raw)))
	if err != nil {
		t.Fatalf("read MIME message: %v", err)
	}
	if message.Header.Get("From") != "<owner@gmail.com>" {
		t.Errorf("From = %q", message.Header.Get("From"))
	}
	if message.Header.Get("To") != "<visitor@example.com>" {
		t.Errorf("To = %q", message.Header.Get("To"))
	}
	if message.Header.Get("Subject") != "Your VK Platform verification code" {
		t.Errorf("Subject = %q", message.Header.Get("Subject"))
	}

	mediaType, parameters, err := mime.ParseMediaType(message.Header.Get("Content-Type"))
	if err != nil {
		t.Fatalf("parse Content-Type: %v", err)
	}
	if mediaType != "multipart/alternative" {
		t.Fatalf("media type = %q", mediaType)
	}

	reader := multipart.NewReader(message.Body, parameters["boundary"])
	parts := map[string]string{}
	for {
		part, err := reader.NextPart()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			t.Fatalf("read MIME part: %v", err)
		}
		body, err := io.ReadAll(part)
		if err != nil {
			t.Fatalf("read MIME part body: %v", err)
		}
		partType, _, err := mime.ParseMediaType(part.Header.Get("Content-Type"))
		if err != nil {
			t.Fatalf("parse MIME part type: %v", err)
		}
		parts[partType] = string(body)
	}

	for _, partType := range []string{"text/plain", "text/html"} {
		body, ok := parts[partType]
		if !ok {
			t.Fatalf("missing %s MIME part", partType)
		}
		if !strings.Contains(body, "123456") || !strings.Contains(body, "09:05 UTC") {
			t.Errorf("%s body = %q", partType, body)
		}
		if strings.Contains(body, "drive.google.com") {
			t.Fatalf("%s body contained a protected-resource URL", partType)
		}
	}
}

func TestGmailSenderPostsBase64URLMessage(t *testing.T) {
	t.Parallel()

	var receivedRaw []byte
	client := &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
		if request.Method != http.MethodPost || request.URL.Path != "/gmail/v1/users/me/messages/send" {
			t.Errorf("request = %s %s", request.Method, request.URL.Path)
		}
		if request.Header.Get("Content-Type") != "application/json" {
			t.Errorf("Content-Type = %q", request.Header.Get("Content-Type"))
		}

		var payload struct {
			Raw string `json:"raw"`
		}
		if err := json.NewDecoder(request.Body).Decode(&payload); err != nil {
			t.Errorf("decode request: %v", err)
		}
		decoded, err := base64.RawURLEncoding.DecodeString(payload.Raw)
		if err != nil {
			t.Errorf("decode raw message: %v", err)
		}
		receivedRaw = decoded
		return &http.Response{
			StatusCode: http.StatusOK,
			Body:       io.NopCloser(strings.NewReader(`{"id":"message-id"}`)),
			Header:     make(http.Header),
		}, nil
	})}

	sender := &GmailSender{
		client:      client,
		fromAddress: "owner@gmail.com",
		endpoint:    "https://gmail.example/gmail/v1/users/me/messages/send",
		renderer:    newTestEmailRenderer(t),
	}
	err := sender.SendVerificationCode(context.Background(), emailverification.EmailMessage{
		To:        "visitor@example.com",
		Code:      "123456",
		ExpiresAt: time.Date(2026, time.October, 6, 9, 5, 0, 0, time.UTC),
	})
	if err != nil {
		t.Fatalf("SendVerificationCode() error = %v", err)
	}
	if !strings.Contains(string(receivedRaw), "To: <visitor@example.com>") ||
		!strings.Contains(string(receivedRaw), "123456") {
		t.Fatalf("raw message = %q", string(receivedRaw))
	}
}

func TestGmailSenderMapsStatusWithoutResponseBody(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name    string
		status  int
		wantErr error
	}{
		{name: "authentication", status: http.StatusUnauthorized, wantErr: ErrGmailAuthentication},
		{name: "rate limit", status: http.StatusTooManyRequests, wantErr: ErrGmailRateLimited},
		{name: "unavailable", status: http.StatusServiceUnavailable, wantErr: ErrGmailUnavailable},
		{name: "rejected", status: http.StatusBadRequest, wantErr: ErrGmailRejected},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			client := &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
				return &http.Response{
					StatusCode: test.status,
					Body:       io.NopCloser(strings.NewReader("provider-body-secret")),
					Header:     make(http.Header),
				}, nil
			})}
			sender := &GmailSender{
				client:      client,
				fromAddress: "owner@gmail.com",
				endpoint:    "https://gmail.example/send",
				renderer:    newTestEmailRenderer(t),
			}
			err := sender.SendVerificationCode(context.Background(), validEmailMessage())
			if !errors.Is(err, test.wantErr) {
				t.Fatalf("SendVerificationCode() error = %v, want %v", err, test.wantErr)
			}
			if strings.Contains(err.Error(), "provider-body-secret") {
				t.Fatalf("error leaked provider body: %v", err)
			}
		})
	}
}

func TestGmailSenderHidesTransportErrorDetails(t *testing.T) {
	t.Parallel()

	client := &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
		return nil, errors.New("refresh_token=secret-value")
	})}
	sender := &GmailSender{
		client:      client,
		fromAddress: "owner@gmail.com",
		endpoint:    "https://example.com/send",
		renderer:    newTestEmailRenderer(t),
	}

	err := sender.SendVerificationCode(context.Background(), validEmailMessage())
	if !errors.Is(err, ErrGmailUnavailable) {
		t.Fatalf("SendVerificationCode() error = %v", err)
	}
	if strings.Contains(err.Error(), "secret-value") || strings.Contains(err.Error(), "refresh_token") {
		t.Fatalf("error leaked transport details: %v", err)
	}
}

func TestBuildVerificationMessageRejectsHeaderAndCodeInjection(t *testing.T) {
	t.Parallel()

	tests := []emailverification.EmailMessage{
		{To: "visitor@example.com\r\nBcc: attacker@example.com", Code: "123456", ExpiresAt: time.Now()},
		{To: "visitor@example.com", Code: "12\n456", ExpiresAt: time.Now()},
	}
	for _, message := range tests {
		if _, err := buildVerificationMessage("owner@gmail.com", message, newTestEmailRenderer(t)); err == nil {
			t.Fatalf("buildVerificationMessage(%#v) accepted injection", message)
		}
	}
}

func TestNewGmailSenderValidatesConfigurationWithoutNetworkAccess(t *testing.T) {
	t.Parallel()

	if _, err := NewGmailSender(GmailConfig{}); !errors.Is(err, ErrGmailConfiguration) {
		t.Fatalf("NewGmailSender(empty) error = %v", err)
	}
	if _, err := NewGmailSender(GmailConfig{
		ClientID:     "client-id",
		ClientSecret: "client-secret",
		RefreshToken: "refresh-token",
		FromAddress:  "owner@gmail.com",
	}); err != nil {
		t.Fatalf("NewGmailSender(valid) error = %v", err)
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (function roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return function(request)
}

func validEmailMessage() emailverification.EmailMessage {
	return emailverification.EmailMessage{
		To:        "visitor@example.com",
		Code:      "123456",
		ExpiresAt: time.Date(2026, time.October, 6, 9, 5, 0, 0, time.UTC),
	}
}

func newTestEmailRenderer(t *testing.T) *verificationEmailRenderer {
	t.Helper()

	renderer, err := newVerificationEmailRenderer()
	if err != nil {
		t.Fatalf("newVerificationEmailRenderer() error = %v", err)
	}
	return renderer
}
