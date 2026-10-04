import type { ProjectSummary } from "@/features/projects/model";

import { ProjectCard } from "../molecules/ProjectCard";

export interface ProjectGridProps {
  emptyMessage: string;
  projects: ProjectSummary[];
}

export function ProjectGrid({
  emptyMessage,
  projects,
}: Readonly<ProjectGridProps>) {
  if (projects.length === 0) {
    return (
      <p className="rounded-3xl border border-dashed border-white/15 p-8 text-slate-400">
        {emptyMessage}
      </p>
    );
  }

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      {projects.map((project) => (
        <ProjectCard key={project.slug} project={project} />
      ))}
    </div>
  );
}
