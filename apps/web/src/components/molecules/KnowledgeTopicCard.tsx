import Link from "next/link";

import type { KnowledgeTopicSummary } from "@/features/knowledge/model";

import { ContentTag } from "../atoms/ContentTag";

export interface KnowledgeTopicCardProps {
  topic: KnowledgeTopicSummary;
}

export function KnowledgeTopicCard({
  topic,
}: Readonly<KnowledgeTopicCardProps>) {
  return (
    <article className="flex h-full flex-col rounded-3xl border border-white/10 bg-white/[0.035] p-6 sm:p-8">
      <h3 className="text-2xl font-semibold tracking-tight text-white">
        <Link
          className="transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
          href={`/knowledge/${topic.slug}`}
        >
          {topic.title}
        </Link>
      </h3>
      <p className="mt-4 flex-1 leading-7 text-slate-400">{topic.summary}</p>
      <ul className="mt-6 flex flex-wrap gap-2" aria-label="Key concepts">
        {topic.concepts.map((concept) => (
          <li key={concept}>
            <ContentTag label={concept} />
          </li>
        ))}
      </ul>
      {(topic.relatedTopicCount > 0 || topic.noteCount > 0) && (
        <p className="mt-7 border-t border-white/10 pt-5 font-mono text-xs uppercase tracking-[0.14em] text-slate-500">
          {topic.relatedTopicCount} related {topic.relatedTopicCount === 1 ? "topic" : "topics"}
          {" · "}
          {topic.noteCount} {topic.noteCount === 1 ? "note" : "notes"}
        </p>
      )}
    </article>
  );
}
