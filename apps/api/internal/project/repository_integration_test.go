//go:build integration

package project_test

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"
	"testing"
	"time"

	"entgo.io/ent/dialect"
	entsql "entgo.io/ent/dialect/sql"
	entschema "entgo.io/ent/dialect/sql/schema"
	"entgo.io/ent/schema/field"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/stdlib"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/database"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence"
	persistenceent "github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence/ent"
	entmigrate "github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence/ent/migrate"
	entproject "github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence/ent/project"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence/ent/technology"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence/seed"
	"github.com/vkng1104/VK-platform/apps/api/internal/project"
)

const integrationOperationTimeout = 10 * time.Second

func TestEntRepositoryListPublishedIntegration(t *testing.T) {
	database := newProjectIntegrationDatabase(t, true)
	seedProjectCatalog(t, database.sql)
	repository := mustNewEntRepository(t, database.client)

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
		{name: "all published projects ordered by display order then title", want: allPublished},
		{name: "featured published projects only", featured: boolPointer(true), want: []project.Project{allPublished[0], allPublished[2]}},
		{name: "non-featured published projects only", featured: boolPointer(false), want: []project.Project{allPublished[1]}},
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

func TestEntRepositoryFindPublishedBySlugIntegration(t *testing.T) {
	database := newProjectIntegrationDatabase(t, true)
	seedProjectCatalog(t, database.sql)
	repository := mustNewEntRepository(t, database.client)

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

func TestEntRepositoryAvoidsNPlusOneQueriesIntegration(t *testing.T) {
	queryCount := 0
	database := newProjectIntegrationDatabase(
		t,
		true,
		persistenceent.Debug(),
		persistenceent.Log(func(...any) { queryCount++ }),
	)
	seedProjectCatalog(t, database.sql)
	repository := mustNewEntRepository(t, database.client)
	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	queryCount = 0
	if _, err := repository.ListPublished(ctx, project.ListFilter{}); err != nil {
		t.Fatalf("ListPublished() error = %v", err)
	}
	if queryCount != 3 {
		t.Fatalf("ListPublished() query count = %d, want 3", queryCount)
	}

	queryCount = 0
	if _, err := repository.FindPublishedBySlug(ctx, "zulu-project"); err != nil {
		t.Fatalf("FindPublishedBySlug() error = %v", err)
	}
	if queryCount != 3 {
		t.Fatalf("FindPublishedBySlug() query count = %d, want 3", queryCount)
	}
}

func TestProjectCatalogStructuralConstraintsIntegration(t *testing.T) {
	database := newProjectIntegrationDatabase(t, true)

	insertProject(t, database.sql, projectRow{
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
			slug, title, summary, period, role, content_markdown,
			display_order, repository_url, live_url
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
			_, err := database.sql.ExecContext(ctx, projectInsert, test.arguments...)
			requirePostgresError(t, err, test.wantCode, test.wantConstraint)
		})
	}

	projectID := projectIDBySlug(t, database.sql, "constraint-project")
	goID := insertTechnology(t, database.sql, "go", "Go")
	postgresID := insertTechnology(t, database.sql, "postgresql", "PostgreSQL")
	insertProjectTechnology(t, database.sql, projectID, goID, 0)

	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	_, err := database.sql.ExecContext(ctx, `
		INSERT INTO project_technologies (project_id, technology_id, display_order)
		VALUES ($1, $2, $3)
	`, projectID, postgresID, 0)
	cancel()
	requirePostgresError(t, err, "23505", "project_technologies_project_display_order_unique")

	ctx, cancel = context.WithTimeout(context.Background(), integrationOperationTimeout)
	_, err = database.sql.ExecContext(ctx, `DELETE FROM technologies WHERE id = $1`, goID)
	cancel()
	requirePostgresError(t, err, "23503", "project_technologies_technology_fk")

	ctx, cancel = context.WithTimeout(context.Background(), integrationOperationTimeout)
	if _, err := database.sql.ExecContext(ctx, `DELETE FROM projects WHERE id = $1`, projectID); err != nil {
		cancel()
		t.Fatalf("delete project: %v", err)
	}
	cancel()

	ctx, cancel = context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()
	var relationships int
	if err := database.sql.QueryRowContext(ctx, `
		SELECT COUNT(*) FROM project_technologies WHERE project_id = $1
	`, projectID).Scan(&relationships); err != nil {
		t.Fatalf("count project technologies after project deletion: %v", err)
	}
	if relationships != 0 {
		t.Fatalf("project technology relationship count = %d, want 0 after cascade", relationships)
	}
}

func TestProjectCatalogMigrationIsReversibleIntegration(t *testing.T) {
	database := newProjectIntegrationDatabase(t, false)

	executeMigration(t, database.sql, "000001_create_project_catalog.up.sql")
	assertTablesExist(t, database.sql, true)
	executeMigration(t, database.sql, "000001_create_project_catalog.down.sql")
	assertTablesExist(t, database.sql, false)
	executeMigration(t, database.sql, "000001_create_project_catalog.up.sql")
	assertTablesExist(t, database.sql, true)
}

func TestMigratedSchemaMatchesGeneratedEntMetadataIntegration(t *testing.T) {
	database := newProjectIntegrationDatabase(t, true)

	for _, table := range entmigrate.Tables {
		actualColumns := databaseColumns(t, database.sql, table.Name)
		if len(actualColumns) != len(table.Columns) {
			t.Errorf("table %q column count = %d, want %d", table.Name, len(actualColumns), len(table.Columns))
		}
		for _, column := range table.Columns {
			actual, ok := actualColumns[column.Name]
			if !ok {
				t.Errorf("table %q is missing generated column %q", table.Name, column.Name)
				continue
			}
			if actual.nullable != column.Nullable {
				t.Errorf(
					"table %q column %q nullable = %t, want %t",
					table.Name,
					column.Name,
					actual.nullable,
					column.Nullable,
				)
			}
			if wantType := postgresType(column); actual.dataType != wantType {
				t.Errorf(
					"table %q column %q type = %q, want %q",
					table.Name,
					column.Name,
					actual.dataType,
					wantType,
				)
			}
		}
	}
}

func TestDevelopmentSeedIsIdempotentAndUpdatesExistingRowsIntegration(t *testing.T) {
	database := newProjectIntegrationDatabase(t, true)
	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	staleTechnology, err := database.client.Technology.
		Create().
		SetSlug("next-js").
		SetName("Stale Next").
		SetCategory("stale").
		Save(ctx)
	if err != nil {
		t.Fatalf("create stale technology: %v", err)
	}
	_, err = database.client.Project.
		Create().
		SetSlug("vk-platform").
		SetTitle("Stale title").
		SetSummary("Stale summary").
		SetPeriod("Old").
		SetRole("Old role").
		SetContentMarkdown("Old content").
		SetLiveURL("https://stale.example.com").
		Save(ctx)
	if err != nil {
		t.Fatalf("create stale project: %v", err)
	}

	if err := seed.Development(ctx, database.client); err != nil {
		t.Fatalf("Development() first error = %v", err)
	}
	if err := seed.Development(ctx, database.client); err != nil {
		t.Fatalf("Development() second error = %v", err)
	}

	for table, want := range map[string]int{
		"projects":             1,
		"technologies":         5,
		"project_technologies": 5,
	} {
		if got := tableCount(t, database.sql, table); got != want {
			t.Errorf("seeded %s count = %d, want %d", table, got, want)
		}
	}

	updatedTechnology, err := database.client.Technology.
		Query().
		Where(technology.SlugEQ("next-js")).
		Only(ctx)
	if err != nil {
		t.Fatalf("query updated technology: %v", err)
	}
	if updatedTechnology.ID != staleTechnology.ID {
		t.Errorf("updated technology ID = %s, want preserved ID %s", updatedTechnology.ID, staleTechnology.ID)
	}
	if updatedTechnology.Name != "Next.js" || updatedTechnology.Category != "frontend" {
		t.Errorf("updated technology = %#v", updatedTechnology)
	}

	seededRecord, err := database.client.Project.
		Query().
		Where(entproject.SlugEQ("vk-platform")).
		Only(ctx)
	if err != nil {
		t.Fatalf("query seeded project: %v", err)
	}
	if seededRecord.Title != "VK Platform" || seededRecord.LiveURL != nil || !seededRecord.Published {
		t.Errorf("seeded project = %#v", seededRecord)
	}

	repository := mustNewEntRepository(t, database.client)
	seededProject, err := repository.FindPublishedBySlug(ctx, "vk-platform")
	if err != nil {
		t.Fatalf("find seeded project: %v", err)
	}
	wantTechnologies := []string{"Next.js", "React", "TypeScript", "Go", "Tailwind CSS"}
	if !reflect.DeepEqual(seededProject.Technologies, wantTechnologies) {
		t.Errorf("seeded technologies = %#v, want %#v", seededProject.Technologies, wantTechnologies)
	}
}

type integrationDatabase struct {
	sql    *sql.DB
	client *persistenceent.Client
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

type databaseColumn struct {
	nullable bool
	dataType string
}

func seedProjectCatalog(t *testing.T, database *sql.DB) {
	t.Helper()

	alphaLiveURL := "https://alpha.example.com"
	zuluRepositoryURL := "https://github.com/example/zulu"
	alphaID := insertProject(t, database, projectRow{
		slug: "alpha-project", title: "Alpha Project", summary: "Alpha summary", period: "2026",
		role: "Developer", contentMarkdown: "# Alpha", featured: true, published: true,
		displayOrder: 1, liveURL: &alphaLiveURL,
	})
	insertProject(t, database, projectRow{
		slug: "beta-project", title: "Beta Project", summary: "Beta summary", period: "2025",
		role: "Developer", contentMarkdown: "# Beta", published: true, displayOrder: 1,
	})
	zuluID := insertProject(t, database, projectRow{
		slug: "zulu-project", title: "Zulu Project", summary: "Zulu summary", period: "2024",
		role: "Lead Developer", contentMarkdown: "# Zulu", featured: true, published: true,
		displayOrder: 2, repositoryURL: &zuluRepositoryURL,
	})
	insertProject(t, database, projectRow{
		slug: "draft-project", title: "Draft Project", summary: "Draft summary", period: "2027",
		role: "Developer", contentMarkdown: "# Draft", featured: true, displayOrder: 0,
	})

	goID := insertTechnology(t, database, "go", "Go")
	postgresID := insertTechnology(t, database, "postgresql", "PostgreSQL")
	typeScriptID := insertTechnology(t, database, "typescript", "TypeScript")
	insertProjectTechnology(t, database, zuluID, postgresID, 0)
	insertProjectTechnology(t, database, zuluID, goID, 1)
	insertProjectTechnology(t, database, alphaID, typeScriptID, 0)
}

func newProjectIntegrationDatabase(
	t *testing.T,
	migrate bool,
	options ...persistenceent.Option,
) *integrationDatabase {
	t.Helper()

	settings := testDatabaseSettings(t)
	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	adminConfiguration, err := database.ParseConnectionConfig(settings)
	if err != nil {
		t.Fatalf("parse TEST_DATABASE_URL: %v", err)
	}
	adminDatabase := stdlib.OpenDB(*adminConfiguration)
	if err := adminDatabase.PingContext(ctx); err != nil {
		_ = adminDatabase.Close()
		t.Fatalf("ping integration database: %v", err)
	}

	schemaName := newIntegrationSchemaName(t)
	quotedSchema := `"` + schemaName + `"`
	if _, err := adminDatabase.ExecContext(ctx, "CREATE SCHEMA "+quotedSchema); err != nil {
		_ = adminDatabase.Close()
		t.Fatalf("create integration schema: %v", err)
	}

	testConfiguration, err := database.ParseConnectionConfig(settings)
	if err != nil {
		t.Fatalf("parse TEST_DATABASE_URL for isolated database: %v", err)
	}
	if testConfiguration.RuntimeParams == nil {
		testConfiguration.RuntimeParams = make(map[string]string)
	}
	testConfiguration.RuntimeParams["search_path"] = schemaName
	sqlDatabase := stdlib.OpenDB(*testConfiguration)
	if err := sqlDatabase.PingContext(ctx); err != nil {
		_ = sqlDatabase.Close()
		t.Fatalf("ping isolated integration database: %v", err)
	}

	var client *persistenceent.Client
	if len(options) == 0 {
		client, err = persistence.NewClient(sqlDatabase)
	} else {
		driver := entsql.OpenDB(dialect.Postgres, sqlDatabase)
		options = append([]persistenceent.Option{persistenceent.Driver(driver)}, options...)
		client = persistenceent.NewClient(options...)
	}
	if err != nil {
		_ = sqlDatabase.Close()
		t.Fatalf("construct Ent client: %v", err)
	}

	database := &integrationDatabase{sql: sqlDatabase, client: client}
	t.Cleanup(func() {
		if err := database.client.Close(); err != nil {
			t.Errorf("close isolated persistence client: %v", err)
		}

		cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
		defer cleanupCancel()
		if _, err := adminDatabase.ExecContext(cleanupContext, "DROP SCHEMA IF EXISTS "+quotedSchema+" CASCADE"); err != nil {
			t.Errorf("drop integration schema %q: %v", schemaName, err)
		}
		if err := adminDatabase.Close(); err != nil {
			t.Errorf("close integration admin database: %v", err)
		}
	})

	if migrate {
		executeMigration(t, sqlDatabase, "000001_create_project_catalog.up.sql")
	}
	return database
}

func testDatabaseSettings(t *testing.T) database.Settings {
	t.Helper()

	settings := database.Settings{
		URL:      strings.TrimSpace(os.Getenv("TEST_DATABASE_URL")),
		Username: strings.TrimSpace(os.Getenv("TEST_DATABASE_USERNAME")),
		Password: strings.TrimSpace(os.Getenv("TEST_DATABASE_PASSWORD")),
	}
	if settings.URL == "" {
		t.Skip("TEST_DATABASE_URL is required for PostgreSQL integration tests")
	}
	if settings.Username == "" {
		t.Fatal("TEST_DATABASE_USERNAME is required for PostgreSQL integration tests")
	}
	if settings.Password == "" {
		t.Fatal("TEST_DATABASE_PASSWORD is required for PostgreSQL integration tests")
	}
	return settings
}

func newIntegrationSchemaName(t *testing.T) string {
	t.Helper()
	identifier := make([]byte, 8)
	if _, err := rand.Read(identifier); err != nil {
		t.Fatalf("create integration schema identifier: %v", err)
	}
	return "project_test_" + hex.EncodeToString(identifier)
}

func executeMigration(t *testing.T, database *sql.DB, filename string) {
	t.Helper()

	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("locate integration test file")
	}
	sqlPath := filepath.Join(filepath.Dir(currentFile), "..", "..", "migrations", filename)
	sqlFile, err := os.ReadFile(sqlPath)
	if err != nil {
		t.Fatalf("read SQL file %q: %v", filename, err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()
	if _, err := database.ExecContext(ctx, string(sqlFile)); err != nil {
		t.Fatalf("execute SQL file %q: %v", filename, err)
	}
}

func insertProject(t *testing.T, database *sql.DB, row projectRow) uuid.UUID {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	var id uuid.UUID
	err := database.QueryRowContext(ctx, `
		INSERT INTO projects (
			slug, title, summary, period, role, content_markdown,
			featured, published, display_order, repository_url, live_url
		)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
		RETURNING id
	`, row.slug, row.title, row.summary, row.period, row.role, row.contentMarkdown,
		row.featured, row.published, row.displayOrder, row.repositoryURL, row.liveURL,
	).Scan(&id)
	if err != nil {
		t.Fatalf("insert project %q: %v", row.slug, err)
	}
	return id
}

func insertTechnology(t *testing.T, database *sql.DB, slug string, name string) uuid.UUID {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	var id uuid.UUID
	if err := database.QueryRowContext(ctx, `
		INSERT INTO technologies (slug, name, category)
		VALUES ($1, $2, 'application')
		RETURNING id
	`, slug, name).Scan(&id); err != nil {
		t.Fatalf("insert technology %q: %v", slug, err)
	}
	return id
}

func insertProjectTechnology(
	t *testing.T,
	database *sql.DB,
	projectID uuid.UUID,
	technologyID uuid.UUID,
	displayOrder int,
) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	if _, err := database.ExecContext(ctx, `
		INSERT INTO project_technologies (project_id, technology_id, display_order)
		VALUES ($1, $2, $3)
	`, projectID, technologyID, displayOrder); err != nil {
		t.Fatalf("insert project technology: %v", err)
	}
}

func projectIDBySlug(t *testing.T, database *sql.DB, slug string) uuid.UUID {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	var id uuid.UUID
	if err := database.QueryRowContext(ctx, `SELECT id FROM projects WHERE slug = $1`, slug).Scan(&id); err != nil {
		t.Fatalf("find project %q: %v", slug, err)
	}
	return id
}

func databaseColumns(t *testing.T, database *sql.DB, table string) map[string]databaseColumn {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	rows, err := database.QueryContext(ctx, `
		SELECT column_name, is_nullable, data_type
		FROM information_schema.columns
		WHERE table_schema = current_schema() AND table_name = $1
	`, table)
	if err != nil {
		t.Fatalf("query columns for %q: %v", table, err)
	}
	defer rows.Close()

	columns := make(map[string]databaseColumn)
	for rows.Next() {
		var name string
		var nullable string
		var dataType string
		if err := rows.Scan(&name, &nullable, &dataType); err != nil {
			t.Fatalf("scan column for %q: %v", table, err)
		}
		columns[name] = databaseColumn{nullable: nullable == "YES", dataType: dataType}
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("iterate columns for %q: %v", table, err)
	}
	return columns
}

func postgresType(column *entschema.Column) string {
	switch column.Type {
	case field.TypeUUID:
		return "uuid"
	case field.TypeString:
		return "text"
	case field.TypeBool:
		return "boolean"
	case field.TypeInt:
		return "integer"
	case field.TypeTime:
		return "timestamp with time zone"
	default:
		return column.Type.String()
	}
}

func tableCount(t *testing.T, database *sql.DB, table string) int {
	t.Helper()
	queries := map[string]string{
		"projects":             "SELECT COUNT(*) FROM projects",
		"technologies":         "SELECT COUNT(*) FROM technologies",
		"project_technologies": "SELECT COUNT(*) FROM project_technologies",
	}
	query, ok := queries[table]
	if !ok {
		t.Fatalf("unsupported table count %q", table)
	}
	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	var count int
	if err := database.QueryRowContext(ctx, query).Scan(&count); err != nil {
		t.Fatalf("count %s: %v", table, err)
	}
	return count
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

func assertTablesExist(t *testing.T, database *sql.DB, want bool) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), integrationOperationTimeout)
	defer cancel()

	for _, table := range []string{"projects", "technologies", "project_technologies"} {
		var exists bool
		if err := database.QueryRowContext(ctx, `SELECT to_regclass($1) IS NOT NULL`, table).Scan(&exists); err != nil {
			t.Fatalf("check table %q: %v", table, err)
		}
		if exists != want {
			t.Errorf("table %q exists = %t, want %t", table, exists, want)
		}
	}
}

func mustNewEntRepository(t *testing.T, client *persistenceent.Client) *project.EntRepository {
	t.Helper()
	repository, err := project.NewEntRepository(client)
	if err != nil {
		t.Fatalf("NewEntRepository() error = %v", err)
	}
	return repository
}

func boolPointer(value bool) *bool {
	return &value
}
