import {
  assertOnlyKeys,
  ensureUnique,
  fail,
  readArray,
  readNonNegativeInteger,
  readRecord,
  readSlug,
  readStatus,
  readText,
  readTextList,
} from "@/features/content/validation";

import type {
  KnowledgeTopicMetadata,
  RelatedProject,
} from "./model";

const topicKeys = [
  "slug",
  "title",
  "summary",
  "category",
  "status",
  "concepts",
  "related_topic_slugs",
  "related_projects",
  "display_order",
] as const;

function parseRelatedProject(
  value: unknown,
  source: string,
  field: string,
): RelatedProject {
  const project = readRecord(value, source, field);
  assertOnlyKeys(project, ["slug", "title"], source, field);

  return {
    slug: readSlug(project.slug, source, `${field}.slug`),
    title: readText(project.title, source, `${field}.title`),
  };
}

function parseTopic(
  value: unknown,
  source: string,
  field: string,
): KnowledgeTopicMetadata {
  const topic = readRecord(value, source, field);
  assertOnlyKeys(topic, topicKeys, source, field);

  const slug = readSlug(topic.slug, source, `${field}.slug`);
  const concepts = readTextList(
    topic.concepts,
    source,
    `${field}.concepts`,
    false,
  );
  const relatedTopicSlugs = readArray(
    topic.related_topic_slugs,
    source,
    `${field}.related_topic_slugs`,
  ).map((relatedSlug, index) =>
    readSlug(
      relatedSlug,
      source,
      `${field}.related_topic_slugs[${index}]`,
    ),
  );
  const relatedProjects = readArray(
    topic.related_projects,
    source,
    `${field}.related_projects`,
  ).map((project, index) =>
    parseRelatedProject(project, source, `${field}.related_projects[${index}]`),
  );

  ensureUnique(concepts, source, `${field}.concepts`);
  ensureUnique(relatedTopicSlugs, source, `${field}.related_topic_slugs`);
  ensureUnique(
    relatedProjects.map((project) => project.slug),
    source,
    `${field}.related_projects`,
  );

  if (relatedTopicSlugs.includes(slug)) {
    fail(source, `${field}.related_topic_slugs`, "must not reference itself");
  }

  return {
    slug,
    title: readText(topic.title, source, `${field}.title`),
    summary: readText(topic.summary, source, `${field}.summary`),
    category: readSlug(topic.category, source, `${field}.category`),
    status: readStatus(topic.status, source, `${field}.status`),
    concepts,
    relatedTopicSlugs,
    relatedProjects,
    displayOrder: readNonNegativeInteger(
      topic.display_order,
      source,
      `${field}.display_order`,
    ),
  };
}

export function parseKnowledgeIndex(
  value: unknown,
  source = "knowledge index",
): KnowledgeTopicMetadata[] {
  const root = readRecord(value, source, "root");
  assertOnlyKeys(root, ["topics"], source, "root");

  const topics = readArray(root.topics, source, "topics").map((topic, index) =>
    parseTopic(topic, source, `topics[${index}]`),
  );
  ensureUnique(
    topics.map((topic) => topic.slug),
    source,
    "topics",
  );

  return topics;
}

export function validatePublishedTopicRelationships(
  topics: readonly KnowledgeTopicMetadata[],
  source = "knowledge index",
): void {
  const publishedSlugs = new Set(
    topics
      .filter((topic) => topic.status === "published")
      .map((topic) => topic.slug),
  );

  for (const topic of topics) {
    if (topic.status !== "published") {
      continue;
    }

    for (const relatedSlug of topic.relatedTopicSlugs) {
      if (!publishedSlugs.has(relatedSlug)) {
        fail(
          source,
          `topic "${topic.slug}"`,
          `references unavailable topic "${relatedSlug}"`,
        );
      }
    }
  }
}
