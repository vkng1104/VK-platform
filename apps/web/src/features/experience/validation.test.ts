import { describe, expect, it } from "vitest";

import { parsePublicProfile } from "./validation";

function publicProfile() {
  return {
    name: "Example Person",
    headline: "Software engineer",
    summary: "Builds reliable systems.",
    experience: [
      {
        id: "current-role",
        employer: "Example Co",
        role: "Engineer",
        start_date: "2024-01",
        end_date: null,
        highlights: ["Built a reliable service."],
        technologies: ["Go"],
      },
    ],
  };
}

describe("public profile validation", () => {
  it("maps valid snake_case content into the typed model", () => {
    expect(parsePublicProfile(publicProfile(), "profile.json")).toEqual({
      name: "Example Person",
      headline: "Software engineer",
      summary: "Builds reliable systems.",
      experience: [
        {
          id: "current-role",
          employer: "Example Co",
          role: "Engineer",
          startDate: "2024-01",
          endDate: undefined,
          highlights: ["Built a reliable service."],
          technologies: ["Go"],
        },
      ],
    });
  });

  it.each([
    ["invalid month", "2024-13", null, "must use YYYY-MM format"],
    ["reversed dates", "2024-01", "2023-12", "before its start date"],
  ])("rejects %s", (_name, startDate, endDate, message) => {
    const content = publicProfile();
    const entry = content.experience[0] as {
      start_date: string;
      end_date: string | null;
    };
    entry.start_date = startDate;
    entry.end_date = endDate;

    expect(() => parsePublicProfile(content, "profile.json")).toThrow(message);
  });

  it("rejects duplicate experience identifiers", () => {
    const content = publicProfile();
    content.experience.push(structuredClone(content.experience[0]));

    expect(() => parsePublicProfile(content, "profile.json")).toThrow(
      'profile.json: experience contains duplicate id "current-role"',
    );
  });

  it("identifies the source and missing required field", () => {
    const content = publicProfile() as Record<string, unknown>;
    delete content.summary;

    expect(() => parsePublicProfile(content, "profile.json")).toThrow(
      "profile.json: summary must be a non-empty string",
    );
  });
});
