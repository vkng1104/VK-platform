export type KnowledgeTopicStatus = "draft" | "published";

export interface RelatedProject {
  slug: string;
  title: string;
}

export interface KnowledgeTopicMetadata {
  slug: string;
  title: string;
  summary: string;
  category: string;
  status: KnowledgeTopicStatus;
  concepts: string[];
  relatedTopicSlugs: string[];
  relatedProjects: RelatedProject[];
  displayOrder: number;
}

export interface KnowledgeTopicRecord extends KnowledgeTopicMetadata {
  body: string;
}

export interface KnowledgeTopicReference {
  slug: string;
  title: string;
}

export interface KnowledgeTopicSummary extends KnowledgeTopicReference {
  summary: string;
  category: string;
  concepts: string[];
  relatedTopicCount: number;
  noteCount: number;
}

export interface KnowledgeCategory {
  id: string;
  title: string;
  topics: KnowledgeTopicSummary[];
}

export interface KnowledgeTopicDetail extends KnowledgeTopicSummary {
  body: string;
  relatedTopics: KnowledgeTopicReference[];
  relatedNotes: Array<{
    slug: string;
    title: string;
    summary: string;
    publishedAt: string;
    readingTimeMinutes: number;
  }>;
  relatedProjects: RelatedProject[];
}
