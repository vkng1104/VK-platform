//go:build integration

package project_test

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/vkng1104/VK-platform/apps/api/internal/project"
)

const integrationOperationTimeout = 10 * time.Second

func TestPostgreSQLRepositoryListPublishedIntegration(t *testing.T) {
	pool := newProjectIntegrationDatabase(t, true)
	seedProjectCatalog(t, pool)

	repository, err := project.NewPostgreSQLRepository(pool)
	if err != nil {
		t.Fatalf("NewPostgreSQLRepository() error = %v", err)
	}

	alphaLiveURL := "https://alpha.example.com"
	zuluRepositoryURL := "https://github.com/example/zulu"
	allPublished := []project.Project{
		{
			Slug:            "alpha-project",
			Title:           "Alpha Project",
			Summary:         "Alpha summary",
			Period:          "2026",
			Role:            "Developer",
			ContentMarkdown: "# Alpha",
			Technologies:    []string{"TypeScript"},
			Featured:        true,
			LiveURL:         &alphaLiveURL,
		},
		{
			Slug:            "beta-project",
			Title:           "Beta Project",
			Summary:         "Beta summary",
			Period:          "2025",
			Role:            "Developer",
			ContentMarkdown: "# Beta",
			Technologies:    []string{},
			Featured:        false,
		},
		{
			Slug:            "zulu-project",
			Title:           "Zulu Project",
			Summary:         "Zulu summary",
			Period:          "2024",
			Role:            "Lead Developer",
			ContentMarkdown: "# Zulu",
			Technologies:    []string{"PostgreSQL", "Go"},
			Featured:        true,
			RepositoryURL:   &zuluRepositoryURL,
		},
	}

	tests := []struct {
		name     string
		featured *bool
		want     []project.Project
	}{
		{
			name: "all published projects ordered by display order then title",
			want: allPublished,
		},
		{
			name:     "featured published projects only",
			featured: boolPointer(true),
			want:     []project.Project{allPublished[0], allPublished[2]},
		},
		{
			name:     "non-featured published projects only",
			featured: boolPointer(false),
			want:     []project.Project{allPublished[1]},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
			defer cancel()

			got, err := repository.ListPublished(ctx, project.ListFilter{Featured: test.featured})
			if err != nil {
				t.Fatalf("ListPublished() error = %v", err)
			}

			if !reflect.DeepEqual(got, test.want) {
				t.Fatalf("ListPublished() = %#v, want %#v", got, test.want)
			}
		})
	}
}

func TestPostgreSQLRepositoryFindPublishedBySlugIntegration(t *testing.T) {
	pool := newProjectIntegrationDatabase(t, true)
	seedProjectCatalog(t, pool)

	repository, err := project.NewPostgreSQLRepository(pool)
	if err != nil {
		t.Fatalf("NewPostgreSQLRepository() error = %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	got, err := repository.FindPublishedBySlug(ctx, "zulu-project")
	if err != nil {
		t.Fatalf("FindPublishedBySlug() error = %v", err)
	}

	zuluRepositoryURL := "https://github.com/example/zulu"
	want := project.Project{
		Slug:            "zulu-project",
		Title:           "Zulu Project",
		Summary:         "Zulu summary",
		Period:          "2024",
		Role:            "Lead Developer",
		ContentMarkdown: "# Zulu",
		Technologies:    []string{"PostgreSQL", "Go"},
		Featured:        true,
		RepositoryURL:   &zuluRepositoryURL,
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("FindPublishedBySlug() = %#v, want %#v", got, want)
	}

	for _, slug := range []string{"draft-project", "missing-project"} {
		_, err := repository.FindPublishedBySlug(ctx, slug)
		if !errors.Is(err, project.ErrProjectNotFound) {
			t.Errorf("FindPublishedBySlug(%q) error = %v, want ErrProjectNotFound", slug, err)
		}
	}
}

func TestProjectCatalogStructuralConstraintsIntegration(t *testing.T) {
	pool := newProjectIntegrationDatabase(t, true)

	insertProject(t, pool, projectRow{
		slug:            "constraint-project",
		title:           "Constraint Project",
		summary:         "Valid project",
		period:          "2026",
		role:            "Developer",
		contentMarkdown: "# Valid",
		displayOrder:    0,
	})

	projectInsert := `
		INSERT INTO projects (
			slug,
			title,
			summary,
			period,
			role,
			content_markdown,
			display_order,
			repository_url,
			live_url
		)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
	`

	tests := []struct {
		name           string
		arguments      []any
		wantCode       string
		wantConstraint string
	}{
		{
			name: "display order must not be negative",
			arguments: []any{
				"negative-order", "Negative Order", "Summary", "2026", "Developer", "# Content", -1, nil, nil,
			},
			wantCode:       "23514",
			wantConstraint: "projects_display_order_non_negative_check",
		},
		{
			name: "slug must be unique",
			arguments: []any{
				"constraint-project", "Duplicate", "Summary", "2026", "Developer", "# Content", 1, nil, nil,
			},
			wantCode:       "23505",
			wantConstraint: "projects_slug_key",
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
			defer cancel()

			_, err := pool.Exec(ctx, projectInsert, test.arguments...)
			requirePostgresError(t, err, test.wantCode, test.wantConstraint)
		})
	}

	projectID := projectIDBySlug(t, pool, "constraint-project")
	goID := insertTechnology(t, pool, "go", "Go")
	postgresID := insertTechnology(t, pool, "postgresql", "PostgreSQL")
	insertProjectTechnology(t, pool, projectID, goID, 0)

	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	_, err := pool.Exec(ctx, `
		INSERT INTO project_technologies (project_id, technology_id, display_order)
		VALUES ($1, $2, $3)
	`, projectID, postgresID, 0)
	cancel()
	requirePostgresError(t, err, "23505", "project_technologies_project_display_order_unique")

	ctx, cancel = context.WithTimeout(context.Background(), integrationOperationTimeout)
	_, err = pool.Exec(ctx, `DELETE FROM technologies WHERE id = $1`, goID)
	cancel()
	requirePostgresError(t, err, "23503", "project_technologies_technology_fk")

	ctx, cancel = context.WithTimeout(context.Background(), integrationOperationTimeout)
	if _, err := pool.Exec(ctx, `DELETE FROM projects WHERE id = $1`, projectID); err != nil {
		cancel()
		t.Fatalf("delete project: %v", err)
	}
	cancel()

	ctx, cancel = context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()
	var relationships int
	if err := pool.QueryRow(ctx, `
		SELECT COUNT(*)
		FROM project_technologies
		WHERE project_id = $1
	`, projectID).Scan(&relationships); err != nil {
		t.Fatalf("count project technologies after project deletion: %v", err)
	}
	if relationships != 0 {
		t.Fatalf("project technology relationship count = %d, want 0 after cascade", relationships)
	}
}

func TestProjectCatalogMigrationIsReversibleIntegration(t *testing.T) {
	pool := newProjectIntegrationDatabase(t, false)

	executeMigration(t, pool, "000001_create_project_catalog.up.sql")
	assertTablesExist(t, pool, true)

	executeMigration(t, pool, "000001_create_project_catalog.down.sql")
	assertTablesExist(t, pool, false)

	executeMigration(t, pool, "000001_create_project_catalog.up.sql")
	assertTablesExist(t, pool, true)
}

func TestDevelopmentSeedIsIdempotentIntegration(t *testing.T) {
	pool := newProjectIntegrationDatabase(t, true)

	executeSeed(t, pool, "development.sql")
	executeSeed(t, pool, "development.sql")

	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	for table, want := range map[string]int{
		"projects":             1,
		"technologies":         5,
		"project_technologies": 5,
	} {
		var got int
		if err := pool.QueryRow(ctx, "SELECT COUNT(*) FROM "+pgx.Identifier{table}.Sanitize()).Scan(&got); err != nil {
			t.Fatalf("count seeded %s: %v", table, err)
		}
		if got != want {
			t.Errorf("seeded %s count = %d, want %d", table, got, want)
		}
	}

	repository, err := project.NewPostgreSQLRepository(pool)
	if err != nil {
		t.Fatalf("NewPostgreSQLRepository() error = %v", err)
	}

	seededProject, err := repository.FindPublishedBySlug(ctx, "vk-platform")
	if err != nil {
		t.Fatalf("find seeded project: %v", err)
	}
	wantTechnologies := []string{"Next.js", "React", "TypeScript", "Go", "Tailwind CSS"}
	if !reflect.DeepEqual(seededProject.Technologies, wantTechnologies) {
		t.Errorf("seeded technologies = %#v, want %#v", seededProject.Technologies, wantTechnologies)
	}
}

type projectRow struct {
	slug            string
	title           string
	summary         string
	period          string
	role            string
	contentMarkdown string
	featured        bool
	published       bool
	displayOrder    int
	repositoryURL   *string
	liveURL         *string
}

func seedProjectCatalog(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()

	alphaLiveURL := "https://alpha.example.com"
	zuluRepositoryURL := "https://github.com/example/zulu"

	alphaID := insertProject(t, pool, projectRow{
		slug:            "alpha-project",
		title:           "Alpha Project",
		summary:         "Alpha summary",
		period:          "2026",
		role:            "Developer",
		contentMarkdown: "# Alpha",
		featured:        true,
		published:       true,
		displayOrder:    1,
		liveURL:         &alphaLiveURL,
	})
	insertProject(t, pool, projectRow{
		slug:            "beta-project",
		title:           "Beta Project",
		summary:         "Beta summary",
		period:          "2025",
		role:            "Developer",
		contentMarkdown: "# Beta",
		published:       true,
		displayOrder:    1,
	})
	zuluID := insertProject(t, pool, projectRow{
		slug:            "zulu-project",
		title:           "Zulu Project",
		summary:         "Zulu summary",
		period:          "2024",
		role:            "Lead Developer",
		contentMarkdown: "# Zulu",
		featured:        true,
		published:       true,
		displayOrder:    2,
		repositoryURL:   &zuluRepositoryURL,
	})
	insertProject(t, pool, projectRow{
		slug:            "draft-project",
		title:           "Draft Project",
		summary:         "Draft summary",
		period:          "2027",
		role:            "Developer",
		contentMarkdown: "# Draft",
		featured:        true,
		published:       false,
		displayOrder:    0,
	})

	goID := insertTechnology(t, pool, "go", "Go")
	postgresID := insertTechnology(t, pool, "postgresql", "PostgreSQL")
	typeScriptID := insertTechnology(t, pool, "typescript", "TypeScript")

	insertProjectTechnology(t, pool, zuluID, postgresID, 0)
	insertProjectTechnology(t, pool, zuluID, goID, 1)
	insertProjectTechnology(t, pool, alphaID, typeScriptID, 0)
}

func newProjectIntegrationDatabase(t *testing.T, migrate bool) *pgxpool.Pool {
	t.Helper()

	databaseURL := strings.TrimSpace(os.Getenv("TEST_DATABASE_URL"))
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL is required for PostgreSQL integration tests")
	}

	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	adminConfiguration, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		t.Fatalf("parse TEST_DATABASE_URL: %v", err)
	}
	adminPool, err := pgxpool.NewWithConfig(ctx, adminConfiguration)
	if err != nil {
		t.Fatalf("open integration database: %v", err)
	}
	if err := adminPool.Ping(ctx); err != nil {
		adminPool.Close()
		t.Fatalf("ping integration database: %v", err)
	}

	schema := newIntegrationSchemaName(t)
	quotedSchema := pgx.Identifier{schema}.Sanitize()
	if _, err := adminPool.Exec(ctx, "CREATE SCHEMA "+quotedSchema); err != nil {
		adminPool.Close()
		t.Fatalf("create integration schema: %v", err)
	}

	var pool *pgxpool.Pool
	t.Cleanup(func() {
		if pool != nil {
			pool.Close()
		}

		cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
		defer cleanupCancel()
		if _, err := adminPool.Exec(cleanupContext, "DROP SCHEMA IF EXISTS "+quotedSchema+" CASCADE"); err != nil {
			t.Errorf("drop integration schema %q: %v", schema, err)
		}
		adminPool.Close()
	})

	testConfiguration, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		t.Fatalf("parse TEST_DATABASE_URL for isolated pool: %v", err)
	}
	if testConfiguration.ConnConfig.RuntimeParams == nil {
		testConfiguration.ConnConfig.RuntimeParams = make(map[string]string)
	}
	testConfiguration.ConnConfig.RuntimeParams["search_path"] = schema
	pool, err = pgxpool.NewWithConfig(ctx, testConfiguration)
	if err != nil {
		t.Fatalf("open isolated integration database: %v", err)
	}
	if err := pool.Ping(ctx); err != nil {
		t.Fatalf("ping isolated integration database: %v", err)
	}

	if migrate {
		executeMigration(t, pool, "000001_create_project_catalog.up.sql")
	}

	return pool
}

func newIntegrationSchemaName(t *testing.T) string {
	t.Helper()

	identifier := make([]byte, 8)
	if _, err := rand.Read(identifier); err != nil {
		t.Fatalf("create integration schema identifier: %v", err)
	}

	return "project_test_" + hex.EncodeToString(identifier)
}

func executeMigration(t *testing.T, pool *pgxpool.Pool, filename string) {
	t.Helper()
	executeSQLFile(t, pool, "migrations", filename)
}

func executeSeed(t *testing.T, pool *pgxpool.Pool, filename string) {
	t.Helper()
	executeSQLFile(t, pool, "seed", filename)
}

func executeSQLFile(t *testing.T, pool *pgxpool.Pool, directory string, filename string) {
	t.Helper()

	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("locate integration test file")
	}
	sqlPath := filepath.Join(filepath.Dir(currentFile), "..", "..", directory, filename)
	sqlFile, err := os.ReadFile(sqlPath)
	if err != nil {
		t.Fatalf("read SQL file %q: %v", filename, err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()
	if _, err := pool.Exec(ctx, string(sqlFile)); err != nil {
		t.Fatalf("execute SQL file %q: %v", filename, err)
	}
}

func insertProject(t *testing.T, pool *pgxpool.Pool, row projectRow) string {
	t.Helper()

	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	var id string
	err := pool.QueryRow(ctx, `
		INSERT INTO projects (
			slug,
			title,
			summary,
			period,
			role,
			content_markdown,
			featured,
			published,
			display_order,
			repository_url,
			live_url
		)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
		RETURNING id::text
	`,
		row.slug,
		row.title,
		row.summary,
		row.period,
		row.role,
		row.contentMarkdown,
		row.featured,
		row.published,
		row.displayOrder,
		row.repositoryURL,
		row.liveURL,
	).Scan(&id)
	if err != nil {
		t.Fatalf("insert project %q: %v", row.slug, err)
	}

	return id
}

func insertTechnology(t *testing.T, pool *pgxpool.Pool, slug string, name string) string {
	t.Helper()

	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	var id string
	err := pool.QueryRow(ctx, `
		INSERT INTO technologies (slug, name, category)
		VALUES ($1, $2, 'application')
		RETURNING id::text
	`, slug, name).Scan(&id)
	if err != nil {
		t.Fatalf("insert technology %q: %v", slug, err)
	}

	return id
}

func insertProjectTechnology(
	t *testing.T,
	pool *pgxpool.Pool,
	projectID string,
	technologyID string,
	displayOrder int,
) {
	t.Helper()

	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	if _, err := pool.Exec(ctx, `
		INSERT INTO project_technologies (project_id, technology_id, display_order)
		VALUES ($1, $2, $3)
	`, projectID, technologyID, displayOrder); err != nil {
		t.Fatalf("insert project technology: %v", err)
	}
}

func projectIDBySlug(t *testing.T, pool *pgxpool.Pool, slug string) string {
	t.Helper()

	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	var id string
	if err := pool.QueryRow(ctx, `
		SELECT id::text
		FROM projects
		WHERE slug = $1
	`, slug).Scan(&id); err != nil {
		t.Fatalf("find project %q: %v", slug, err)
	}

	return id
}

func requirePostgresError(t *testing.T, err error, wantCode string, wantConstraint string) {
	t.Helper()

	if err == nil {
		t.Fatalf("database operation error = nil, want PostgreSQL code %s", wantCode)
	}

	var postgresError *pgconn.PgError
	if !errors.As(err, &postgresError) {
		t.Fatalf("database operation error = %T %v, want *pgconn.PgError", err, err)
	}
	if postgresError.Code != wantCode {
		t.Errorf("PostgreSQL error code = %q, want %q", postgresError.Code, wantCode)
	}
	if postgresError.ConstraintName != wantConstraint {
		t.Errorf("PostgreSQL constraint = %q, want %q", postgresError.ConstraintName, wantConstraint)
	}
}

func assertTablesExist(t *testing.T, pool *pgxpool.Pool, want bool) {
	t.Helper()

	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	for _, table := range []string{"projects", "technologies", "project_technologies"} {
		var exists bool
		if err := pool.QueryRow(ctx, `SELECT to_regclass($1) IS NOT NULL`, table).Scan(&exists); err != nil {
			t.Fatalf("check table %q: %v", table, err)
		}
		if exists != want {
			t.Errorf("table %q exists = %t, want %t", table, exists, want)
		}
	}
}

func boolPointer(value bool) *bool {
	return &value
}
