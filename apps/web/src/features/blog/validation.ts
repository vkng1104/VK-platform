import {
  assertOnlyKeys,
  ensureUnique,
  fail,
  readArray,
  readDate,
  readOptionalText,
  readRecord,
  readSlug,
  readStatus,
  readText,
  readTextList,
} from "@/features/content/validation";

import type { EngineeringNoteMetadata } from "./model";

const noteKeys = [
  "slug",
  "title",
  "summary",
  "status",
  "published_at",
  "updated_at",
  "tags",
  "topic_slugs",
] as const;

function parseNote(
  value: unknown,
  source: string,
  field: string,
): EngineeringNoteMetadata {
  const note = readRecord(value, source, field);
  assertOnlyKeys(note, noteKeys, source, field);

  const publishedAt = readDate(
    note.published_at,
    source,
    `${field}.published_at`,
  );
  const rawUpdatedAt = readOptionalText(
    note.updated_at,
    source,
    `${field}.updated_at`,
  );
  const updatedAt = rawUpdatedAt
    ? readDate(rawUpdatedAt, source, `${field}.updated_at`)
    : undefined;
  const tags = readTextList(note.tags, source, `${field}.tags`, false);
  const topicSlugs = readArray(
    note.topic_slugs,
    source,
    `${field}.topic_slugs`,
  ).map((topicSlug, index) =>
    readSlug(topicSlug, source, `${field}.topic_slugs[${index}]`),
  );

  if (updatedAt && updatedAt < publishedAt) {
    fail(source, `${field}.updated_at`, "must not be before published_at");
  }

  ensureUnique(tags, source, `${field}.tags`);
  ensureUnique(topicSlugs, source, `${field}.topic_slugs`);

  return {
    slug: readSlug(note.slug, source, `${field}.slug`),
    title: readText(note.title, source, `${field}.title`),
    summary: readText(note.summary, source, `${field}.summary`),
    status: readStatus(note.status, source, `${field}.status`),
    publishedAt,
    updatedAt,
    tags,
    topicSlugs,
  };
}

export function parseBlogIndex(
  value: unknown,
  source = "blog index",
): EngineeringNoteMetadata[] {
  const root = readRecord(value, source, "root");
  assertOnlyKeys(root, ["notes"], source, "root");

  const notes = readArray(root.notes, source, "notes").map((note, index) =>
    parseNote(note, source, `notes[${index}]`),
  );
  ensureUnique(
    notes.map((note) => note.slug),
    source,
    "notes",
  );

  return notes;
}

export function validatePublishedNoteTopics(
  notes: readonly EngineeringNoteMetadata[],
  publishedTopicSlugs: ReadonlySet<string>,
  source = "blog index",
): void {
  for (const note of notes) {
    if (note.status !== "published") {
      continue;
    }

    for (const topicSlug of note.topicSlugs) {
      if (!publishedTopicSlugs.has(topicSlug)) {
        fail(
          source,
          `note "${note.slug}"`,
          `references unavailable topic "${topicSlug}"`,
        );
      }
    }
  }
}
