package project

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"
)

const queryTimeout = 5 * time.Second

type PostgreSQLRepository struct {
	pool *pgxpool.Pool
}

func NewPostgreSQLRepository(pool *pgxpool.Pool) (*PostgreSQLRepository, error) {
	if pool == nil {
		return nil, ErrMissingPool
	}

	return &PostgreSQLRepository{pool: pool}, nil
}

func (repository *PostgreSQLRepository) ListPublished(
	ctx context.Context,
	filter ListFilter,
) ([]Project, error) {
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()

	rows, err := repository.pool.Query(ctx, `
		SELECT
			p.slug,
			p.title,
			p.summary,
			p.period,
			p.role,
			p.content_markdown,
			p.featured,
			p.repository_url,
			p.live_url,
			COALESCE(
				array_agg(t.name ORDER BY pt.display_order, t.name)
					FILTER (WHERE t.id IS NOT NULL),
				ARRAY[]::text[]
			) AS technologies
		FROM projects AS p
		LEFT JOIN project_technologies AS pt ON pt.project_id = p.id
		LEFT JOIN technologies AS t ON t.id = pt.technology_id
		WHERE p.published = TRUE
			AND ($1::boolean IS NULL OR p.featured = $1)
		GROUP BY p.id
		ORDER BY p.display_order, p.title, p.id
	`, filter.Featured)
	if err != nil {
		return nil, fmt.Errorf("query published projects: %w", err)
	}
	defer rows.Close()

	projects := make([]Project, 0)
	for rows.Next() {
		result, err := scanProject(rows)
		if err != nil {
			return nil, fmt.Errorf("scan published project: %w", err)
		}

		projects = append(projects, result)
	}

	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate published projects: %w", err)
	}

	return projects, nil
}

func (repository *PostgreSQLRepository) FindPublishedBySlug(
	ctx context.Context,
	slug string,
) (Project, error) {
	ctx, cancel := context.WithTimeout(ctx, queryTimeout)
	defer cancel()

	row := repository.pool.QueryRow(ctx, `
		SELECT
			p.slug,
			p.title,
			p.summary,
			p.period,
			p.role,
			p.content_markdown,
			p.featured,
			p.repository_url,
			p.live_url,
			COALESCE(
				array_agg(t.name ORDER BY pt.display_order, t.name)
					FILTER (WHERE t.id IS NOT NULL),
				ARRAY[]::text[]
			) AS technologies
		FROM projects AS p
		LEFT JOIN project_technologies AS pt ON pt.project_id = p.id
		LEFT JOIN technologies AS t ON t.id = pt.technology_id
		WHERE p.published = TRUE AND p.slug = $1
		GROUP BY p.id
	`, slug)

	result, err := scanProject(row)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return Project{}, ErrProjectNotFound
		}

		return Project{}, fmt.Errorf("query published project by slug: %w", err)
	}

	return result, nil
}

type projectScanner interface {
	Scan(destinations ...any) error
}

func scanProject(scanner projectScanner) (Project, error) {
	var record projectRecord

	err := scanner.Scan(
		&record.slug,
		&record.title,
		&record.summary,
		&record.period,
		&record.role,
		&record.contentMarkdown,
		&record.featured,
		&record.repositoryURL,
		&record.liveURL,
		&record.technologies,
	)
	if err != nil {
		return Project{}, err
	}

	return Project{
		Slug:            record.slug,
		Title:           record.title,
		Summary:         record.summary,
		Period:          record.period,
		Role:            record.role,
		ContentMarkdown: record.contentMarkdown,
		Technologies:    record.technologies,
		Featured:        record.featured,
		RepositoryURL:   nullableText(record.repositoryURL),
		LiveURL:         nullableText(record.liveURL),
	}, nil
}

type projectRecord struct {
	slug            string
	title           string
	summary         string
	period          string
	role            string
	contentMarkdown string
	technologies    []string
	featured        bool
	repositoryURL   pgtype.Text
	liveURL         pgtype.Text
}

func nullableText(value pgtype.Text) *string {
	if !value.Valid {
		return nil
	}

	return &value.String
}
