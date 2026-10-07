import "server-only";

import { cache } from "react";

import { loadEngineeringNoteRecords } from "@/features/blog/content.server";
import type { EngineeringNoteRecord } from "@/features/blog/model";
import { validatePublishedNoteTopics } from "@/features/blog/validation";
import { loadKnowledgeRecords } from "@/features/knowledge/content.server";
import type { KnowledgeTopicRecord } from "@/features/knowledge/model";

export interface PublishedContentCatalog {
  notes: EngineeringNoteRecord[];
  topics: KnowledgeTopicRecord[];
}

export function buildPublishedContentCatalog(
  topics: readonly KnowledgeTopicRecord[],
  notes: readonly EngineeringNoteRecord[],
): PublishedContentCatalog {
  const publishedTopics = topics.filter(
    (topic) => topic.status === "published",
  );
  const publishedTopicSlugs = new Set(
    publishedTopics.map((topic) => topic.slug),
  );

  validatePublishedNoteTopics(notes, publishedTopicSlugs);

  return {
    topics: publishedTopics,
    notes: notes.filter((note) => note.status === "published"),
  };
}

export const loadPublishedContentCatalog = cache(
  async (): Promise<PublishedContentCatalog> => {
    const [topics, notes] = await Promise.all([
      loadKnowledgeRecords(),
      loadEngineeringNoteRecords(),
    ]);

    return buildPublishedContentCatalog(topics, notes);
  },
);
