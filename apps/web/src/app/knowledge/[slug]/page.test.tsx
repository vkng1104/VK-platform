import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getKnowledgeTopicBySlug: vi.fn(),
  getKnowledgeTopicSlugs: vi.fn(),
  notFound: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  notFound: mocks.notFound,
}));
vi.mock("@/features/knowledge/queries", () => ({
  getKnowledgeTopicBySlug: mocks.getKnowledgeTopicBySlug,
  getKnowledgeTopicSlugs: mocks.getKnowledgeTopicSlugs,
}));

import KnowledgeTopicPage, {
  generateMetadata,
  generateStaticParams,
} from "./page";

const topic = {
  slug: "api-design",
  title: "API Design",
  summary: "Explicit contracts and stable errors.",
  category: "backend",
  concepts: ["Stable errors"],
  relatedTopicCount: 0,
  noteCount: 0,
  body: "## Contract\n\nKeep it explicit.",
  relatedTopics: [],
  relatedNotes: [],
  relatedProjects: [],
};

describe("knowledge topic detail route", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getKnowledgeTopicBySlug.mockResolvedValue(topic);
    mocks.getKnowledgeTopicSlugs.mockResolvedValue([topic.slug]);
    mocks.notFound.mockImplementation(() => {
      throw new Error("NEXT_NOT_FOUND");
    });
  });

  it("renders one published topic", async () => {
    const markup = renderToStaticMarkup(
      await KnowledgeTopicPage({ params: Promise.resolve({ slug: topic.slug }) }),
    );

    expect(markup).toContain("API Design");
    expect(markup).toContain("Keep it explicit.");
  });

  it("generates metadata and static params from published content", async () => {
    await expect(
      generateMetadata({ params: Promise.resolve({ slug: topic.slug }) }),
    ).resolves.toEqual({ title: topic.title, description: topic.summary });
    await expect(generateStaticParams()).resolves.toEqual([{ slug: topic.slug }]);
  });

  it("uses not-found behavior for an unavailable topic", async () => {
    mocks.getKnowledgeTopicBySlug.mockResolvedValue(null);

    await expect(
      KnowledgeTopicPage({ params: Promise.resolve({ slug: "draft-topic" }) }),
    ).rejects.toThrow("NEXT_NOT_FOUND");
  });
});
