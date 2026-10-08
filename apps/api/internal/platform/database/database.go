package database

import (
	"context"
	"errors"
	"fmt"
	"net/url"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

var ErrCredentialsInURL = errors.New("database URL must not contain credentials")

type Settings struct {
	URL      string
	Username string
	Password string
}

func Open(ctx context.Context, settings Settings) (*pgxpool.Pool, error) {
	configuration, err := ParsePoolConfig(settings)
	if err != nil {
		return nil, err
	}

	pool, err := pgxpool.NewWithConfig(ctx, configuration)
	if err != nil {
		return nil, fmt.Errorf("open database pool: %w", err)
	}

	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("ping database: %w", err)
	}

	return pool, nil
}

func ParseConnectionConfig(settings Settings) (*pgx.ConnConfig, error) {
	if _, err := parseURL(settings.URL); err != nil {
		return nil, err
	}

	configuration, err := pgx.ParseConfig(settings.URL)
	if err != nil {
		return nil, fmt.Errorf("parse database configuration: %w", err)
	}
	configuration.User = settings.Username
	configuration.Password = settings.Password

	return configuration, nil
}

func ParsePoolConfig(settings Settings) (*pgxpool.Config, error) {
	if _, err := parseURL(settings.URL); err != nil {
		return nil, err
	}

	configuration, err := pgxpool.ParseConfig(settings.URL)
	if err != nil {
		return nil, fmt.Errorf("parse database configuration: %w", err)
	}
	configuration.ConnConfig.User = settings.Username
	configuration.ConnConfig.Password = settings.Password

	return configuration, nil
}

func parseURL(databaseURL string) (*url.URL, error) {
	parsedURL, err := url.Parse(databaseURL)
	if err != nil {
		return nil, fmt.Errorf("parse database URL: %w", err)
	}
	if parsedURL.Scheme != "postgres" && parsedURL.Scheme != "postgresql" {
		return nil, fmt.Errorf("parse database URL: unsupported scheme %q", parsedURL.Scheme)
	}
	if parsedURL.User != nil {
		return nil, ErrCredentialsInURL
	}

	return parsedURL, nil
}
