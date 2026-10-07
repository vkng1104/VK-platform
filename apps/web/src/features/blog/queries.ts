import { cache } from "react";

import { loadPublishedContentCatalog } from "@/features/content/catalog.server";

import type {
  EngineeringNoteDetail,
  EngineeringNoteRecord,
  EngineeringNoteSummary,
} from "./model";

function compareNotes(
  left: EngineeringNoteRecord,
  right: EngineeringNoteRecord,
): number {
  return (
    right.publishedAt.localeCompare(left.publishedAt) ||
    left.title.localeCompare(right.title)
  );
}

export async function getEngineeringNotes(): Promise<EngineeringNoteSummary[]> {
  const { notes, topics } = await loadPublishedContentCatalog();
  const topicBySlug = new Map(topics.map((topic) => [topic.slug, topic]));

  return [...notes].sort(compareNotes).map((note) => ({
    slug: note.slug,
    title: note.title,
    summary: note.summary,
    publishedAt: note.publishedAt,
    updatedAt: note.updatedAt,
    readingTimeMinutes: note.readingTimeMinutes,
    tags: note.tags,
    topics: note.topicSlugs.map((slug) => {
      const topic = topicBySlug.get(slug);
      if (!topic) {
        throw new Error(`Published note "${note.slug}" has an unavailable topic`);
      }
      return { slug: topic.slug, title: topic.title };
    }),
  }));
}

export const getEngineeringNoteBySlug = cache(
  async (slug: string): Promise<EngineeringNoteDetail | null> => {
    const { notes, topics } = await loadPublishedContentCatalog();
    const note = notes.find((candidate) => candidate.slug === slug);

    if (!note) {
      return null;
    }

    const topicBySlug = new Map(topics.map((topic) => [topic.slug, topic]));

    return {
      slug: note.slug,
      title: note.title,
      summary: note.summary,
      body: note.body,
      publishedAt: note.publishedAt,
      updatedAt: note.updatedAt,
      readingTimeMinutes: note.readingTimeMinutes,
      tags: note.tags,
      topics: note.topicSlugs.map((topicSlug) => {
        const topic = topicBySlug.get(topicSlug);
        if (!topic) {
          throw new Error(`Published note "${note.slug}" has an unavailable topic`);
        }
        return { slug: topic.slug, title: topic.title };
      }),
    };
  },
);

export async function getEngineeringNoteSlugs(): Promise<string[]> {
  const { notes } = await loadPublishedContentCatalog();
  return notes.map((note) => note.slug).sort();
}
