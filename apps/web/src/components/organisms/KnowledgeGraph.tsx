import type { KnowledgeCategory } from "@/features/knowledge/model";

import { KnowledgeTopicCard } from "../molecules/KnowledgeTopicCard";

export interface KnowledgeGraphProps {
  categories: KnowledgeCategory[];
}

export function KnowledgeGraph({
  categories,
}: Readonly<KnowledgeGraphProps>) {
  if (categories.length === 0) {
    return (
      <p className="rounded-3xl border border-dashed border-white/15 p-8 text-slate-400">
        Knowledge topics are being prepared.
      </p>
    );
  }

  return (
    <div className="space-y-14">
      {categories.map((category) => (
        <section key={category.id} aria-labelledby={`category-${category.id}`}>
          <div className="flex items-center gap-4">
            <h2
              className="text-2xl font-semibold tracking-tight text-white"
              id={`category-${category.id}`}
            >
              {category.title}
            </h2>
            <span className="h-px flex-1 bg-white/10" aria-hidden="true" />
          </div>
          <div className="mt-6 grid gap-6 lg:grid-cols-2">
            {category.topics.map((topic) => (
              <KnowledgeTopicCard key={topic.slug} topic={topic} />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
