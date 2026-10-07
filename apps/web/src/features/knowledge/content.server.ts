import "server-only";

import { readFile } from "node:fs/promises";
import path from "node:path";

import { repositoryPath } from "@/lib/repository-path.server";

import type { KnowledgeTopicRecord } from "./model";
import {
  parseKnowledgeIndex,
  validatePublishedTopicRelationships,
} from "./validation";

const defaultKnowledgeDirectory = repositoryPath(
  "internal",
  "content",
  "knowledge",
);

export async function loadKnowledgeRecords(
  sourceDirectory = defaultKnowledgeDirectory,
): Promise<KnowledgeTopicRecord[]> {
  const sourceLabel =
    sourceDirectory === defaultKnowledgeDirectory
      ? "internal/content/knowledge"
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

  const metadata = parseKnowledgeIndex(
    parsedIndex,
    `${sourceLabel}/index.json`,
  );
  validatePublishedTopicRelationships(
    metadata,
    `${sourceLabel}/index.json`,
  );

  return Promise.all(
    metadata.map(async (topic) => {
      const bodyPath = path.join(
        sourceDirectory,
        "topics",
        `${topic.slug}.md`,
      );
      const body = (await readFile(bodyPath, "utf8")).trim();

      if (body === "") {
        throw new Error(
          `${sourceLabel}/topics/${topic.slug}.md: body must be non-empty`,
        );
      }

      return { ...topic, body };
    }),
  );
}
