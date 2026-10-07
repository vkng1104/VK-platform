import type { KnowledgeTopicReference } from "@/features/knowledge/model";

export type EngineeringNoteStatus = "draft" | "published";

export interface EngineeringNoteMetadata {
  slug: string;
  title: string;
  summary: string;
  status: EngineeringNoteStatus;
  publishedAt: string;
  updatedAt?: string;
  tags: string[];
  topicSlugs: string[];
}

export interface EngineeringNoteRecord extends EngineeringNoteMetadata {
  body: string;
  readingTimeMinutes: number;
}

export interface EngineeringNoteSummary {
  slug: string;
  title: string;
  summary: string;
  publishedAt: string;
  updatedAt?: string;
  readingTimeMinutes: number;
  tags: string[];
  topics: KnowledgeTopicReference[];
}

export interface EngineeringNoteDetail extends EngineeringNoteSummary {
  body: string;
}
