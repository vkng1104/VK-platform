import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { KnowledgeCategory } from "@/features/knowledge/model";

import { KnowledgeGraph } from "./KnowledgeGraph";

const categories: KnowledgeCategory[] = [
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
        relatedTopicCount: 2,
        noteCount: 1,
      },
    ],
  },
];

describe("KnowledgeGraph", () => {
  it("renders semantic category sections and navigable topic links", () => {
    const markup = renderToStaticMarkup(
      <KnowledgeGraph categories={categories} />,
    );

    expect(markup).toContain('<section aria-labelledby="category-backend">');
    expect(markup).toContain('id="category-backend"');
    expect(markup).toContain('href="/knowledge/api-design"');
    expect(markup).toContain("2 related topics");
    expect(markup).toContain("1 note");
  });

  it("renders a useful empty state", () => {
    const markup = renderToStaticMarkup(<KnowledgeGraph categories={[]} />);

    expect(markup).toContain("Knowledge topics are being prepared.");
  });
});
