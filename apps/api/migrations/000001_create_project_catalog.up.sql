CREATE TABLE projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    period TEXT NOT NULL,
    role TEXT NOT NULL,
    content_markdown TEXT NOT NULL,
    featured BOOLEAN NOT NULL DEFAULT FALSE,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INTEGER NOT NULL DEFAULT 0,
    repository_url TEXT,
    live_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT projects_slug_format_check
        CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT projects_title_not_blank_check
        CHECK (BTRIM(title) <> ''),
    CONSTRAINT projects_summary_not_blank_check
        CHECK (BTRIM(summary) <> ''),
    CONSTRAINT projects_period_not_blank_check
        CHECK (BTRIM(period) <> ''),
    CONSTRAINT projects_role_not_blank_check
        CHECK (BTRIM(role) <> ''),
    CONSTRAINT projects_content_markdown_not_blank_check
        CHECK (BTRIM(content_markdown) <> ''),
    CONSTRAINT projects_display_order_non_negative_check
        CHECK (display_order >= 0),
    CONSTRAINT projects_repository_url_http_check
        CHECK (repository_url IS NULL OR repository_url ~* '^https?://[^[:space:]]+$'),
    CONSTRAINT projects_live_url_http_check
        CHECK (live_url IS NULL OR live_url ~* '^https?://[^[:space:]]+$')
);

CREATE INDEX projects_published_display_order_idx
    ON projects (display_order, title, id)
    WHERE published = TRUE;

CREATE INDEX projects_published_featured_display_order_idx
    ON projects (display_order, title, id)
    WHERE published = TRUE AND featured = TRUE;

CREATE TABLE technologies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    category TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT technologies_slug_format_check
        CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT technologies_name_not_blank_check
        CHECK (BTRIM(name) <> ''),
    CONSTRAINT technologies_category_not_blank_check
        CHECK (BTRIM(category) <> '')
);

CREATE TABLE project_technologies (
    project_id UUID NOT NULL,
    technology_id UUID NOT NULL,
    display_order INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (project_id, technology_id),
    CONSTRAINT project_technologies_project_fk
        FOREIGN KEY (project_id)
        REFERENCES projects (id)
        ON DELETE CASCADE,
    CONSTRAINT project_technologies_technology_fk
        FOREIGN KEY (technology_id)
        REFERENCES technologies (id)
        ON DELETE RESTRICT,
    CONSTRAINT project_technologies_display_order_non_negative_check
        CHECK (display_order >= 0),
    CONSTRAINT project_technologies_project_display_order_unique
        UNIQUE (project_id, display_order)
);

CREATE INDEX project_technologies_technology_id_idx
    ON project_technologies (technology_id);
