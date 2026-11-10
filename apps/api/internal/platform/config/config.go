package config

import (
	"errors"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/database"
)

const defaultAddress = ":8080"

const (
	defaultAIRuntimeBaseURL       = "http://ai:8090"
	defaultAIRequestTimeout       = 5 * time.Second
	defaultAIMaxConcurrentRequest = 2
)

var (
	ErrMissingDatabaseURL      = errors.New("DATABASE_URL is required")
	ErrMissingDatabaseUsername = errors.New("DATABASE_USERNAME is required")
	ErrMissingDatabasePassword = errors.New("DATABASE_PASSWORD is required")
	ErrInvalidAIEnabled        = errors.New("AI_ENABLED must be true or false")
	ErrInvalidAIRuntimeBaseURL = errors.New("AI_RUNTIME_BASE_URL must be an absolute HTTP URL")
	ErrMissingAIInternalToken  = errors.New("AI_INTERNAL_TOKEN is required when AI is enabled")
	ErrInvalidAIRequestTimeout = errors.New("AI_REQUEST_TIMEOUT must be a positive Go duration")
	ErrInvalidAIMaxConcurrent  = errors.New("AI_MAX_CONCURRENT_REQUESTS must be a positive integer")
)

type AISettings struct {
	Enabled               bool
	RuntimeBaseURL        string
	InternalToken         string
	RequestTimeout        time.Duration
	MaxConcurrentRequests int
}

type Config struct {
	Address  string
	Database database.Settings
	AI       AISettings
}

func Load() (Config, error) {
	address := strings.TrimSpace(os.Getenv("API_ADDR"))
	if address == "" {
		address = defaultAddress
	}

	databaseSettings, err := LoadDatabase()
	if err != nil {
		return Config{}, err
	}
	aiSettings, err := LoadAI()
	if err != nil {
		return Config{}, err
	}

	return Config{
		Address:  address,
		Database: databaseSettings,
		AI:       aiSettings,
	}, nil
}

func LoadAI() (AISettings, error) {
	enabled := false
	if raw := strings.TrimSpace(os.Getenv("AI_ENABLED")); raw != "" {
		switch raw {
		case "true":
			enabled = true
		case "false":
			enabled = false
		default:
			return AISettings{}, ErrInvalidAIEnabled
		}
	}

	baseURL := strings.TrimSpace(os.Getenv("AI_RUNTIME_BASE_URL"))
	if baseURL == "" {
		baseURL = defaultAIRuntimeBaseURL
	}
	parsedURL, err := url.Parse(baseURL)
	if err != nil || (parsedURL.Scheme != "http" && parsedURL.Scheme != "https") || parsedURL.Host == "" {
		return AISettings{}, ErrInvalidAIRuntimeBaseURL
	}

	internalToken := strings.TrimSpace(os.Getenv("AI_INTERNAL_TOKEN"))
	if enabled && internalToken == "" {
		return AISettings{}, ErrMissingAIInternalToken
	}

	requestTimeout := defaultAIRequestTimeout
	if raw := strings.TrimSpace(os.Getenv("AI_REQUEST_TIMEOUT")); raw != "" {
		parsed, err := time.ParseDuration(raw)
		if err != nil || parsed <= 0 {
			return AISettings{}, ErrInvalidAIRequestTimeout
		}
		requestTimeout = parsed
	}

	maximumConcurrent := defaultAIMaxConcurrentRequest
	if raw := strings.TrimSpace(os.Getenv("AI_MAX_CONCURRENT_REQUESTS")); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed <= 0 {
			return AISettings{}, ErrInvalidAIMaxConcurrent
		}
		maximumConcurrent = parsed
	}

	return AISettings{
		Enabled:               enabled,
		RuntimeBaseURL:        strings.TrimRight(baseURL, "/"),
		InternalToken:         internalToken,
		RequestTimeout:        requestTimeout,
		MaxConcurrentRequests: maximumConcurrent,
	}, nil
}

func LoadDatabase() (database.Settings, error) {
	databaseURL := strings.TrimSpace(os.Getenv("DATABASE_URL"))
	if databaseURL == "" {
		return database.Settings{}, ErrMissingDatabaseURL
	}

	databaseUsername := strings.TrimSpace(os.Getenv("DATABASE_USERNAME"))
	if databaseUsername == "" {
		return database.Settings{}, ErrMissingDatabaseUsername
	}

	databasePassword := strings.TrimSpace(os.Getenv("DATABASE_PASSWORD"))
	if databasePassword == "" {
		return database.Settings{}, ErrMissingDatabasePassword
	}

	return database.Settings{
		URL:      databaseURL,
		Username: databaseUsername,
		Password: databasePassword,
	}, nil
}
