import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  loadPublishedContentCatalog: vi.fn(),
}));

vi.mock("server-only", () => ({}));
vi.mock("@/features/content/catalog.server", () => ({
  loadPublishedContentCatalog: mocks.loadPublishedContentCatalog,
}));

import { getKnowledgeCategories, getKnowledgeTopicBySlug } from "./queries";

const topics = [
  {
    slug: "api-design",
    title: "API Design",
    summary: "Explicit contracts.",
    category: "backend",
    status: "published" as const,
    concepts: ["Contracts"],
    relatedTopicSlugs: ["postgresql"],
    relatedProjects: [{ slug: "vk-platform", title: "VK Platform" }],
    displayOrder: 20,
    body: "API body",
  },
  {
    slug: "email-verification",
    title: "Email Verification",
    summary: "Purpose-bound proof.",
    category: "backend",
    status: "published" as const,
    concepts: ["Purpose binding"],
    relatedTopicSlugs: [],
    relatedProjects: [],
    displayOrder: 10,
    body: "Email body",
  },
  {
    slug: "postgresql",
    title: "PostgreSQL",
    summary: "Relational persistence.",
    category: "data",
    status: "published" as const,
    concepts: ["Migrations"],
    relatedTopicSlugs: [],
    relatedProjects: [],
    displayOrder: 10,
    body: "Database body",
  },
];

const notes = [
  {
    slug: "database-contracts",
    title: "Database contracts",
    summary: "Make persistence explicit.",
    status: "published" as const,
    publishedAt: "2026-10-08",
    tags: ["PostgreSQL"],
    topicSlugs: ["postgresql"],
    body: "Note body",
    readingTimeMinutes: 2,
  },
];

describe("knowledge queries", () => {
  beforeEach(() => {
    mocks.loadPublishedContentCatalog.mockResolvedValue({ topics, notes });
  });

  it("groups topics and preserves display order within a category", async () => {
    const categories = await getKnowledgeCategories();

    expect(categories.map((category) => category.title)).toEqual([
      "Backend",
      "Data",
    ]);
    expect(categories[0].topics.map((topic) => topic.slug)).toEqual([
      "email-verification",
      "api-design",
    ]);
  });

  it("normalizes reverse topic relationships and derives note backlinks", async () => {
    const topic = await getKnowledgeTopicBySlug("postgresql");

    expect(topic?.relatedTopics).toEqual([
      { slug: "api-design", title: "API Design" },
    ]);
    expect(topic?.relatedNotes).toEqual([
      expect.objectContaining({ slug: "database-contracts" }),
    ]);
    expect(topic?.relatedTopicCount).toBe(1);
    expect(topic?.noteCount).toBe(1);
  });

  it("returns null for an unknown topic", async () => {
    await expect(getKnowledgeTopicBySlug("unknown-topic")).resolves.toBeNull();
  });
});
