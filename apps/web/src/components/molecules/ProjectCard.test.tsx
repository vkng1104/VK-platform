import { renderToStaticMarkup } from "react-dom/server";

import { describe, expect, it } from "vitest";

import type { ProjectSummary } from "@/features/projects/model";

import { ProjectCard } from "./ProjectCard";

const project: ProjectSummary = {
  slug: "vk-platform",
  title: "VK Platform",
  summary: "A systems-focused engineering portfolio.",
  period: "2026 — Present",
  role: "Creator and software engineer",
  technologies: ["Next.js", "Go"],
  featured: true,
};

describe("ProjectCard", () => {
  it("uses the full card as the project detail link", () => {
    const markup = renderToStaticMarkup(<ProjectCard project={project} />);

    expect(markup).toContain('href="/projects/vk-platform"');
    expect(markup).toContain('aria-label="View VK Platform project"');
    expect(markup.indexOf("<a ")).toBeLessThan(markup.indexOf("<article"));
    expect(markup.lastIndexOf("</article>")).toBeLessThan(
      markup.lastIndexOf("</a>"),
    );
  });
});
