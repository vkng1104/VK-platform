import { describe, expect, it } from "vitest";

import { parseBlogIndex, validatePublishedNoteTopics } from "./validation";

function validIndex(): { notes: Array<Record<string, unknown>> } {
  return {
    notes: [
      {
        slug: "reliable-api-contracts",
        title: "Reliable API contracts",
        summary: "How explicit contracts keep services understandable.",
        status: "published",
        published_at: "2026-10-08",
        updated_at: null,
        tags: ["Go", "API design"],
        topic_slugs: ["api-design"],
      },
    ],
  };
}

describe("blog metadata validation", () => {
  it("maps repository metadata to the note model", () => {
    expect(parseBlogIndex(validIndex())).toEqual([
      {
        slug: "reliable-api-contracts",
        title: "Reliable API contracts",
        summary: "How explicit contracts keep services understandable.",
        status: "published",
        publishedAt: "2026-10-08",
        updatedAt: undefined,
        tags: ["Go", "API design"],
        topicSlugs: ["api-design"],
      },
    ]);
  });

  it.each([
    ["invalid slug", { slug: "Bad Slug" }, "lowercase kebab-case"],
    ["invalid calendar date", { published_at: "2026-02-30" }, "valid YYYY-MM-DD"],
    ["unknown status", { status: "scheduled" }, '"draft" or "published"'],
    ["duplicate tags", { tags: ["Go", "Go"] }, "duplicate value"],
    ["duplicate topics", { topic_slugs: ["api-design", "api-design"] }, "duplicate value"],
  ])("rejects %s", (_name, change, message) => {
    const index = validIndex();
    Object.assign(index.notes[0], change);

    expect(() => parseBlogIndex(index)).toThrow(message);
  });

  it("rejects an updated date before publication", () => {
    const index = validIndex();
    index.notes[0].updated_at = "2026-10-07";

    expect(() => parseBlogIndex(index)).toThrow(
      "updated_at must not be before published_at",
    );
  });

  it("rejects duplicate note slugs", () => {
    const index = validIndex();
    index.notes.push({ ...index.notes[0] });

    expect(() => parseBlogIndex(index)).toThrow("duplicate value");
  });

  it("rejects published notes that reference unavailable topics", () => {
    const notes = parseBlogIndex(validIndex());

    expect(() => validatePublishedNoteTopics(notes, new Set())).toThrow(
      'references unavailable topic "api-design"',
    );
  });

  it("does not publish relationship requirements for drafts", () => {
    const index = validIndex();
    index.notes[0].status = "draft";
    const notes = parseBlogIndex(index);

    expect(() => validatePublishedNoteTopics(notes, new Set())).not.toThrow();
  });
});
