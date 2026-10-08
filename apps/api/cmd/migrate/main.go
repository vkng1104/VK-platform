package main

import (
	"errors"
	"fmt"
	"log/slog"
	"os"
	"strings"

	"github.com/golang-migrate/migrate/v4"
	migratepostgres "github.com/golang-migrate/migrate/v4/database/postgres"
	_ "github.com/golang-migrate/migrate/v4/source/file"
	"github.com/jackc/pgx/v5/stdlib"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/config"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/database"
)

const defaultMigrationsURL = "file://migrations"

func main() {
	if err := run(os.Args[1:]); err != nil {
		slog.Error("database migration failed", "error", err)
		os.Exit(1)
	}
}

func run(arguments []string) error {
	if len(arguments) != 1 || (arguments[0] != "up" && arguments[0] != "down") {
		return errors.New("usage: go run ./cmd/migrate [up|down]")
	}

	databaseSettings, err := config.LoadDatabase()
	if err != nil {
		return err
	}
	connectionConfiguration, err := database.ParseConnectionConfig(databaseSettings)
	if err != nil {
		return err
	}

	migrationsURL := strings.TrimSpace(os.Getenv("MIGRATIONS_URL"))
	if migrationsURL == "" {
		migrationsURL = defaultMigrationsURL
	}

	sqlDatabase := stdlib.OpenDB(*connectionConfiguration)
	migrationDriver, err := migratepostgres.WithInstance(sqlDatabase, &migratepostgres.Config{})
	if err != nil {
		_ = sqlDatabase.Close()
		return fmt.Errorf("construct migration database: %w", err)
	}

	runner, err := migrate.NewWithDatabaseInstance(migrationsURL, "postgres", migrationDriver)
	if err != nil {
		_ = migrationDriver.Close()
		return fmt.Errorf("construct migration runner: %w", err)
	}
	defer func() {
		sourceErr, databaseErr := runner.Close()
		if sourceErr != nil || databaseErr != nil {
			slog.Warn("close migration runner failed", "source_error", sourceErr, "database_error", databaseErr)
		}
	}()

	switch arguments[0] {
	case "up":
		err = runner.Up()
	case "down":
		err = runner.Steps(-1)
	}

	if err != nil && !errors.Is(err, migrate.ErrNoChange) {
		return fmt.Errorf("migrate %s: %w", arguments[0], err)
	}

	slog.Info("database migration complete", "direction", arguments[0])
	return nil
}
