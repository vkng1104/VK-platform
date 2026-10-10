package project

import (
	"context"
	"fmt"
	"time"

	persistenceent "github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence/ent"
	entproject "github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence/ent/project"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence/ent/projecttechnology"
)

const queryTimeout = 5 * time.Second

type EntRepository struct {
	client *persistenceent.Client
}

func NewEntRepository(client *persistenceent.Client) (*EntRepository, error) {
	if client == nil {
		return nil, ErrMissingPersistenceClient
	}

	return &EntRepository{client: client}, nil
}

func (repository *EntRepository) ListPublished(
	ctx context.Context,
	filter ListFilter,
) ([]Project, error) {
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()

	query := repository.client.Project.
		Query().
		Where(entproject.PublishedEQ(true)).
		Order(
			entproject.ByDisplayOrder(),
			entproject.ByTitle(),
			entproject.ByID(),
		).
		WithTechnologyLinks(func(query *persistenceent.ProjectTechnologyQuery) {
			query.
				Order(projecttechnology.ByDisplayOrder()).
				WithTechnology()
		})
	if filter.Featured != nil {
		query.Where(entproject.FeaturedEQ(*filter.Featured))
	}

	records, err := query.All(ctx)
	if err != nil {
		return nil, fmt.Errorf("query published projects: %w", err)
	}

	projects := make([]Project, 0, len(records))
	for _, record := range records {
		result, err := projectFromPersistence(record)
		if err != nil {
			return nil, fmt.Errorf("map published project: %w", err)
		}
		projects = append(projects, result)
	}

	return projects, nil
}

func (repository *EntRepository) FindPublishedBySlug(
	ctx context.Context,
	slug string,
) (Project, error) {
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()

	record, err := repository.client.Project.
		Query().
		Where(
			entproject.PublishedEQ(true),
			entproject.SlugEQ(slug),
		).
		WithTechnologyLinks(func(query *persistenceent.ProjectTechnologyQuery) {
			query.
				Order(projecttechnology.ByDisplayOrder()).
				WithTechnology()
		}).
		Only(ctx)
	if err != nil {
		if persistenceent.IsNotFound(err) {
			return Project{}, ErrProjectNotFound
		}
		return Project{}, fmt.Errorf("query published project by slug: %w", err)
	}

	result, err := projectFromPersistence(record)
	if err != nil {
		return Project{}, fmt.Errorf("map published project by slug: %w", err)
	}
	return result, nil
}

func projectFromPersistence(record *persistenceent.Project) (Project, error) {
	links, err := record.Edges.TechnologyLinksOrErr()
	if err != nil {
		return Project{}, err
	}

	technologies := make([]string, 0, len(links))
	for _, link := range links {
		technology, err := link.Edges.TechnologyOrErr()
		if err != nil {
			return Project{}, err
		}
		technologies = append(technologies, technology.Name)
	}

	return Project{
		Slug:            record.Slug,
		Title:           record.Title,
		Summary:         record.Summary,
		Period:          record.Period,
		Role:            record.Role,
		ContentMarkdown: record.ContentMarkdown,
		Technologies:    technologies,
		Featured:        record.Featured,
		RepositoryURL:   record.RepositoryURL,
		LiveURL:         record.LiveURL,
	}, nil
}
