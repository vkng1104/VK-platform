package config

import (
	"errors"
	"os"
	"strings"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/database"
)

const defaultAddress = ":8080"

var (
	ErrMissingDatabaseURL      = errors.New("DATABASE_URL is required")
	ErrMissingDatabaseUsername = errors.New("DATABASE_USERNAME is required")
	ErrMissingDatabasePassword = errors.New("DATABASE_PASSWORD is required")
)

type Config struct {
	Address  string
	Database database.Settings
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

	return Config{
		Address:  address,
		Database: databaseSettings,
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
