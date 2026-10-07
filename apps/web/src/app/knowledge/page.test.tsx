import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getKnowledgeCategories: vi.fn(),
}));

vi.mock("@/features/knowledge/queries", () => ({
  getKnowledgeCategories: mocks.getKnowledgeCategories,
}));

import KnowledgePage from "./page";

describe("knowledge index route", () => {
  beforeEach(() => {
    mocks.getKnowledgeCategories.mockResolvedValue([
      {
        id: "backend",
        title: "Backend",
        topics: [
          {
            slug: "api-design",
            title: "API Design",
            summary: "Explicit contracts and stable errors.",
            category: "backend",
            concepts: ["Stable errors"],
            relatedTopicCount: 1,
            noteCount: 1,
          },
        ],
      },
    ]);
  });

  it("renders published topic groups", async () => {
    const markup = renderToStaticMarkup(await KnowledgePage());

    expect(markup).toContain("Knowledge graph");
    expect(markup).toContain("API Design");
    expect(markup).toContain('href="/knowledge/api-design"');
  });
});
