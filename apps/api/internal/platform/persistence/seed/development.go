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
	{uuid.MustParse("00000000-0000-4000-8000-000000000106"), "java", "Java", "language"},
	{uuid.MustParse("00000000-0000-4000-8000-000000000107"), "spring-boot", "Spring Boot", "backend"},
	{uuid.MustParse("00000000-0000-4000-8000-000000000108"), "spring-security", "Spring Security", "backend"},
	{uuid.MustParse("00000000-0000-4000-8000-000000000109"), "python", "Python", "language"},
	{uuid.MustParse("00000000-0000-4000-8000-000000000110"), "fastapi", "FastAPI", "backend"},
	{uuid.MustParse("00000000-0000-4000-8000-000000000111"), "postgresql", "PostgreSQL", "database"},
	{uuid.MustParse("00000000-0000-4000-8000-000000000112"), "docker", "Docker", "infrastructure"},
	{uuid.MustParse("00000000-0000-4000-8000-000000000113"), "github-actions", "GitHub Actions", "infrastructure"},
	{uuid.MustParse("00000000-0000-4000-8000-000000000114"), "ollama", "Ollama", "ai"},
}

const developmentProjectMarkdown = `## Why it exists

VK Platform treats the portfolio itself as an engineering project. The public site presents the work, while the repository provides a place to learn systems concepts by adding them only when a real feature needs them.

## Current architecture

The platform is a monorepo of independently deployable services, each owning its own data:

- a Next.js frontend for the portfolio, experience timeline, system status, and interactive labs;
- a Go HTTP API that serves the PostgreSQL-backed project catalog and the public AI search endpoint;
- a Java identity and access management service built with Spring Boot, Spring Security, and Spring Authorization Server, with its own PostgreSQL database;
- a private Python AI runtime built with FastAPI that embeds published content with a local Ollama model and searches it by cosine similarity.

Services communicate through versioned HTTP contracts, and no service reads another service's database.

## What is implemented

- A database-backed project showcase and an experience timeline.
- CV access protected by email one-time-password verification through the identity service.
- Passwordless sign-in challenges, an OAuth 2 authorization-code flow with PKCE, refresh-token rotation with reuse detection, and session revocation in the identity service.
- A vector search lab that shows the published content most similar to a visitor's question, with similarity scores and source links.
- A live system status view, Docker Compose environments, and GitHub Actions checks that run only for the areas a change touches.

## How it evolves

Future components must earn their place through a product or learning requirement. Next come grounded answers that cite the published portfolio, IAM-issued service tokens between internal services, and AWS infrastructure managed with Terraform.`

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

	technologySlugs := []string{
		"next-js",
		"react",
		"typescript",
		"tailwind-css",
		"go",
		"java",
		"spring-boot",
		"spring-security",
		"python",
		"fastapi",
		"postgresql",
		"docker",
		"github-actions",
		"ollama",
	}
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
