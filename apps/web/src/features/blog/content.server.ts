import "server-only";

import { readFile } from "node:fs/promises";
import path from "node:path";

import { repositoryPath } from "@/lib/repository-path.server";

import { calculateReadingTime } from "./format";
import type { EngineeringNoteRecord } from "./model";
import { parseBlogIndex } from "./validation";

const defaultBlogDirectory = repositoryPath("internal", "content", "blog");

export async function loadEngineeringNoteRecords(
  sourceDirectory = defaultBlogDirectory,
): Promise<EngineeringNoteRecord[]> {
  const sourceLabel =
    sourceDirectory === defaultBlogDirectory
      ? "internal/content/blog"
      : sourceDirectory;
  const rawIndex = await readFile(path.join(sourceDirectory, "index.json"), "utf8");
  let parsedIndex: unknown;

  try {
    parsedIndex = JSON.parse(rawIndex);
  } catch (error) {
    throw new Error(`${sourceLabel}/index.json: root must be valid JSON`, {
      cause: error,
    });
  }

  const metadata = parseBlogIndex(parsedIndex, `${sourceLabel}/index.json`);

  return Promise.all(
    metadata.map(async (note) => {
      const body = (
        await readFile(
          path.join(sourceDirectory, "posts", `${note.slug}.md`),
          "utf8",
        )
      ).trim();

      if (body === "") {
        throw new Error(
          `${sourceLabel}/posts/${note.slug}.md: body must be non-empty`,
        );
      }

      return {
        ...note,
        body,
        readingTimeMinutes: calculateReadingTime(body),
      };
    }),
  );
}
