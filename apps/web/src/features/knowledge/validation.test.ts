import { describe, expect, it } from "vitest";

import {
  parseKnowledgeIndex,
  validatePublishedTopicRelationships,
} from "./validation";

function validIndex(): { topics: Array<Record<string, unknown>> } {
  return {
    topics: [
      {
        slug: "api-design",
        title: "API Design",
        summary: "Explicit contracts and stable errors.",
        category: "backend",
        status: "published",
        concepts: ["Strict decoding", "Stable errors"],
        related_topic_slugs: ["postgresql"],
        related_projects: [{ slug: "vk-platform", title: "VK Platform" }],
        display_order: 10,
      },
      {
        slug: "postgresql",
        title: "PostgreSQL",
        summary: "Relational persistence with explicit invariants.",
        category: "data",
        status: "published",
        concepts: ["Migrations"],
        related_topic_slugs: [],
        related_projects: [],
        display_order: 20,
      },
    ],
  };
}

describe("knowledge metadata validation", () => {
  it("maps repository metadata to the topic model", () => {
    const topics = parseKnowledgeIndex(validIndex());

    expect(topics[0]).toEqual({
      slug: "api-design",
      title: "API Design",
      summary: "Explicit contracts and stable errors.",
      category: "backend",
      status: "published",
      concepts: ["Strict decoding", "Stable errors"],
      relatedTopicSlugs: ["postgresql"],
      relatedProjects: [{ slug: "vk-platform", title: "VK Platform" }],
      displayOrder: 10,
    });
  });

  it.each([
    ["invalid slug", "slug", "Bad Slug", "lowercase kebab-case"],
    ["invalid category", "category", "Backend Systems", "lowercase kebab-case"],
    ["negative display order", "display_order", -1, "non-negative integer"],
    ["duplicate concepts", "concepts", ["Errors", "Errors"], "duplicate value"],
    [
      "duplicate relationships",
      "related_topic_slugs",
      ["postgresql", "postgresql"],
      "duplicate value",
    ],
  ])("rejects %s", (_name, field, value, message) => {
    const index = validIndex();
    Object.assign(index.topics[0], { [field]: value });

    expect(() => parseKnowledgeIndex(index)).toThrow(message as string);
  });

  it("rejects self-referential topics", () => {
    const index = validIndex();
    index.topics[0].related_topic_slugs = ["api-design"];

    expect(() => parseKnowledgeIndex(index)).toThrow("must not reference itself");
  });

  it("rejects unknown metadata fields", () => {
    const index = validIndex();
    Object.assign(index.topics[0], { private_notes: "do not publish" });

    expect(() => parseKnowledgeIndex(index)).toThrow(
      "private_notes is not supported",
    );
  });

  it("rejects duplicate topic slugs", () => {
    const index = validIndex();
    index.topics.push({ ...index.topics[0] });

    expect(() => parseKnowledgeIndex(index)).toThrow("duplicate value");
  });

  it("rejects published relationships to missing or draft topics", () => {
    const topics = parseKnowledgeIndex(validIndex());
    topics[1].status = "draft";

    expect(() => validatePublishedTopicRelationships(topics)).toThrow(
      'references unavailable topic "postgresql"',
    );
  });
});
