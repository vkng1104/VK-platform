import { afterEach, describe, expect, it, vi } from "vitest";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

vi.mock("server-only", () => ({}));

import { loadEngineeringNoteRecords } from "./content.server";

const temporaryDirectories: string[] = [];

async function createBlogDirectory(
  index: unknown,
  bodies: Record<string, string>,
): Promise<string> {
  const directory = await mkdtemp(path.join(tmpdir(), "vk-blog-test-"));
  temporaryDirectories.push(directory);
  await mkdir(path.join(directory, "posts"));
  await writeFile(
    path.join(directory, "index.json"),
    JSON.stringify(index),
    "utf8",
  );
  await Promise.all(
    Object.entries(bodies).map(([slug, body]) =>
      writeFile(path.join(directory, "posts", `${slug}.md`), body, "utf8"),
    ),
  );
  return directory;
}

function blogIndex() {
  return {
    notes: [
      {
        slug: "reliable-api-contracts",
        title: "Reliable API contracts",
        summary: "How explicit contracts keep services understandable.",
        status: "published",
        published_at: "2026-10-08",
        updated_at: null,
        tags: ["Go"],
        topic_slugs: ["api-design"],
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

describe("engineering note content loader", () => {
  it("loads the Markdown body and derives reading time", async () => {
    const directory = await createBlogDirectory(blogIndex(), {
      "reliable-api-contracts": "word ".repeat(221),
    });

    const records = await loadEngineeringNoteRecords(directory);

    expect(records[0].body).toContain("word word");
    expect(records[0].readingTimeMinutes).toBe(2);
  });

  it("rejects an empty body with a focused source", async () => {
    const directory = await createBlogDirectory(blogIndex(), {
      "reliable-api-contracts": "\n",
    });

    await expect(loadEngineeringNoteRecords(directory)).rejects.toThrow(
      "reliable-api-contracts.md: body must be non-empty",
    );
  });
});
