package database

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/url"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/stdlib"
)

var ErrCredentialsInURL = errors.New("database URL must not contain credentials")

const (
	maximumOpenConnections = 10
	maximumIdleConnections = 2
	connectionMaxLifetime  = 30 * time.Minute
	connectionMaxIdleTime  = 5 * time.Minute
)

type Settings struct {
	URL      string
	Username string
	Password string
}

func Open(ctx context.Context, settings Settings) (*sql.DB, error) {
	configuration, err := ParseConnectionConfig(settings)
	if err != nil {
		return nil, err
	}

	pool := stdlib.OpenDB(*configuration)
	pool.SetMaxOpenConns(maximumOpenConnections)
	pool.SetMaxIdleConns(maximumIdleConnections)
	pool.SetConnMaxLifetime(connectionMaxLifetime)
	pool.SetConnMaxIdleTime(connectionMaxIdleTime)

	if err := pool.PingContext(ctx); err != nil {
		_ = pool.Close()
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
