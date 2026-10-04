import Link from "next/link";

import type { ProjectSummary } from "@/features/projects/model";

import { ProjectGrid } from "./ProjectGrid";

export interface FeaturedProjectsSectionProps {
  projects: ProjectSummary[];
}

export function FeaturedProjectsSection({
  projects,
}: Readonly<FeaturedProjectsSectionProps>) {
  return (
    <section className="border-t border-white/10 bg-slate-950/45">
      <div className="mx-auto w-full max-w-6xl px-6 py-16 sm:px-10 sm:py-20 lg:px-12">
        <div className="flex flex-col gap-5 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p className="font-mono text-sm uppercase tracking-[0.2em] text-sky-300">
              Selected work
            </p>
            <h2 className="mt-3 text-3xl font-semibold tracking-tight text-white sm:text-4xl">
              Systems built to be understood.
            </h2>
          </div>
          <Link
            className="w-fit text-sm font-medium text-sky-300 transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
            href="/projects"
          >
            View all projects <span aria-hidden="true">→</span>
          </Link>
        </div>
        <div className="mt-10">
          <ProjectGrid
            emptyMessage="Project stories are being prepared."
            projects={projects}
          />
        </div>
      </div>
    </section>
  );
}
