import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getEngineeringNotes: vi.fn(),
}));

vi.mock("@/features/blog/queries", () => ({
  getEngineeringNotes: mocks.getEngineeringNotes,
}));

import BlogPage from "./page";

describe("engineering notes index route", () => {
  beforeEach(() => {
    mocks.getEngineeringNotes.mockResolvedValue([
      {
        slug: "reliable-api-contracts",
        title: "Reliable API contracts",
        summary: "How explicit contracts keep services understandable.",
        publishedAt: "2026-10-08",
        readingTimeMinutes: 3,
        tags: ["Go"],
        topics: [{ slug: "api-design", title: "API Design" }],
      },
    ]);
  });

  it("renders published note summaries", async () => {
    const markup = renderToStaticMarkup(await BlogPage());

    expect(markup).toContain("Engineering notes");
    expect(markup).toContain("Reliable API contracts");
    expect(markup).toContain('href="/blog/reliable-api-contracts"');
  });
});
