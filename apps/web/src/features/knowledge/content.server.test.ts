import { afterEach, describe, expect, it, vi } from "vitest";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

vi.mock("server-only", () => ({}));

import { loadKnowledgeRecords } from "./content.server";

const temporaryDirectories: string[] = [];

async function createKnowledgeDirectory(
  index: unknown,
  bodies: Record<string, string>,
): Promise<string> {
  const directory = await mkdtemp(path.join(tmpdir(), "vk-knowledge-test-"));
  temporaryDirectories.push(directory);
  await mkdir(path.join(directory, "topics"));
  await writeFile(
    path.join(directory, "index.json"),
    JSON.stringify(index),
    "utf8",
  );
  await Promise.all(
    Object.entries(bodies).map(([slug, body]) =>
      writeFile(path.join(directory, "topics", `${slug}.md`), body, "utf8"),
    ),
  );
  return directory;
}

function knowledgeIndex(slug = "api-design") {
  return {
    topics: [
      {
        slug,
        title: "API Design",
        summary: "Explicit contracts and stable errors.",
        category: "backend",
        status: "published",
        concepts: ["Stable errors"],
        related_topic_slugs: [],
        related_projects: [],
        display_order: 10,
      },
    ],
  };
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) =>
      rm(directory, { force: true, recursive: true }),
    ),
  );
});

describe("knowledge content loader", () => {
  it("loads a validated slug-derived Markdown body", async () => {
    const directory = await createKnowledgeDirectory(knowledgeIndex(), {
      "api-design": "## Contract\n\nKeep it explicit.",
    });

    const records = await loadKnowledgeRecords(directory);

    expect(records).toHaveLength(1);
    expect(records[0].slug).toBe("api-design");
    expect(records[0].body).toContain("Keep it explicit.");
  });

  it("rejects an empty body", async () => {
    const directory = await createKnowledgeDirectory(knowledgeIndex(), {
      "api-design": "  ",
    });

    await expect(loadKnowledgeRecords(directory)).rejects.toThrow(
      "api-design.md: body must be non-empty",
    );
  });

  it("rejects a traversal slug before reading a body", async () => {
    const directory = await createKnowledgeDirectory(knowledgeIndex("../escape"), {});

    await expect(loadKnowledgeRecords(directory)).rejects.toThrow(
      "lowercase kebab-case",
    );
  });
});
