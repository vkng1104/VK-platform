package mail

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	stdmail "net/mail"
	"net/textproto"
	"strings"
	"time"

	"github.com/vkng1104/VK-platform/apps/api/internal/emailverification"
	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

const (
	gmailSendEndpoint = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send"
	gmailSendScope    = "https://www.googleapis.com/auth/gmail.send"
	gmailTimeout      = 8 * time.Second
)

var (
	ErrGmailAuthentication = errors.New("gmail authentication failed")
	ErrGmailConfiguration  = errors.New("gmail configuration is invalid")
	ErrGmailRateLimited    = errors.New("gmail delivery rate limited")
	ErrGmailRejected       = errors.New("gmail rejected the message")
	ErrGmailUnavailable    = errors.New("gmail is unavailable")
)

type GmailConfig struct {
	ClientID     string
	ClientSecret string
	RefreshToken string
	FromAddress  string
}

type GmailSender struct {
	client      *http.Client
	fromAddress string
	endpoint    string
	renderer    *verificationEmailRenderer
}

func NewGmailSender(configuration GmailConfig) (*GmailSender, error) {
	configuration = trimGmailConfig(configuration)
	if err := validateGmailConfig(configuration); err != nil {
		return nil, err
	}

	oauthConfiguration := &oauth2.Config{
		ClientID:     configuration.ClientID,
		ClientSecret: configuration.ClientSecret,
		Endpoint:     google.Endpoint,
		Scopes:       []string{gmailSendScope},
	}
	tokenSource := oauthConfiguration.TokenSource(context.Background(), &oauth2.Token{
		RefreshToken: configuration.RefreshToken,
	})
	client := oauth2.NewClient(context.Background(), tokenSource)
	client.Timeout = gmailTimeout
	renderer, err := newVerificationEmailRenderer()
	if err != nil {
		return nil, fmt.Errorf("%w: load embedded email templates: %v", ErrGmailConfiguration, err)
	}

	return &GmailSender{
		client:      client,
		fromAddress: configuration.FromAddress,
		endpoint:    gmailSendEndpoint,
		renderer:    renderer,
	}, nil
}

func (sender *GmailSender) SendVerificationCode(
	ctx context.Context,
	message emailverification.EmailMessage,
) error {
	if sender == nil || sender.client == nil || sender.renderer == nil {
		return ErrGmailConfiguration
	}

	rawMessage, err := buildVerificationMessage(sender.fromAddress, message, sender.renderer)
	if err != nil {
		return err
	}

	payload, err := json.Marshal(struct {
		Raw string `json:"raw"`
	}{Raw: base64.RawURLEncoding.EncodeToString(rawMessage)})
	if err != nil {
		return fmt.Errorf("marshal Gmail send request: %w", err)
	}

	request, err := http.NewRequestWithContext(ctx, http.MethodPost, sender.endpoint, bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("construct Gmail send request: %w", err)
	}
	request.Header.Set("Content-Type", "application/json")

	response, err := sender.client.Do(request)
	if err != nil {
		return fmt.Errorf("send Gmail request: %w", ErrGmailUnavailable)
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 64<<10))

	if response.StatusCode >= http.StatusOK && response.StatusCode < http.StatusMultipleChoices {
		return nil
	}

	switch response.StatusCode {
	case http.StatusUnauthorized:
		return fmt.Errorf("%w: status %d", ErrGmailAuthentication, response.StatusCode)
	case http.StatusTooManyRequests:
		return fmt.Errorf("%w: status %d", ErrGmailRateLimited, response.StatusCode)
	case http.StatusInternalServerError, http.StatusBadGateway, http.StatusServiceUnavailable,
		http.StatusGatewayTimeout:
		return fmt.Errorf("%w: status %d", ErrGmailUnavailable, response.StatusCode)
	default:
		return fmt.Errorf("%w: status %d", ErrGmailRejected, response.StatusCode)
	}
}

func buildVerificationMessage(
	fromAddress string,
	message emailverification.EmailMessage,
	renderer *verificationEmailRenderer,
) ([]byte, error) {
	from, err := exactMailbox(fromAddress)
	if err != nil {
		return nil, fmt.Errorf("validate Gmail sender: %w", ErrGmailConfiguration)
	}
	to, err := exactMailbox(message.To)
	if err != nil {
		return nil, errors.New("verification email recipient is invalid")
	}
	if !isSixDigitCode(message.Code) {
		return nil, errors.New("verification email code is invalid")
	}

	rendered, err := renderer.Render(verificationEmailTemplateData{
		Code:      message.Code,
		ExpiresAt: message.ExpiresAt.UTC().Format("15:04 MST"),
	})
	if err != nil {
		return nil, err
	}

	var body bytes.Buffer
	multipartWriter := multipart.NewWriter(&body)
	plainHeader := textproto.MIMEHeader{
		"Content-Type":              {"text/plain; charset=UTF-8"},
		"Content-Transfer-Encoding": {"8bit"},
	}
	plainPart, err := multipartWriter.CreatePart(plainHeader)
	if err != nil {
		return nil, fmt.Errorf("create plain-text verification email: %w", err)
	}
	if _, err := io.WriteString(plainPart, rendered.PlainText); err != nil {
		return nil, fmt.Errorf("write plain-text verification email: %w", err)
	}

	htmlHeader := textproto.MIMEHeader{
		"Content-Type":              {"text/html; charset=UTF-8"},
		"Content-Transfer-Encoding": {"8bit"},
	}
	htmlPart, err := multipartWriter.CreatePart(htmlHeader)
	if err != nil {
		return nil, fmt.Errorf("create HTML verification email: %w", err)
	}
	if _, err := io.WriteString(htmlPart, rendered.HTML); err != nil {
		return nil, fmt.Errorf("write HTML verification email: %w", err)
	}
	if err := multipartWriter.Close(); err != nil {
		return nil, fmt.Errorf("close verification email MIME body: %w", err)
	}

	var raw bytes.Buffer
	fmt.Fprintf(&raw, "From: %s\r\n", from.String())
	fmt.Fprintf(&raw, "To: %s\r\n", to.String())
	fmt.Fprintf(&raw, "Subject: %s\r\n", rendered.Subject)
	fmt.Fprint(&raw, "MIME-Version: 1.0\r\n")
	fmt.Fprintf(&raw, "Content-Type: multipart/alternative; boundary=%q\r\n", multipartWriter.Boundary())
	fmt.Fprint(&raw, "\r\n")
	raw.Write(body.Bytes())

	return raw.Bytes(), nil
}

func trimGmailConfig(configuration GmailConfig) GmailConfig {
	configuration.ClientID = strings.TrimSpace(configuration.ClientID)
	configuration.ClientSecret = strings.TrimSpace(configuration.ClientSecret)
	configuration.RefreshToken = strings.TrimSpace(configuration.RefreshToken)
	configuration.FromAddress = strings.TrimSpace(configuration.FromAddress)
	return configuration
}

func validateGmailConfig(configuration GmailConfig) error {
	if configuration.ClientID == "" || configuration.ClientSecret == "" ||
		configuration.RefreshToken == "" || configuration.FromAddress == "" {
		return ErrGmailConfiguration
	}
	if _, err := exactMailbox(configuration.FromAddress); err != nil {
		return ErrGmailConfiguration
	}

	return nil
}

func exactMailbox(value string) (*stdmail.Address, error) {
	if strings.ContainsAny(value, "\r\n") {
		return nil, errors.New("mailbox contains a line break")
	}

	address, err := stdmail.ParseAddress(value)
	if err != nil || address.Address != value {
		return nil, errors.New("mailbox is invalid")
	}

	return address, nil
}

func isSixDigitCode(value string) bool {
	if len(value) != 6 {
		return false
	}
	for _, character := range value {
		if character < '0' || character > '9' {
			return false
		}
	}

	return true
}
