import type { Metadata } from "next";

import { PageIntro } from "@/components/molecules/PageIntro";
import { KnowledgeGraph } from "@/components/organisms/KnowledgeGraph";
import { getKnowledgeCategories } from "@/features/knowledge/queries";

export const metadata: Metadata = {
  title: "Knowledge",
  description:
    "An interconnected map of the backend, data, infrastructure, and AI concepts behind VK Platform.",
};

export default async function KnowledgePage() {
  const categories = await getKnowledgeCategories();

  return (
    <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-16 sm:px-10 sm:py-24 lg:px-12">
      <PageIntro
        description="A practical map of engineering concepts, connected to the notes and systems where those ideas are applied."
        eyebrow="Systems knowledge"
        title="Knowledge graph"
      />
      <div className="mt-12">
        <KnowledgeGraph categories={categories} />
      </div>
    </main>
  );
}
