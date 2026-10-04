import "server-only";

import { readFile } from "node:fs/promises";

import type { PublicProfile } from "./model";
import { repositoryPath } from "./content-path.server";
import { sortExperienceNewestFirst } from "./format";
import { parsePublicProfile } from "./validation";

const defaultPublicProfilePath = repositoryPath(
  "internal",
  "content",
  "cv",
  "public",
  "profile.json",
);

export async function loadPublicProfile(
  sourcePath = defaultPublicProfilePath,
): Promise<PublicProfile> {
  const raw = await readFile(sourcePath, "utf8");
  const profile = parsePublicProfile(JSON.parse(raw), sourcePath);

  return {
    ...profile,
    experience: sortExperienceNewestFirst(profile.experience),
  };
}
