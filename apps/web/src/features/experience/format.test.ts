import { describe, expect, it } from "vitest";

import type { ExperienceEntry } from "./model";
import {
  formatDateRange,
  formatMonthYear,
  sortExperienceNewestFirst,
} from "./format";

const entries: ExperienceEntry[] = [
  {
    id: "older",
    employer: "Older Employer",
    role: "Engineer",
    startDate: "2020-01",
    endDate: "2021-06",
    highlights: ["Built a service."],
    technologies: [],
  },
  {
    id: "current",
    employer: "Current Employer",
    role: "Senior Engineer",
    startDate: "2024-01",
    highlights: ["Operates a service."],
    technologies: ["Go"],
  },
  {
    id: "recent",
    employer: "Recent Employer",
    role: "Engineer",
    startDate: "2022-03",
    endDate: "2023-11",
    highlights: ["Improved a service."],
    technologies: [],
  },
];

describe("experience formatting", () => {
  it("formats normalized months and a current role", () => {
    expect(formatMonthYear("2024-01")).toBe("Jan 2024");
    expect(formatDateRange("2024-01")).toBe("Jan 2024 - Present");
    expect(formatDateRange("2022-03", "2023-11")).toBe(
      "Mar 2022 - Nov 2023",
    );
  });

  it("sorts current and recently ended roles first without mutating input", () => {
    const sorted = sortExperienceNewestFirst(entries);

    expect(sorted.map((entry) => entry.id)).toEqual([
      "current",
      "recent",
      "older",
    ]);
    expect(entries.map((entry) => entry.id)).toEqual([
      "older",
      "current",
      "recent",
    ]);
  });
});
