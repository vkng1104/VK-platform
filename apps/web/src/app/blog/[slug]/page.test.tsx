import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getEngineeringNoteBySlug: vi.fn(),
  getEngineeringNoteSlugs: vi.fn(),
  notFound: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  notFound: mocks.notFound,
}));
vi.mock("@/features/blog/queries", () => ({
  getEngineeringNoteBySlug: mocks.getEngineeringNoteBySlug,
  getEngineeringNoteSlugs: mocks.getEngineeringNoteSlugs,
}));

import EngineeringNotePage, {
  generateMetadata,
  generateStaticParams,
} from "./page";

const note = {
  slug: "reliable-api-contracts",
  title: "Reliable API contracts",
  summary: "How explicit contracts keep services understandable.",
  publishedAt: "2026-10-08",
  readingTimeMinutes: 3,
  tags: ["Go"],
  topics: [{ slug: "api-design", title: "API Design" }],
  body: "## Contract\n\nKeep it explicit.",
};

describe("engineering note detail route", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getEngineeringNoteBySlug.mockResolvedValue(note);
    mocks.getEngineeringNoteSlugs.mockResolvedValue([note.slug]);
    mocks.notFound.mockImplementation(() => {
      throw new Error("NEXT_NOT_FOUND");
    });
  });

  it("renders one published note", async () => {
    const markup = renderToStaticMarkup(
      await EngineeringNotePage({ params: Promise.resolve({ slug: note.slug }) }),
    );

    expect(markup).toContain("Reliable API contracts");
    expect(markup).toContain("Keep it explicit.");
  });

  it("generates metadata and static params from published content", async () => {
    await expect(
      generateMetadata({ params: Promise.resolve({ slug: note.slug }) }),
    ).resolves.toEqual({ title: note.title, description: note.summary });
    await expect(generateStaticParams()).resolves.toEqual([{ slug: note.slug }]);
  });

  it("uses not-found behavior for an unavailable note", async () => {
    mocks.getEngineeringNoteBySlug.mockResolvedValue(null);

    await expect(
      EngineeringNotePage({ params: Promise.resolve({ slug: "draft-note" }) }),
    ).rejects.toThrow("NEXT_NOT_FOUND");
  });
});
