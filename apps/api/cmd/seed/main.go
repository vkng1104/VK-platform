package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

const (
	defaultSeedPath  = "seed/development.sql"
	seedTimeout      = 30 * time.Second
	seedCloseTimeout = 5 * time.Second
)

func main() {
	if err := run(); err != nil {
		slog.Error("database seed failed", "error", err)
		os.Exit(1)
	}
}

func run() error {
	databaseURL := strings.TrimSpace(os.Getenv("DATABASE_URL"))
	if databaseURL == "" {
		return errors.New("DATABASE_URL is required")
	}

	seedPath := strings.TrimSpace(os.Getenv("SEED_PATH"))
	if seedPath == "" {
		seedPath = defaultSeedPath
	}

	seedSQL, err := os.ReadFile(seedPath)
	if err != nil {
		return fmt.Errorf("read seed file: %w", err)
	}

	configuration, err := pgx.ParseConfig(databaseURL)
	if err != nil {
		return fmt.Errorf("parse database configuration: %w", err)
	}
	configuration.DefaultQueryExecMode = pgx.QueryExecModeSimpleProtocol

	ctx, cancel := context.WithTimeout(context.Background(), seedTimeout)
	defer cancel()

	connection, err := pgx.ConnectConfig(ctx, configuration)
	if err != nil {
		return fmt.Errorf("connect to seed database: %w", err)
	}
	defer func() {
		closeContext, cancelClose := context.WithTimeout(context.Background(), seedCloseTimeout)
		defer cancelClose()

		if err := connection.Close(closeContext); err != nil {
			slog.Warn("close seed database connection failed", "error", err)
		}
	}()

	if _, err := connection.Exec(ctx, string(seedSQL)); err != nil {
		return fmt.Errorf("execute seed file: %w", err)
	}

	slog.Info("database seed complete", "path", seedPath)
	return nil
}
