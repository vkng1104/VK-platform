import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { ProjectDetails } from "@/components/organisms/ProjectDetails";
import { getProjectBySlug } from "@/features/projects/queries";

interface ProjectPageProps {
  params: Promise<{ slug: string }>;
}

export async function generateMetadata({
  params,
}: Readonly<ProjectPageProps>): Promise<Metadata> {
  const { slug } = await params;
  const project = await getProjectBySlug(slug);

  if (!project) {
    return { title: "Project not found" };
  }

  return {
    title: project.title,
    description: project.summary,
  };
}

export default async function ProjectPage({ params }: Readonly<ProjectPageProps>) {
  const { slug } = await params;
  const project = await getProjectBySlug(slug);

  if (!project) {
    notFound();
  }

  return (
    <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-16 sm:px-10 sm:py-24 lg:px-12">
      <ProjectDetails project={project} />
    </main>
  );
}
