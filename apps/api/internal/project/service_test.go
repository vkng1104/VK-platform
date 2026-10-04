package project_test

import (
	"context"
	"errors"
	"testing"

	"github.com/vkng1104/VK-platform/apps/api/internal/project"
)

func TestServiceListForwardsFeaturedFilter(t *testing.T) {
	t.Parallel()

	featured := true
	wantProjects := []project.Project{{Slug: "vk-platform"}}
	var receivedFilter project.ListFilter
	repository := &repositoryStub{
		listPublished: func(_ context.Context, filter project.ListFilter) ([]project.Project, error) {
			receivedFilter = filter
			return wantProjects, nil
		},
	}
	service := mustNewService(t, repository)

	gotProjects, err := service.List(context.Background(), project.ListFilter{Featured: &featured})
	if err != nil {
		t.Fatalf("list projects: %v", err)
	}

	if receivedFilter.Featured == nil || !*receivedFilter.Featured {
		t.Fatalf("expected featured=true filter, got %#v", receivedFilter.Featured)
	}

	if len(gotProjects) != 1 || gotProjects[0].Slug != wantProjects[0].Slug {
		t.Fatalf("expected projects %#v, got %#v", wantProjects, gotProjects)
	}
}

func TestServiceListPreservesRepositoryError(t *testing.T) {
	t.Parallel()

	repositoryError := errors.New("repository unavailable")
	repository := &repositoryStub{
		listPublished: func(context.Context, project.ListFilter) ([]project.Project, error) {
			return nil, repositoryError
		},
	}
	service := mustNewService(t, repository)

	_, err := service.List(context.Background(), project.ListFilter{})
	if !errors.Is(err, repositoryError) {
		t.Fatalf("expected wrapped repository error, got %v", err)
	}
}

func TestServiceGetBySlugRejectsInvalidSlugWithoutRepositoryCall(t *testing.T) {
	t.Parallel()

	repositoryCalled := false
	repository := &repositoryStub{
		findPublishedBySlug: func(context.Context, string) (project.Project, error) {
			repositoryCalled = true
			return project.Project{}, nil
		},
	}
	service := mustNewService(t, repository)

	_, err := service.GetBySlug(context.Background(), "Invalid Slug")
	if !errors.Is(err, project.ErrInvalidSlug) {
		t.Fatalf("expected ErrInvalidSlug, got %v", err)
	}

	if repositoryCalled {
		t.Fatal("expected invalid slug to be rejected before repository call")
	}
}

func TestServiceGetBySlugReturnsRepositoryProject(t *testing.T) {
	t.Parallel()

	wantProject := project.Project{Slug: "vk-platform", Title: "VK Platform"}
	repository := &repositoryStub{
		findPublishedBySlug: func(_ context.Context, slug string) (project.Project, error) {
			if slug != wantProject.Slug {
				t.Fatalf("expected slug %q, got %q", wantProject.Slug, slug)
			}

			return wantProject, nil
		},
	}
	service := mustNewService(t, repository)

	gotProject, err := service.GetBySlug(context.Background(), wantProject.Slug)
	if err != nil {
		t.Fatalf("get project: %v", err)
	}

	if gotProject.Slug != wantProject.Slug || gotProject.Title != wantProject.Title {
		t.Fatalf("expected project %#v, got %#v", wantProject, gotProject)
	}
}

func TestServiceGetBySlugPreservesRepositoryErrors(t *testing.T) {
	t.Parallel()

	testCases := []struct {
		name          string
		repositoryErr error
	}{
		{name: "not found", repositoryErr: project.ErrProjectNotFound},
		{name: "unexpected", repositoryErr: errors.New("database unavailable")},
	}

	for _, testCase := range testCases {
		t.Run(testCase.name, func(t *testing.T) {
			t.Parallel()

			repository := &repositoryStub{
				findPublishedBySlug: func(_ context.Context, slug string) (project.Project, error) {
					if slug != "vk-platform" {
						t.Fatalf("expected slug %q, got %q", "vk-platform", slug)
					}

					return project.Project{}, testCase.repositoryErr
				},
			}
			service := mustNewService(t, repository)

			_, err := service.GetBySlug(context.Background(), "vk-platform")
			if !errors.Is(err, testCase.repositoryErr) {
				t.Fatalf("expected wrapped repository error, got %v", err)
			}
		})
	}
}

func TestNewServiceRejectsMissingRepository(t *testing.T) {
	t.Parallel()

	_, err := project.NewService(nil)
	if !errors.Is(err, project.ErrMissingRepository) {
		t.Fatalf("expected ErrMissingRepository, got %v", err)
	}
}

type repositoryStub struct {
	listPublished       func(context.Context, project.ListFilter) ([]project.Project, error)
	findPublishedBySlug func(context.Context, string) (project.Project, error)
}

func (repository *repositoryStub) ListPublished(
	ctx context.Context,
	filter project.ListFilter,
) ([]project.Project, error) {
	if repository.listPublished == nil {
		return nil, errors.New("unexpected ListPublished call")
	}

	return repository.listPublished(ctx, filter)
}

func (repository *repositoryStub) FindPublishedBySlug(
	ctx context.Context,
	slug string,
) (project.Project, error) {
	if repository.findPublishedBySlug == nil {
		return project.Project{}, errors.New("unexpected FindPublishedBySlug call")
	}

	return repository.findPublishedBySlug(ctx, slug)
}

func mustNewService(t *testing.T, repository project.ProjectRepository) *project.Service {
	t.Helper()

	service, err := project.NewService(repository)
	if err != nil {
		t.Fatalf("construct project service: %v", err)
	}

	return service
}
