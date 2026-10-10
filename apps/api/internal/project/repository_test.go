package project_test

import (
	"errors"
	"testing"

	"github.com/vkng1104/VK-platform/apps/api/internal/project"
)

func TestNewEntRepositoryRejectsMissingClient(t *testing.T) {
	t.Parallel()

	_, err := project.NewEntRepository(nil)
	if !errors.Is(err, project.ErrMissingPersistenceClient) {
		t.Fatalf("NewEntRepository() error = %v, want %v", err, project.ErrMissingPersistenceClient)
	}
}
