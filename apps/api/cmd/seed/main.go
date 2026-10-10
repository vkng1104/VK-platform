package main

import (
	"context"
	"log/slog"
	"os"
	"time"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/config"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/database"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence/seed"
)

const seedTimeout = 30 * time.Second

func main() {
	if err := run(); err != nil {
		slog.Error("database seed failed", "error", err)
		os.Exit(1)
	}
}

func run() error {
	databaseSettings, err := config.LoadDatabase()
	if err != nil {
		return err
	}

	ctx, cancel := context.WithTimeout(context.Background(), seedTimeout)
	defer cancel()

	sqlDatabase, err := database.Open(ctx, databaseSettings)
	if err != nil {
		return err
	}

	client, err := persistence.NewClient(sqlDatabase)
	if err != nil {
		_ = sqlDatabase.Close()
		return err
	}
	defer func() {
		if err := client.Close(); err != nil {
			slog.Warn("close seed database failed", "error", err)
		}
	}()

	if err := seed.Development(ctx, client); err != nil {
		return err
	}

	slog.Info("database seed complete")
	return nil
}
