import { describe, expect, it, vi } from "vitest";

import { retrievePublishedContent } from "./api";

describe("vector-search API", () => {
  it("posts the bounded query and maps snake_case matches", async () => {
    const fetcher = vi.fn(async () =>
      Response.json({
        retrieval: {
          query: "architecture",
          matches: [
            {
              chunk_id: "project:vk-platform:architecture:0",
              title: "VK Platform",
              heading: "Architecture",
              excerpt: "Go calls Python.",
              source_url: "/projects/vk-platform",
              score: 0.91,
            },
          ],
        },
      }),
    );

    const matches = await retrievePublishedContent("architecture", {
      baseUrl: "https://api.example.test",
      fetcher: fetcher as typeof fetch,
    });

    expect(fetcher).toHaveBeenCalledWith(
      new URL("https://api.example.test/api/v1/ai/retrievals"),
      expect.objectContaining({ body: '{"query":"architecture","top_k":5}' }),
    );
    expect(matches).toEqual([
      {
        chunkId: "project:vk-platform:architecture:0",
        title: "VK Platform",
        heading: "Architecture",
        excerpt: "Go calls Python.",
        sourceUrl: "/projects/vk-platform",
        score: 0.91,
      },
    ]);
  });
});
