BEGIN;

INSERT INTO technologies (id, slug, name, category)
VALUES
    ('00000000-0000-4000-8000-000000000101', 'next-js', 'Next.js', 'frontend'),
    ('00000000-0000-4000-8000-000000000102', 'react', 'React', 'frontend'),
    ('00000000-0000-4000-8000-000000000103', 'typescript', 'TypeScript', 'language'),
    ('00000000-0000-4000-8000-000000000104', 'go', 'Go', 'language'),
    ('00000000-0000-4000-8000-000000000105', 'tailwind-css', 'Tailwind CSS', 'frontend')
ON CONFLICT (slug) DO UPDATE
SET
    name = EXCLUDED.name,
    category = EXCLUDED.category,
    updated_at = NOW();

INSERT INTO projects (
    id,
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
VALUES (
    '00000000-0000-4000-8000-000000000001',
    'vk-platform',
    'VK Platform',
    'A systems-focused engineering portfolio that grows alongside practical backend, infrastructure, data, and AI experiments.',
    '2026 — Present',
    'Creator and software engineer',
    $project_markdown$## Why it exists

VK Platform treats the portfolio itself as an engineering project. The public site presents the work, while the repository provides a place to learn systems concepts by adding them only when a real feature needs them.

## Current architecture

The first architecture is deliberately small:

- a Next.js frontend for the portfolio and system views;
- a Go HTTP API for backend capabilities;
- a PostgreSQL-backed, read-only project catalog.

## What is implemented

The monorepo includes a responsive frontend, a live API status view, shared local verification commands, and isolated frontend and backend tests. The database-backed project showcase is the first content-driven feature built on that foundation.

## How it evolves

Future components must earn their place through a product or learning requirement. Observability, data pipelines, AI runtimes, and deployment infrastructure arrive as the platform gains behaviors that justify them.$project_markdown$,
    TRUE,
    TRUE,
    1,
    'https://github.com/vkng1104/VK-platform',
    NULL
)
ON CONFLICT (slug) DO UPDATE
SET
    title = EXCLUDED.title,
    summary = EXCLUDED.summary,
    period = EXCLUDED.period,
    role = EXCLUDED.role,
    content_markdown = EXCLUDED.content_markdown,
    featured = EXCLUDED.featured,
    published = EXCLUDED.published,
    display_order = EXCLUDED.display_order,
    repository_url = EXCLUDED.repository_url,
    live_url = EXCLUDED.live_url,
    updated_at = NOW();

DELETE FROM project_technologies
WHERE project_id = (
    SELECT id
    FROM projects
    WHERE slug = 'vk-platform'
);

INSERT INTO project_technologies (project_id, technology_id, display_order)
SELECT
    project.id,
    technology.id,
    seeded_technology.display_order
FROM (
    VALUES
        ('next-js', 0),
        ('react', 1),
        ('typescript', 2),
        ('go', 3),
        ('tailwind-css', 4)
) AS seeded_technology(slug, display_order)
JOIN technologies AS technology
    ON technology.slug = seeded_technology.slug
CROSS JOIN LATERAL (
    SELECT id
    FROM projects
    WHERE slug = 'vk-platform'
) AS project;

COMMIT;
