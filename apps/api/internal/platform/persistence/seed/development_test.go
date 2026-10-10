package seed_test

import (
	"errors"
	"testing"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence/seed"
)

func TestDevelopmentRejectsMissingClient(t *testing.T) {
	t.Parallel()

	if err := seed.Development(t.Context(), nil); !errors.Is(err, seed.ErrMissingClient) {
		t.Fatalf("Development() error = %v, want %v", err, seed.ErrMissingClient)
	}
}
