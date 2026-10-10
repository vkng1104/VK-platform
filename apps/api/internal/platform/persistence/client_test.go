package persistence_test

import (
	"errors"
	"testing"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence"
)

func TestNewClientRejectsMissingDatabase(t *testing.T) {
	t.Parallel()

	_, err := persistence.NewClient(nil)
	if !errors.Is(err, persistence.ErrMissingDatabase) {
		t.Fatalf("NewClient() error = %v, want %v", err, persistence.ErrMissingDatabase)
	}
}
