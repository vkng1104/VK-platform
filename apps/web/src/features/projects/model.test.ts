import { describe, expect, it } from "vitest";

import type { ProjectDetailDto } from "./api";
import { toProjectDetail } from "./model";

describe("project model mapping", () => {
  it("maps the snake_case detail DTO to the UI view model", () => {
    const dto: ProjectDetailDto = {
      slug: "vk-platform",
      title: "VK Platform",
      summary: "A systems-focused engineering portfolio.",
      period: "2026 — Present",
      role: "Creator and software engineer",
      technologies: ["Next.js", "Go"],
      featured: true,
      repository_url: "https://github.com/vkng1104/VK-platform",
      live_url: null,
      content_markdown: "## Overview",
    };

    expect(toProjectDetail(dto)).toEqual({
      slug: "vk-platform",
      title: "VK Platform",
      summary: "A systems-focused engineering portfolio.",
      period: "2026 — Present",
      role: "Creator and software engineer",
      technologies: ["Next.js", "Go"],
      featured: true,
      repositoryUrl: "https://github.com/vkng1104/VK-platform",
      liveUrl: undefined,
      contentMarkdown: "## Overview",
    });
  });
});
