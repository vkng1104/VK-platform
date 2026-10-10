//go:build integration

package database_test

import (
	"context"
	"errors"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/database"
)

const databaseIntegrationTimeout = 10 * time.Second

func TestOpenConfiguresAndClosesSharedSQLPoolIntegration(t *testing.T) {
	settings := integrationSettings(t)
	ctx, cancel := context.WithTimeout(context.Background(), databaseIntegrationTimeout)
	defer cancel()

	pool, err := database.Open(ctx, settings)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	if pool.Stats().MaxOpenConnections != 10 {
		t.Fatalf("MaxOpenConnections = %d, want 10", pool.Stats().MaxOpenConnections)
	}
	if err := pool.PingContext(ctx); err != nil {
		t.Fatalf("PingContext() error = %v", err)
	}
	if err := pool.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
	if err := pool.PingContext(ctx); err == nil {
		t.Fatal("PingContext() after Close() error = nil")
	}
}

func TestOpenHonorsCanceledAndExpiredContextsIntegration(t *testing.T) {
	settings := integrationSettings(t)

	tests := []struct {
		name    string
		context func() (context.Context, context.CancelFunc)
		want    error
	}{
		{
			name: "canceled",
			context: func() (context.Context, context.CancelFunc) {
				ctx, cancel := context.WithCancel(context.Background())
				cancel()
				return ctx, func() {}
			},
			want: context.Canceled,
		},
		{
			name: "expired",
			context: func() (context.Context, context.CancelFunc) {
				return context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
			},
			want: context.DeadlineExceeded,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			ctx, cancel := test.context()
			defer cancel()

			pool, err := database.Open(ctx, settings)
			if pool != nil {
				_ = pool.Close()
			}
			if !errors.Is(err, test.want) {
				t.Fatalf("Open() error = %v, want %v", err, test.want)
			}
		})
	}
}

func integrationSettings(t *testing.T) database.Settings {
	t.Helper()

	settings := database.Settings{
		URL:      strings.TrimSpace(os.Getenv("TEST_DATABASE_URL")),
		Username: strings.TrimSpace(os.Getenv("TEST_DATABASE_USERNAME")),
		Password: strings.TrimSpace(os.Getenv("TEST_DATABASE_PASSWORD")),
	}
	if settings.URL == "" {
		t.Skip("TEST_DATABASE_URL is required for PostgreSQL integration tests")
	}
	if settings.Username == "" {
		t.Fatal("TEST_DATABASE_USERNAME is required for PostgreSQL integration tests")
	}
	if settings.Password == "" {
		t.Fatal("TEST_DATABASE_PASSWORD is required for PostgreSQL integration tests")
	}
	return settings
}
