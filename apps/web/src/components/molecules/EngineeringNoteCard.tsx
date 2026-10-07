import Link from "next/link";

import { formatContentDate } from "@/features/blog/format";
import type { EngineeringNoteSummary } from "@/features/blog/model";

import { ContentTag } from "../atoms/ContentTag";

export interface EngineeringNoteCardProps {
  note: EngineeringNoteSummary;
}

export function EngineeringNoteCard({ note }: Readonly<EngineeringNoteCardProps>) {
  return (
    <article className="rounded-3xl border border-white/10 bg-white/[0.035] p-6 sm:p-8">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 font-mono text-xs uppercase tracking-[0.14em] text-slate-500">
        <time dateTime={note.publishedAt}>
          {formatContentDate(note.publishedAt)}
        </time>
        <span aria-hidden="true">·</span>
        <span>{note.readingTimeMinutes} min read</span>
      </div>
      <h2 className="mt-5 text-2xl font-semibold tracking-tight text-white sm:text-3xl">
        <Link
          className="transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
          href={`/blog/${note.slug}`}
        >
          {note.title}
        </Link>
      </h2>
      <p className="mt-4 max-w-3xl leading-7 text-slate-400">{note.summary}</p>
      <ul className="mt-6 flex flex-wrap gap-2" aria-label="Tags">
        {note.tags.map((tag) => (
          <li key={tag}>
            <ContentTag label={tag} />
          </li>
        ))}
      </ul>
      {note.topics.length > 0 && (
        <div className="mt-7 border-t border-white/10 pt-5">
          <p className="font-mono text-xs uppercase tracking-[0.14em] text-slate-500">
            Related knowledge
          </p>
          <ul className="mt-3 flex flex-wrap gap-x-4 gap-y-2">
            {note.topics.map((topic) => (
              <li key={topic.slug}>
                <Link
                  className="text-sm text-sky-300 transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
                  href={`/knowledge/${topic.slug}`}
                >
                  {topic.title}
                </Link>
              </li>
            ))}
          </ul>
        </div>
      )}
    </article>
  );
}
