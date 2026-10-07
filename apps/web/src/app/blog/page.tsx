import type { Metadata } from "next";

import { PageIntro } from "@/components/molecules/PageIntro";
import { EngineeringNotesList } from "@/components/organisms/EngineeringNotesList";
import { getEngineeringNotes } from "@/features/blog/queries";

export const metadata: Metadata = {
  title: "Engineering notes",
  description:
    "Engineering notes about the design decisions and trade-offs behind VK Platform.",
};

export default async function BlogPage() {
  const notes = await getEngineeringNotes();

  return (
    <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-16 sm:px-10 sm:py-24 lg:px-12">
      <PageIntro
        description="Design decisions, implementation boundaries, and lessons from building software systems in public."
        eyebrow="Technical writing"
        title="Engineering notes"
      />
      <div className="mt-12">
        <EngineeringNotesList notes={notes} />
      </div>
    </main>
  );
}
