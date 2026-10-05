import { afterEach, describe, expect, it, vi } from "vitest";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

vi.mock("server-only", () => ({}));

import { loadPublicProfile } from "./content";

const temporaryDirectories: string[] = [];

async function temporaryFile(name: string, content: unknown): Promise<string> {
  const directory = await mkdtemp(path.join(tmpdir(), "vk-profile-test-"));
  temporaryDirectories.push(directory);
  const filePath = path.join(directory, name);
  await writeFile(filePath, JSON.stringify(content), "utf8");
  return filePath;
}

afterEach(async () => {
  vi.unstubAllEnvs();
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) =>
      rm(directory, { force: true, recursive: true }),
    ),
  );
});

describe("experience content loaders", () => {
  it("loads and sorts public experience newest first", async () => {
    const sourcePath = await temporaryFile("public.json", {
      name: "Example Person",
      headline: "Software engineer",
      summary: "Builds reliable systems.",
      experience: [
        {
          id: "past",
          employer: "Past Co",
          role: "Engineer",
          start_date: "2020-01",
          end_date: "2021-01",
          highlights: ["Built a service."],
          technologies: [],
        },
        {
          id: "current",
          employer: "Current Co",
          role: "Engineer",
          start_date: "2024-01",
          end_date: null,
          highlights: ["Runs a service."],
          technologies: ["Go"],
        },
      ],
    });

    const profile = await loadPublicProfile(sourcePath);

    expect(profile.experience.map((entry) => entry.id)).toEqual([
      "current",
      "past",
    ]);
  });
});
