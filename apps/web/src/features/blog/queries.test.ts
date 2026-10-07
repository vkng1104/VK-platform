import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  loadPublishedContentCatalog: vi.fn(),
}));

vi.mock("server-only", () => ({}));
vi.mock("@/features/content/catalog.server", () => ({
  loadPublishedContentCatalog: mocks.loadPublishedContentCatalog,
}));

import { getEngineeringNoteBySlug, getEngineeringNotes } from "./queries";

const topics = [
  {
    slug: "api-design",
    title: "API Design",
    summary: "Explicit contracts.",
    category: "backend",
    status: "published" as const,
    concepts: ["Contracts"],
    relatedTopicSlugs: [],
    relatedProjects: [],
    displayOrder: 10,
    body: "Topic body",
  },
];

const notes = [
  {
    slug: "older-note",
    title: "Older note",
    summary: "First note.",
    status: "published" as const,
    publishedAt: "2026-10-01",
    tags: ["Go"],
    topicSlugs: ["api-design"],
    body: "Older body",
    readingTimeMinutes: 1,
  },
  {
    slug: "newer-note",
    title: "Newer note",
    summary: "Second note.",
    status: "published" as const,
    publishedAt: "2026-10-08",
    updatedAt: "2026-10-09",
    tags: ["API"],
    topicSlugs: ["api-design"],
    body: "Newer body",
    readingTimeMinutes: 2,
  },
];

describe("engineering note queries", () => {
  beforeEach(() => {
    mocks.loadPublishedContentCatalog.mockResolvedValue({ topics, notes });
  });

  it("sorts notes newest first and resolves topic references", async () => {
    const summaries = await getEngineeringNotes();

    expect(summaries.map((note) => note.slug)).toEqual([
      "newer-note",
      "older-note",
    ]);
    expect(summaries[0].topics).toEqual([
      { slug: "api-design", title: "API Design" },
    ]);
  });

  it("returns a detail with its body and metadata", async () => {
    const note = await getEngineeringNoteBySlug("newer-note");

    expect(note).toEqual(
      expect.objectContaining({
        slug: "newer-note",
        body: "Newer body",
        updatedAt: "2026-10-09",
        readingTimeMinutes: 2,
      }),
    );
  });

  it("returns null for an unknown note", async () => {
    await expect(getEngineeringNoteBySlug("unknown-note")).resolves.toBeNull();
  });
});
