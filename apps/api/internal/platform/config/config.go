package config

import (
	"errors"
	"os"
	"strings"
)

const defaultAddress = ":8080"

const (
	emailProviderDisabled = "disabled"
	emailProviderGmail    = "gmail"
	minimumSecretLength   = 32
)

var (
	ErrIncompleteGmailConfiguration = errors.New("Gmail email configuration is incomplete")
	ErrMissingDatabaseURL           = errors.New("DATABASE_URL is required")
	ErrUnsupportedEmailProvider     = errors.New("EMAIL_PROVIDER must be disabled or gmail")
	ErrWeakEmailSecrets             = errors.New("email verification secrets must each contain at least 32 characters")
)

type Config struct {
	Address           string
	DatabaseURL       string
	EmailVerification EmailVerificationConfig
}

type EmailVerificationConfig struct {
	Enabled           bool
	Provider          string
	FromAddress       string
	GmailClientID     string
	GmailClientSecret string
	GmailRefreshToken string
	OTPPepper         string
	RateLimitSecret   string
}

func Load() (Config, error) {
	address := strings.TrimSpace(os.Getenv("API_ADDR"))
	if address == "" {
		address = defaultAddress
	}

	databaseURL := strings.TrimSpace(os.Getenv("DATABASE_URL"))
	if databaseURL == "" {
		return Config{}, ErrMissingDatabaseURL
	}

	emailVerification, err := loadEmailVerification()
	if err != nil {
		return Config{}, err
	}

	return Config{
		Address:           address,
		DatabaseURL:       databaseURL,
		EmailVerification: emailVerification,
	}, nil
}

func loadEmailVerification() (EmailVerificationConfig, error) {
	provider := strings.ToLower(strings.TrimSpace(os.Getenv("EMAIL_PROVIDER")))
	if provider == "" || provider == emailProviderDisabled {
		return EmailVerificationConfig{Provider: emailProviderDisabled}, nil
	}
	if provider != emailProviderGmail {
		return EmailVerificationConfig{}, ErrUnsupportedEmailProvider
	}

	configuration := EmailVerificationConfig{
		Enabled:           true,
		Provider:          provider,
		FromAddress:       strings.TrimSpace(os.Getenv("EMAIL_FROM_ADDRESS")),
		GmailClientID:     strings.TrimSpace(os.Getenv("GMAIL_CLIENT_ID")),
		GmailClientSecret: strings.TrimSpace(os.Getenv("GMAIL_CLIENT_SECRET")),
		GmailRefreshToken: strings.TrimSpace(os.Getenv("GMAIL_REFRESH_TOKEN")),
		OTPPepper:         strings.TrimSpace(os.Getenv("EMAIL_OTP_PEPPER")),
		RateLimitSecret:   strings.TrimSpace(os.Getenv("EMAIL_RATE_LIMIT_SECRET")),
	}

	if configuration.FromAddress == "" || configuration.GmailClientID == "" ||
		configuration.GmailClientSecret == "" || configuration.GmailRefreshToken == "" ||
		configuration.OTPPepper == "" || configuration.RateLimitSecret == "" {
		return EmailVerificationConfig{}, ErrIncompleteGmailConfiguration
	}
	if len(configuration.OTPPepper) < minimumSecretLength ||
		len(configuration.RateLimitSecret) < minimumSecretLength {
		return EmailVerificationConfig{}, ErrWeakEmailSecrets
	}

	return configuration, nil
}
