package seed

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	persistenceent "github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence/ent"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence/ent/project"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence/ent/projecttechnology"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence/ent/technology"
)

var ErrMissingClient = errors.New("persistence client is required")

var developmentTechnologies = []struct {
	id       uuid.UUID
	slug     string
	name     string
	category string
}{
	{uuid.MustParse("00000000-0000-4000-8000-000000000101"), "next-js", "Next.js", "frontend"},
	{uuid.MustParse("00000000-0000-4000-8000-000000000102"), "react", "React", "frontend"},
	{uuid.MustParse("00000000-0000-4000-8000-000000000103"), "typescript", "TypeScript", "language"},
	{uuid.MustParse("00000000-0000-4000-8000-000000000104"), "go", "Go", "language"},
	{uuid.MustParse("00000000-0000-4000-8000-000000000105"), "tailwind-css", "Tailwind CSS", "frontend"},
}

const developmentProjectMarkdown = `## Why it exists

VK Platform treats the portfolio itself as an engineering project. The public site presents the work, while the repository provides a place to learn systems concepts by adding them only when a real feature needs them.

## Current architecture

The first architecture is deliberately small:

- a Next.js frontend for the portfolio and system views;
- a Go HTTP API for backend capabilities;
- a PostgreSQL-backed, read-only project catalog.

## What is implemented

The monorepo includes a responsive frontend, a live API status view, shared local verification commands, and isolated frontend and backend tests. The database-backed project showcase is the first content-driven feature built on that foundation.

## How it evolves

Future components must earn their place through a product or learning requirement. Observability, data pipelines, AI runtimes, and deployment infrastructure arrive as the platform gains behaviors that justify them.`

func Development(ctx context.Context, client *persistenceent.Client) error {
	if client == nil {
		return ErrMissingClient
	}

	transaction, err := client.Tx(ctx)
	if err != nil {
		return fmt.Errorf("begin development seed transaction: %w", err)
	}
	committed := false
	defer func() {
		if !committed {
			_ = transaction.Rollback()
		}
	}()

	now := time.Now().UTC()
	technologyIDs := make(map[string]uuid.UUID, len(developmentTechnologies))
	for _, seededTechnology := range developmentTechnologies {
		id, err := transaction.Technology.
			Create().
			SetID(seededTechnology.id).
			SetSlug(seededTechnology.slug).
			SetName(seededTechnology.name).
			SetCategory(seededTechnology.category).
			SetUpdatedAt(now).
			OnConflictColumns(technology.FieldSlug).
			UpdateName().
			UpdateCategory().
			UpdateUpdatedAt().
			ID(ctx)
		if err != nil {
			return fmt.Errorf("upsert development technology %q: %w", seededTechnology.slug, err)
		}
		technologyIDs[seededTechnology.slug] = id
	}

	projectID, err := transaction.Project.
		Create().
		SetID(uuid.MustParse("00000000-0000-4000-8000-000000000001")).
		SetSlug("vk-platform").
		SetTitle("VK Platform").
		SetSummary("A systems-focused engineering portfolio that grows alongside practical backend, infrastructure, data, and AI experiments.").
		SetPeriod("2026 — Present").
		SetRole("Creator and software engineer").
		SetContentMarkdown(developmentProjectMarkdown).
		SetFeatured(true).
		SetPublished(true).
		SetDisplayOrder(1).
		SetRepositoryURL("https://github.com/vkng1104/VK-platform").
		SetUpdatedAt(now).
		OnConflictColumns(project.FieldSlug).
		Update(func(update *persistenceent.ProjectUpsert) {
			update.
				UpdateTitle().
				UpdateSummary().
				UpdatePeriod().
				UpdateRole().
				UpdateContentMarkdown().
				UpdateFeatured().
				UpdatePublished().
				UpdateDisplayOrder().
				UpdateRepositoryURL().
				ClearLiveURL().
				UpdateUpdatedAt()
		}).
		ID(ctx)
	if err != nil {
		return fmt.Errorf("upsert development project: %w", err)
	}

	if _, err := transaction.ProjectTechnology.
		Delete().
		Where(projecttechnology.ProjectIDEQ(projectID)).
		Exec(ctx); err != nil {
		return fmt.Errorf("replace development project technologies: %w", err)
	}

	technologySlugs := []string{"next-js", "react", "typescript", "go", "tailwind-css"}
	links := make([]*persistenceent.ProjectTechnologyCreate, 0, len(technologySlugs))
	for displayOrder, slug := range technologySlugs {
		links = append(links, transaction.ProjectTechnology.
			Create().
			SetProjectID(projectID).
			SetTechnologyID(technologyIDs[slug]).
			SetDisplayOrder(displayOrder))
	}
	if _, err := transaction.ProjectTechnology.CreateBulk(links...).Save(ctx); err != nil {
		return fmt.Errorf("create development project technologies: %w", err)
	}

	if err := transaction.Commit(); err != nil {
		return fmt.Errorf("commit development seed transaction: %w", err)
	}
	committed = true
	return nil
}
