package config

import (
	"errors"
	"os"
	"strings"
)

const defaultAddress = ":8080"

var (
	ErrMissingDatabaseURL = errors.New("DATABASE_URL is required")
)

type Config struct {
	Address     string
	DatabaseURL string
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

	return Config{
		Address:     address,
		DatabaseURL: databaseURL,
	}, nil
}
