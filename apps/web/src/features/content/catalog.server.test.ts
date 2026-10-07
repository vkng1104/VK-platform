import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import type { EngineeringNoteRecord } from "@/features/blog/model";
import type { KnowledgeTopicRecord } from "@/features/knowledge/model";

import { buildPublishedContentCatalog } from "./catalog.server";

function topic(
  slug: string,
  status: "draft" | "published" = "published",
): KnowledgeTopicRecord {
  return {
    slug,
    title: slug,
    summary: `${slug} summary`,
    category: "backend",
    status,
    concepts: ["Concept"],
    relatedTopicSlugs: [],
    relatedProjects: [],
    displayOrder: 0,
    body: "Topic body",
  };
}

function note(
  slug: string,
  topicSlugs: string[],
  status: "draft" | "published" = "published",
): EngineeringNoteRecord {
  return {
    slug,
    title: slug,
    summary: `${slug} summary`,
    status,
    publishedAt: "2026-10-08",
    tags: ["Go"],
    topicSlugs,
    body: "Note body",
    readingTimeMinutes: 1,
  };
}

describe("published content catalog", () => {
  it("excludes draft topics and notes", () => {
    const catalog = buildPublishedContentCatalog(
      [topic("published-topic"), topic("draft-topic", "draft")],
      [note("published-note", ["published-topic"]), note("draft-note", [], "draft")],
    );

    expect(catalog.topics.map((item) => item.slug)).toEqual(["published-topic"]);
    expect(catalog.notes.map((item) => item.slug)).toEqual(["published-note"]);
  });

  it("rejects a published note linked to a draft topic", () => {
    expect(() =>
      buildPublishedContentCatalog(
        [topic("draft-topic", "draft")],
        [note("published-note", ["draft-topic"])],
      ),
    ).toThrow('references unavailable topic "draft-topic"');
  });
});
