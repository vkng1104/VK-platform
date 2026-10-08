package config_test

import (
	"errors"
	"testing"

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
