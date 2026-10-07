import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { KnowledgeTopicDetail } from "@/features/knowledge/model";

import { KnowledgeTopicDetails } from "./KnowledgeTopicDetails";

function topic(
  overrides: Partial<KnowledgeTopicDetail> = {},
): KnowledgeTopicDetail {
  return {
    slug: "api-design",
    title: "API Design",
    summary: "Explicit contracts and stable errors.",
    category: "backend",
    concepts: ["Stable errors"],
    relatedTopicCount: 1,
    noteCount: 1,
    body: "## Contract\n\nKeep it explicit.",
    relatedTopics: [{ slug: "postgresql", title: "PostgreSQL" }],
    relatedNotes: [
      {
        slug: "reliable-api-contracts",
        title: "Reliable API contracts",
        summary: "A note.",
        publishedAt: "2026-10-08",
        readingTimeMinutes: 3,
      },
    ],
    relatedProjects: [{ slug: "vk-platform", title: "VK Platform" }],
    ...overrides,
  };
}

describe("KnowledgeTopicDetails", () => {
  it("renders authored and derived relationships as links", () => {
    const markup = renderToStaticMarkup(
      <KnowledgeTopicDetails topic={topic()} />,
    );

    expect(markup).toContain('href="/knowledge/postgresql"');
    expect(markup).toContain('href="/blog/reliable-api-contracts"');
    expect(markup).toContain('href="/projects/vk-platform"');
    expect(markup).toContain("Keep it explicit.");
  });

  it("omits empty relationship sections", () => {
    const markup = renderToStaticMarkup(
      <KnowledgeTopicDetails
        topic={
          topic({
            relatedTopics: [],
            relatedNotes: [],
            relatedProjects: [],
            relatedTopicCount: 0,
            noteCount: 0,
          })
        }
      />,
    );

    expect(markup).not.toContain("Related topics");
    expect(markup).not.toContain("Engineering notes");
    expect(markup).not.toContain("Used in");
  });
});
