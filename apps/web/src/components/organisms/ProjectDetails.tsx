import Link from "next/link";

import type { ProjectDetail } from "@/features/projects/model";

import { MarkdownContent } from "../molecules/MarkdownContent";
import { TechnologyTag } from "../atoms/TechnologyTag";

export interface ProjectDetailsProps {
  project: ProjectDetail;
}

export function ProjectDetails({ project }: Readonly<ProjectDetailsProps>) {
  return (
    <>
      <Link
        className="text-sm font-medium text-sky-300 transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
        href="/projects"
      >
        <span aria-hidden="true">←</span> All projects
      </Link>

      <article className="mt-10 grid gap-12 lg:grid-cols-[minmax(0,1fr)_18rem] lg:gap-16">
        <div>
          <p className="font-mono text-sm uppercase tracking-[0.2em] text-sky-300">
            {project.role}
          </p>
          <h1 className="mt-4 text-4xl font-semibold tracking-tight text-white sm:text-6xl">
            {project.title}
          </h1>
          <p className="mt-6 max-w-3xl text-xl leading-8 text-slate-300">
            {project.summary}
          </p>

          <div className="mt-14 border-t border-white/10 pt-10">
            <MarkdownContent content={project.contentMarkdown} />
          </div>
        </div>

        <aside className="h-fit rounded-3xl border border-white/10 bg-white/[0.035] p-6 lg:sticky lg:top-28">
          <dl className="space-y-6">
            <div>
              <dt className="font-mono text-xs uppercase tracking-[0.18em] text-slate-500">
                Period
              </dt>
              <dd className="mt-2 text-slate-200">{project.period}</dd>
            </div>
            <div>
              <dt className="font-mono text-xs uppercase tracking-[0.18em] text-slate-500">
                Technologies
              </dt>
              <dd>
                <ul className="mt-3 flex flex-wrap gap-2">
                  {project.technologies.map((technology) => (
                    <li key={technology}>
                      <TechnologyTag name={technology} />
                    </li>
                  ))}
                </ul>
              </dd>
            </div>
          </dl>

          {(project.repositoryUrl || project.liveUrl) && (
            <div className="mt-7 flex flex-col gap-3 border-t border-white/10 pt-6">
              {project.repositoryUrl && (
                <a
                  className="text-sm font-medium text-sky-300 transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
                  href={project.repositoryUrl}
                  rel="noreferrer"
                  target="_blank"
                >
                  View repository <span aria-hidden="true">↗</span>
                </a>
              )}
              {project.liveUrl && (
                <a
                  className="text-sm font-medium text-sky-300 transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
                  href={project.liveUrl}
                  rel="noreferrer"
                  target="_blank"
                >
                  Visit project <span aria-hidden="true">↗</span>
                </a>
              )}
            </div>
          )}
        </aside>
      </article>
    </>
  );
}
