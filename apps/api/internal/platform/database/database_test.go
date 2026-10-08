package database_test

import (
	"errors"
	"testing"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/database"
)

func TestParseConnectionConfigAppliesSeparateCredentials(t *testing.T) {
	configuration, err := database.ParseConnectionConfig(database.Settings{
		URL:      "postgres://database.example:5433/vk_platform?sslmode=require",
		Username: "api-user",
		Password: "api-password",
	})
	if err != nil {
		t.Fatalf("ParseConnectionConfig() error = %v", err)
	}

	if configuration.Host != "database.example" {
		t.Errorf("Host = %q, want database.example", configuration.Host)
	}
	if configuration.Port != 5433 {
		t.Errorf("Port = %d, want 5433", configuration.Port)
	}
	if configuration.Database != "vk_platform" {
		t.Errorf("Database = %q, want vk_platform", configuration.Database)
	}
	if configuration.User != "api-user" {
		t.Errorf("User = %q, want api-user", configuration.User)
	}
	if configuration.Password != "api-password" {
		t.Error("Password did not come from the separate setting")
	}
	if configuration.TLSConfig == nil {
		t.Error("TLSConfig = nil, want sslmode=require to enable TLS")
	}
}

func TestParsePoolConfigAppliesSeparateCredentials(t *testing.T) {
	configuration, err := database.ParsePoolConfig(database.Settings{
		URL:      "postgresql://database.example/vk_platform",
		Username: "pool-user",
		Password: "pool-password",
	})
	if err != nil {
		t.Fatalf("ParsePoolConfig() error = %v", err)
	}

	if configuration.ConnConfig.User != "pool-user" {
		t.Errorf("User = %q, want pool-user", configuration.ConnConfig.User)
	}
	if configuration.ConnConfig.Password != "pool-password" {
		t.Error("Password did not come from the separate setting")
	}
}

func TestParsersRejectCredentialsInURL(t *testing.T) {
	settings := database.Settings{
		URL:      "postgres://embedded:secret@database.example/vk_platform",
		Username: "api-user",
		Password: "api-password",
	}

	tests := []struct {
		name  string
		parse func(database.Settings) error
	}{
		{
			name: "connection configuration",
			parse: func(settings database.Settings) error {
				_, err := database.ParseConnectionConfig(settings)
				return err
			},
		},
		{
			name: "pool configuration",
			parse: func(settings database.Settings) error {
				_, err := database.ParsePoolConfig(settings)
				return err
			},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if err := test.parse(settings); !errors.Is(err, database.ErrCredentialsInURL) {
				t.Fatalf("parse() error = %v, want %v", err, database.ErrCredentialsInURL)
			}
		})
	}
}

func TestParsersRejectUnsupportedURLScheme(t *testing.T) {
	settings := database.Settings{
		URL:      "mysql://database.example/vk_platform",
		Username: "api-user",
		Password: "api-password",
	}

	if _, err := database.ParseConnectionConfig(settings); err == nil {
		t.Fatal("ParseConnectionConfig() error = nil, want unsupported scheme error")
	}
	if _, err := database.ParsePoolConfig(settings); err == nil {
		t.Fatal("ParsePoolConfig() error = nil, want unsupported scheme error")
	}
}
