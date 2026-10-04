import type { ProjectDetailDto, ProjectSummaryDto } from "./api";

export interface ProjectSummary {
  slug: string;
  title: string;
  summary: string;
  period: string;
  role: string;
  technologies: string[];
  featured: boolean;
  repositoryUrl?: string;
  liveUrl?: string;
}

export interface ProjectDetail extends ProjectSummary {
  contentMarkdown: string;
}

export function toProjectSummary(dto: ProjectSummaryDto): ProjectSummary {
  return {
    slug: dto.slug,
    title: dto.title,
    summary: dto.summary,
    period: dto.period,
    role: dto.role,
    technologies: [...dto.technologies],
    featured: dto.featured,
    repositoryUrl: dto.repository_url ?? undefined,
    liveUrl: dto.live_url ?? undefined,
  };
}

export function toProjectDetail(dto: ProjectDetailDto): ProjectDetail {
  return {
    ...toProjectSummary(dto),
    contentMarkdown: dto.content_markdown,
  };
}
