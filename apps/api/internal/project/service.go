package project

import (
	"context"
	"fmt"
	"regexp"
)

var projectSlugPattern = regexp.MustCompile(`^[a-z0-9]+(?:-[a-z0-9]+)*$`)

type ProjectRepository interface {
	ListPublished(ctx context.Context, filter ListFilter) ([]Project, error)
	FindPublishedBySlug(ctx context.Context, slug string) (Project, error)
}

type Service struct {
	repository ProjectRepository
}

func NewService(repository ProjectRepository) (*Service, error) {
	if repository == nil {
		return nil, ErrMissingRepository
	}

	return &Service{repository: repository}, nil
}

func (service *Service) List(ctx context.Context, filter ListFilter) ([]Project, error) {
	projects, err := service.repository.ListPublished(ctx, filter)
	if err != nil {
		return nil, fmt.Errorf("list projects: %w", err)
	}

	return projects, nil
}

func (service *Service) GetBySlug(ctx context.Context, slug string) (Project, error) {
	if !projectSlugPattern.MatchString(slug) {
		return Project{}, ErrInvalidSlug
	}

	result, err := service.repository.FindPublishedBySlug(ctx, slug)
	if err != nil {
		return Project{}, fmt.Errorf("get project %q: %w", slug, err)
	}

	return result, nil
}
