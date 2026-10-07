import { cache } from "react";

import { loadPublishedContentCatalog } from "@/features/content/catalog.server";

import type {
  KnowledgeCategory,
  KnowledgeTopicDetail,
  KnowledgeTopicRecord,
  KnowledgeTopicReference,
  KnowledgeTopicSummary,
} from "./model";

function compareTopics(
  left: KnowledgeTopicRecord,
  right: KnowledgeTopicRecord,
): number {
  return left.displayOrder - right.displayOrder || left.title.localeCompare(right.title);
}

function formatCategory(category: string): string {
  return category
    .split("-")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

function buildRelationshipMap(
  topics: readonly KnowledgeTopicRecord[],
): Map<string, Set<string>> {
  const relationships = new Map(
    topics.map((topic) => [topic.slug, new Set<string>()]),
  );

  for (const topic of topics) {
    for (const relatedSlug of topic.relatedTopicSlugs) {
      relationships.get(topic.slug)?.add(relatedSlug);
      relationships.get(relatedSlug)?.add(topic.slug);
    }
  }

  return relationships;
}

function toReference(topic: KnowledgeTopicRecord): KnowledgeTopicReference {
  return { slug: topic.slug, title: topic.title };
}

export async function getKnowledgeCategories(): Promise<KnowledgeCategory[]> {
  const { notes, topics } = await loadPublishedContentCatalog();
  const relationships = buildRelationshipMap(topics);
  const noteCounts = new Map<string, number>();

  for (const note of notes) {
    for (const topicSlug of note.topicSlugs) {
      noteCounts.set(topicSlug, (noteCounts.get(topicSlug) ?? 0) + 1);
    }
  }

  const categories = new Map<string, KnowledgeTopicSummary[]>();
  for (const topic of [...topics].sort(compareTopics)) {
    const summary: KnowledgeTopicSummary = {
      slug: topic.slug,
      title: topic.title,
      summary: topic.summary,
      category: topic.category,
      concepts: topic.concepts,
      relatedTopicCount: relationships.get(topic.slug)?.size ?? 0,
      noteCount: noteCounts.get(topic.slug) ?? 0,
    };
    const categoryTopics = categories.get(topic.category) ?? [];
    categoryTopics.push(summary);
    categories.set(topic.category, categoryTopics);
  }

  return [...categories.entries()]
    .map(([id, categoryTopics]) => ({
      id,
      title: formatCategory(id),
      topics: categoryTopics,
    }))
    .sort((left, right) => left.title.localeCompare(right.title));
}

export const getKnowledgeTopicBySlug = cache(
  async (slug: string): Promise<KnowledgeTopicDetail | null> => {
    const { notes, topics } = await loadPublishedContentCatalog();
    const topic = topics.find((candidate) => candidate.slug === slug);

    if (!topic) {
      return null;
    }

    const topicBySlug = new Map(topics.map((candidate) => [candidate.slug, candidate]));
    const relationships = buildRelationshipMap(topics);
    const relatedTopics = [...(relationships.get(topic.slug) ?? [])]
      .map((relatedSlug) => topicBySlug.get(relatedSlug))
      .filter((candidate): candidate is KnowledgeTopicRecord => Boolean(candidate))
      .sort(compareTopics)
      .map(toReference);
    const relatedNotes = notes
      .filter((note) => note.topicSlugs.includes(topic.slug))
      .sort(
        (left, right) =>
          right.publishedAt.localeCompare(left.publishedAt) ||
          left.title.localeCompare(right.title),
      )
      .map((note) => ({
        slug: note.slug,
        title: note.title,
        summary: note.summary,
        publishedAt: note.publishedAt,
        readingTimeMinutes: note.readingTimeMinutes,
      }));

    return {
      slug: topic.slug,
      title: topic.title,
      summary: topic.summary,
      category: topic.category,
      concepts: topic.concepts,
      body: topic.body,
      relatedTopicCount: relatedTopics.length,
      noteCount: relatedNotes.length,
      relatedTopics,
      relatedNotes,
      relatedProjects: topic.relatedProjects,
    };
  },
);

export async function getKnowledgeTopicSlugs(): Promise<string[]> {
  const { topics } = await loadPublishedContentCatalog();
  return topics.map((topic) => topic.slug).sort();
}
