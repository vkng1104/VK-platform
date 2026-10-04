import { getJson, type ApiRequestOptions } from "@/lib/api";

export interface ProjectSummaryDto {
  slug: string;
  title: string;
  summary: string;
  period: string;
  role: string;
  technologies: string[];
  featured: boolean;
  repository_url: string | null;
  live_url: string | null;
}

export interface ProjectDetailDto extends ProjectSummaryDto {
  content_markdown: string;
}

interface ProjectListResponseDto {
  projects: ProjectSummaryDto[];
}

interface ProjectDetailResponseDto {
  project: ProjectDetailDto;
}

export interface ListProjectsOptions extends ApiRequestOptions {
  featured?: boolean;
}

export async function fetchProjects({
  featured,
  ...requestOptions
}: ListProjectsOptions = {}): Promise<ProjectSummaryDto[]> {
  const query = featured === undefined ? "" : `?featured=${featured}`;
  const response = await getJson<ProjectListResponseDto>(
    `/api/v1/projects${query}`,
    requestOptions,
  );

  return response.projects;
}

export async function fetchProject(
  slug: string,
  requestOptions: ApiRequestOptions = {},
): Promise<ProjectDetailDto> {
  const response = await getJson<ProjectDetailResponseDto>(
    `/api/v1/projects/${encodeURIComponent(slug)}`,
    requestOptions,
  );

  return response.project;
}
