import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { EngineeringNoteSummary } from "@/features/blog/model";

import { EngineeringNoteCard } from "./EngineeringNoteCard";

const note: EngineeringNoteSummary = {
  slug: "reliable-api-contracts",
  title: "Reliable API contracts",
  summary: "How explicit contracts keep services understandable.",
  publishedAt: "2026-10-08",
  readingTimeMinutes: 4,
  tags: ["Go", "API design"],
  topics: [{ slug: "api-design", title: "API Design" }],
};

describe("EngineeringNoteCard", () => {
  it("exposes note metadata and related knowledge links", () => {
    const markup = renderToStaticMarkup(<EngineeringNoteCard note={note} />);

    expect(markup).toContain('href="/blog/reliable-api-contracts"');
    expect(markup).toContain('dateTime="2026-10-08"');
    expect(markup).toContain("8 October 2026");
    expect(markup).toContain("4 min read");
    expect(markup).toContain('href="/knowledge/api-design"');
  });
});
