package config_test

import (
	"errors"
	"testing"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/config"
)

func TestLoadDisablesEmailVerificationByDefault(t *testing.T) {
	setBaseEnvironment(t)

	configuration, err := config.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if configuration.EmailVerification.Enabled {
		t.Fatal("email verification unexpectedly enabled")
	}
	if configuration.EmailVerification.Provider != "disabled" {
		t.Fatalf("provider = %q", configuration.EmailVerification.Provider)
	}
}

func TestLoadAcceptsCompleteGmailConfiguration(t *testing.T) {
	setBaseEnvironment(t)
	t.Setenv("EMAIL_PROVIDER", " GMAIL ")
	t.Setenv("EMAIL_FROM_ADDRESS", "owner@gmail.com")
	t.Setenv("GMAIL_CLIENT_ID", "client-id")
	t.Setenv("GMAIL_CLIENT_SECRET", "client-secret")
	t.Setenv("GMAIL_REFRESH_TOKEN", "refresh-token")
	t.Setenv("EMAIL_OTP_PEPPER", "otp-pepper-with-at-least-32-characters")
	t.Setenv("EMAIL_RATE_LIMIT_SECRET", "rate-limit-secret-with-at-least-32-characters")

	configuration, err := config.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	email := configuration.EmailVerification
	if !email.Enabled || email.Provider != "gmail" || email.FromAddress != "owner@gmail.com" ||
		email.GmailClientID != "client-id" || email.GmailClientSecret != "client-secret" ||
		email.GmailRefreshToken != "refresh-token" {
		t.Fatalf("email configuration = %#v", email)
	}
}

func TestLoadRejectsUnsupportedEmailProvider(t *testing.T) {
	setBaseEnvironment(t)
	t.Setenv("EMAIL_PROVIDER", "smtp")

	_, err := config.Load()
	if !errors.Is(err, config.ErrUnsupportedEmailProvider) {
		t.Fatalf("Load() error = %v", err)
	}
}

func TestLoadRejectsIncompleteGmailConfiguration(t *testing.T) {
	setBaseEnvironment(t)
	t.Setenv("EMAIL_PROVIDER", "gmail")
	t.Setenv("EMAIL_FROM_ADDRESS", "owner@gmail.com")

	_, err := config.Load()
	if !errors.Is(err, config.ErrIncompleteGmailConfiguration) {
		t.Fatalf("Load() error = %v", err)
	}
}

func TestLoadRejectsWeakEmailVerificationSecrets(t *testing.T) {
	setBaseEnvironment(t)
	t.Setenv("EMAIL_PROVIDER", "gmail")
	t.Setenv("EMAIL_FROM_ADDRESS", "owner@gmail.com")
	t.Setenv("GMAIL_CLIENT_ID", "client-id")
	t.Setenv("GMAIL_CLIENT_SECRET", "client-secret")
	t.Setenv("GMAIL_REFRESH_TOKEN", "refresh-token")
	t.Setenv("EMAIL_OTP_PEPPER", "short")
	t.Setenv("EMAIL_RATE_LIMIT_SECRET", "also-short")

	_, err := config.Load()
	if !errors.Is(err, config.ErrWeakEmailSecrets) {
		t.Fatalf("Load() error = %v", err)
	}
}

func setBaseEnvironment(t *testing.T) {
	t.Helper()

	t.Setenv("DATABASE_URL", "postgres://example")
	t.Setenv("API_ADDR", "")
	t.Setenv("EMAIL_PROVIDER", "")
	t.Setenv("EMAIL_FROM_ADDRESS", "")
	t.Setenv("GMAIL_CLIENT_ID", "")
	t.Setenv("GMAIL_CLIENT_SECRET", "")
	t.Setenv("GMAIL_REFRESH_TOKEN", "")
	t.Setenv("EMAIL_OTP_PEPPER", "")
	t.Setenv("EMAIL_RATE_LIMIT_SECRET", "")
}
