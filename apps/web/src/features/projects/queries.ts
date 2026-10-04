import { cache } from "react";

import { ApiError } from "@/lib/api";

import { fetchProject, fetchProjects } from "./api";
import {
  toProjectDetail,
  toProjectSummary,
  type ProjectDetail,
  type ProjectSummary,
} from "./model";

export async function getProjects(): Promise<ProjectSummary[]> {
  return (await fetchProjects()).map(toProjectSummary);
}

export async function getFeaturedProjects(): Promise<ProjectSummary[]> {
  return (await fetchProjects({ featured: true })).map(toProjectSummary);
}

export const getProjectBySlug = cache(
  async (slug: string): Promise<ProjectDetail | null> => {
    try {
      return toProjectDetail(await fetchProject(slug));
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        return null;
      }

      throw error;
    }
  },
);
