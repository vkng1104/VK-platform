import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { ExperienceEntry } from "@/features/experience/model";

import { ExperienceEntryCard } from "./ExperienceEntryCard";

const entry: ExperienceEntry = {
  id: "example-role",
  employer: "Example Co",
  role: "Software Engineer",
  startDate: "2024-01",
  highlights: ["Built a reliable service."],
  technologies: ["Go", "PostgreSQL"],
};

describe("ExperienceEntryCard", () => {
  it("renders semantic role, employer, dates, highlights, and technologies", () => {
    const markup = renderToStaticMarkup(<ExperienceEntryCard entry={entry} />);

    expect(markup).toContain("Software Engineer");
    expect(markup).toContain("Example Co");
    expect(markup).toContain("Jan 2024 - Present");
    expect(markup).toContain("Built a reliable service.");
    expect(markup).toContain('aria-label="Technologies"');
    expect(markup).toContain("PostgreSQL");
  });

  it("omits the technology list when no technologies are approved", () => {
    const markup = renderToStaticMarkup(
      <ExperienceEntryCard entry={{ ...entry, technologies: [] }} />,
    );

    expect(markup).not.toContain('aria-label="Technologies"');
  });
});
