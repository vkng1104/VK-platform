import type { Metadata } from "next";

import { PageIntro } from "@/components/molecules/PageIntro";
import { VectorSearchLab } from "@/components/organisms/VectorSearchLab";

export const metadata: Metadata = {
  title: "Vector Search Lab",
  description: "Inspect semantic retrieval over VK Platform's published project catalog.",
};

export default function VectorSearchPage() {
  return (
    <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-16 sm:px-10 sm:py-24 lg:px-12">
      <PageIntro
        description="A transparent first AI milestone: embed a question, compare it with published project passages, and inspect the ranked evidence before answer generation is added."
        eyebrow="AI engineering lab"
        title="Vector search"
      />
      <VectorSearchLab />
    </main>
  );
}
