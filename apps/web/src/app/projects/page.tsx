import type { Metadata } from "next";

import { PageIntro } from "@/components/molecules/PageIntro";
import { ProjectGrid } from "@/components/organisms/ProjectGrid";
import { getProjects } from "@/features/projects/queries";

export const metadata: Metadata = {
  title: "Projects",
  description: "Software systems and engineering projects by VK.",
};

export default async function ProjectsPage() {
  const projects = await getProjects();

  return (
    <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-16 sm:px-10 sm:py-24 lg:px-12">
      <PageIntro
        description="Practical systems work, documented through the decisions, trade-offs, and technologies behind each build."
        eyebrow="Portfolio"
        title="Projects"
      />
      <div className="mt-12">
        <ProjectGrid
          emptyMessage="No projects are published yet."
          projects={projects}
        />
      </div>
    </main>
  );
}
