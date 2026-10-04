import { describe, expect, it, vi } from "vitest";

import { fetchProject, fetchProjects, type ProjectSummaryDto } from "./api";

const baseUrl = "https://api.example.test";

const project: ProjectSummaryDto = {
  slug: "vk-platform",
  title: "VK Platform",
  summary: "A systems-focused engineering portfolio.",
  period: "2026 — Present",
  role: "Creator and software engineer",
  technologies: ["Next.js", "Go"],
  featured: true,
  repository_url: "https://github.com/vkng1104/VK-platform",
  live_url: null,
};

describe("projects API", () => {
  it.each([
    [undefined, "https://api.example.test/api/v1/projects"],
    [true, "https://api.example.test/api/v1/projects?featured=true"],
  ])("requests the project list with featured=%s", async (featured, expectedUrl) => {
    const fetcher = vi.fn(async () => Response.json({ projects: [project] }));

    const projects = await fetchProjects({
      baseUrl,
      featured,
      fetcher: fetcher as typeof fetch,
    });

    expect(projects).toEqual([project]);
    expect(fetcher).toHaveBeenCalledWith(
      new URL(expectedUrl),
      expect.objectContaining({ cache: "no-store" }),
    );
  });

  it("encodes the project slug in the detail URL", async () => {
    const fetcher = vi.fn(async () =>
      Response.json({
        project: {
          ...project,
          content_markdown: "## Overview",
        },
      }),
    );

    await fetchProject("platform/overview", {
      baseUrl,
      fetcher: fetcher as typeof fetch,
    });

    expect(fetcher).toHaveBeenCalledWith(
      new URL("https://api.example.test/api/v1/projects/platform%2Foverview"),
      expect.objectContaining({ cache: "no-store" }),
    );
  });
});
