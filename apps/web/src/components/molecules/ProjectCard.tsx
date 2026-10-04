import Link from "next/link";

import type { ProjectSummary } from "@/features/projects/model";

import { TechnologyTag } from "../atoms/TechnologyTag";

export interface ProjectCardProps {
  project: ProjectSummary;
}

export function ProjectCard({ project }: Readonly<ProjectCardProps>) {
  return (
    <Link
      aria-label={`View ${project.title} project`}
      className="group block h-full rounded-3xl focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
      href={`/projects/${project.slug}`}
    >
      <article className="flex h-full flex-col rounded-3xl border border-white/10 bg-white/[0.035] p-6 transition group-hover:-translate-y-1 group-hover:border-sky-300/35 group-hover:bg-white/[0.055] sm:p-8">
        <div className="flex flex-wrap items-center justify-between gap-3 text-xs uppercase tracking-[0.18em]">
          <span className="font-mono text-sky-300">{project.role}</span>
          <span className="text-slate-500">{project.period}</span>
        </div>
        <h2 className="mt-7 text-2xl font-semibold tracking-tight text-white">
          {project.title}
        </h2>
        <p className="mt-4 flex-1 leading-7 text-slate-400">{project.summary}</p>
        <ul className="mt-7 flex flex-wrap gap-2" aria-label="Technologies">
          {project.technologies.map((technology) => (
            <li key={technology}>
              <TechnologyTag name={technology} />
            </li>
          ))}
        </ul>
        <span className="mt-8 text-sm font-medium text-sky-300 transition group-hover:text-sky-200">
          View project <span aria-hidden="true">→</span>
        </span>
      </article>
    </Link>
  );
}
