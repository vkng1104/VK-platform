import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { EngineeringNoteDetails } from "@/components/organisms/EngineeringNoteDetails";
import {
  getEngineeringNoteBySlug,
  getEngineeringNoteSlugs,
} from "@/features/blog/queries";

interface EngineeringNotePageProps {
  params: Promise<{ slug: string }>;
}

export async function generateStaticParams() {
  const slugs = await getEngineeringNoteSlugs();
  return slugs.map((slug) => ({ slug }));
}

export async function generateMetadata({
  params,
}: Readonly<EngineeringNotePageProps>): Promise<Metadata> {
  const { slug } = await params;
  const note = await getEngineeringNoteBySlug(slug);

  if (!note) {
    return { title: "Engineering note not found" };
  }

  return {
    title: note.title,
    description: note.summary,
  };
}

export default async function EngineeringNotePage({
  params,
}: Readonly<EngineeringNotePageProps>) {
  const { slug } = await params;
  const note = await getEngineeringNoteBySlug(slug);

  if (!note) {
    notFound();
  }

  return (
    <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-16 sm:px-10 sm:py-24 lg:px-12">
      <EngineeringNoteDetails note={note} />
    </main>
  );
}
