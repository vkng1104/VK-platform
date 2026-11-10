package config_test

import (
	"errors"
	"testing"
	"time"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/config"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/database"
)

func TestLoadUsesDefaultAddress(t *testing.T) {
	setDatabaseEnvironment(t)
	t.Setenv("API_ADDR", "")

	configuration, err := config.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if configuration.Address != ":8080" {
		t.Fatalf("Address = %q, want :8080", configuration.Address)
	}
	wantDatabase := database.Settings{
		URL:      "postgres://database.example/vk_platform",
		Username: "api-user",
		Password: "api-password",
	}
	assertDatabaseSettings(t, configuration.Database, wantDatabase)
}

func TestLoadTrimsConfiguredValues(t *testing.T) {
	t.Setenv("DATABASE_URL", " postgres://database.example/vk_platform ")
	t.Setenv("DATABASE_USERNAME", " api-user ")
	t.Setenv("DATABASE_PASSWORD", " api-password ")
	t.Setenv("API_ADDR", " :9090 ")

	configuration, err := config.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	wantDatabase := database.Settings{
		URL:      "postgres://database.example/vk_platform",
		Username: "api-user",
		Password: "api-password",
	}
	if configuration.Address != ":9090" {
		t.Fatalf("Address = %q, want :9090", configuration.Address)
	}
	assertDatabaseSettings(t, configuration.Database, wantDatabase)
}

func TestLoadDatabaseRequiresEveryValue(t *testing.T) {
	tests := []struct {
		name        string
		environment string
		wantError   error
	}{
		{
			name:        "URL",
			environment: "DATABASE_URL",
			wantError:   config.ErrMissingDatabaseURL,
		},
		{
			name:        "username",
			environment: "DATABASE_USERNAME",
			wantError:   config.ErrMissingDatabaseUsername,
		},
		{
			name:        "password",
			environment: "DATABASE_PASSWORD",
			wantError:   config.ErrMissingDatabasePassword,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			setDatabaseEnvironment(t)
			t.Setenv(test.environment, " ")

			_, err := config.LoadDatabase()
			if !errors.Is(err, test.wantError) {
				t.Fatalf("LoadDatabase() error = %v, want %v", err, test.wantError)
			}
		})
	}
}

func TestLoadAIUsesDisabledSafeDefaults(t *testing.T) {
	t.Setenv("AI_ENABLED", "")
	t.Setenv("AI_RUNTIME_BASE_URL", "")
	t.Setenv("AI_INTERNAL_TOKEN", "")
	t.Setenv("AI_REQUEST_TIMEOUT", "")
	t.Setenv("AI_MAX_CONCURRENT_REQUESTS", "")

	settings, err := config.LoadAI()
	if err != nil {
		t.Fatalf("LoadAI() error = %v", err)
	}
	if settings.Enabled || settings.RuntimeBaseURL != "http://ai:8090" ||
		settings.RequestTimeout != 5*time.Second || settings.MaxConcurrentRequests != 2 {
		t.Fatalf("AI settings = %#v", settings)
	}
}

func TestLoadAIRequiresTokenOnlyWhenEnabled(t *testing.T) {
	t.Setenv("AI_ENABLED", "true")
	t.Setenv("AI_INTERNAL_TOKEN", " ")

	_, err := config.LoadAI()
	if !errors.Is(err, config.ErrMissingAIInternalToken) {
		t.Fatalf("LoadAI() error = %v, want ErrMissingAIInternalToken", err)
	}
}

func TestLoadAIRejectsInvalidBounds(t *testing.T) {
	tests := []struct {
		name      string
		variable  string
		value     string
		wantError error
	}{
		{name: "enabled", variable: "AI_ENABLED", value: "yes", wantError: config.ErrInvalidAIEnabled},
		{name: "URL", variable: "AI_RUNTIME_BASE_URL", value: "file:///tmp/ai", wantError: config.ErrInvalidAIRuntimeBaseURL},
		{name: "timeout", variable: "AI_REQUEST_TIMEOUT", value: "0s", wantError: config.ErrInvalidAIRequestTimeout},
		{name: "capacity", variable: "AI_MAX_CONCURRENT_REQUESTS", value: "0", wantError: config.ErrInvalidAIMaxConcurrent},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Setenv("AI_ENABLED", "false")
			t.Setenv("AI_RUNTIME_BASE_URL", "http://ai:8090")
			t.Setenv("AI_REQUEST_TIMEOUT", "5s")
			t.Setenv("AI_MAX_CONCURRENT_REQUESTS", "2")
			t.Setenv(test.variable, test.value)

			_, err := config.LoadAI()
			if !errors.Is(err, test.wantError) {
				t.Fatalf("LoadAI() error = %v, want %v", err, test.wantError)
			}
		})
	}
}

func setDatabaseEnvironment(t *testing.T) {
	t.Helper()
	t.Setenv("DATABASE_URL", "postgres://database.example/vk_platform")
	t.Setenv("DATABASE_USERNAME", "api-user")
	t.Setenv("DATABASE_PASSWORD", "api-password")
}

func assertDatabaseSettings(t *testing.T, got database.Settings, want database.Settings) {
	t.Helper()
	if got.URL != want.URL {
		t.Errorf("database URL = %q, want %q", got.URL, want.URL)
	}
	if got.Username != want.Username {
		t.Errorf("database username = %q, want %q", got.Username, want.Username)
	}
	if got.Password != want.Password {
		t.Error("database password did not match")
	}
}
