package config_test

import (
	"errors"
	"testing"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/config"
)

func TestLoadUsesDefaultAddress(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://example")
	t.Setenv("API_ADDR", "")

	configuration, err := config.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if configuration.Address != ":8080" {
		t.Fatalf("Address = %q, want :8080", configuration.Address)
	}
	if configuration.DatabaseURL != "postgres://example" {
		t.Fatalf("DatabaseURL = %q", configuration.DatabaseURL)
	}
}

func TestLoadTrimsConfiguredValues(t *testing.T) {
	t.Setenv("DATABASE_URL", " postgres://example ")
	t.Setenv("API_ADDR", " :9090 ")

	configuration, err := config.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if configuration.Address != ":9090" || configuration.DatabaseURL != "postgres://example" {
		t.Fatalf("configuration = %#v", configuration)
	}
}

func TestLoadRequiresDatabaseURL(t *testing.T) {
	t.Setenv("DATABASE_URL", "")
	t.Setenv("API_ADDR", "")

	_, err := config.Load()
	if !errors.Is(err, config.ErrMissingDatabaseURL) {
		t.Fatalf("Load() error = %v", err)
	}
}
