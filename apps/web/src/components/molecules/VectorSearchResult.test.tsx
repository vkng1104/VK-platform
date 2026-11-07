import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { VectorSearchResult } from "./VectorSearchResult";

describe("VectorSearchResult", () => {
  it("shows ranked evidence, raw similarity score, and source link", () => {
    const markup = renderToStaticMarkup(
      <VectorSearchResult
        rank={1}
        match={{
          chunkId: "project:vk-platform:architecture:0",
          title: "VK Platform",
          heading: "Architecture",
          excerpt: "Go calls Python.",
          sourceUrl: "/projects/vk-platform",
          score: 0.91234,
        }}
      />,
    );

    expect(markup).toContain("Match 1");
    expect(markup).toContain("Cosine score 0.9123");
    expect(markup).toContain("Go calls Python.");
    expect(markup).toContain('href="/projects/vk-platform"');
  });
});
