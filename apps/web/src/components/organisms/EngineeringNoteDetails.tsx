import Link from "next/link";

import { formatContentDate } from "@/features/blog/format";
import type { EngineeringNoteDetail } from "@/features/blog/model";

import { ContentTag } from "../atoms/ContentTag";
import { MarkdownContent } from "../molecules/MarkdownContent";

export interface EngineeringNoteDetailsProps {
  note: EngineeringNoteDetail;
}

export function EngineeringNoteDetails({
  note,
}: Readonly<EngineeringNoteDetailsProps>) {
  return (
    <>
      <Link
        className="text-sm font-medium text-sky-300 transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
        href="/blog"
      >
        <span aria-hidden="true">←</span> All engineering notes
      </Link>

      <article className="mx-auto mt-10 max-w-4xl">
        <header className="border-b border-white/10 pb-10">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-2 font-mono text-xs uppercase tracking-[0.14em] text-slate-500">
            <time dateTime={note.publishedAt}>
              Published {formatContentDate(note.publishedAt)}
            </time>
            <span aria-hidden="true">·</span>
            <span>{note.readingTimeMinutes} min read</span>
            {note.updatedAt && (
              <>
                <span aria-hidden="true">·</span>
                <time dateTime={note.updatedAt}>
                  Updated {formatContentDate(note.updatedAt)}
                </time>
              </>
            )}
          </div>
          <h1 className="mt-5 text-4xl font-semibold tracking-tight text-white sm:text-6xl">
            {note.title}
          </h1>
          <p className="mt-6 text-xl leading-8 text-slate-300">{note.summary}</p>
          <ul className="mt-7 flex flex-wrap gap-2" aria-label="Tags">
            {note.tags.map((tag) => (
              <li key={tag}>
                <ContentTag label={tag} />
              </li>
            ))}
          </ul>
        </header>

        <div className="mt-10">
          <MarkdownContent content={note.body} />
        </div>

        {note.topics.length > 0 && (
          <aside className="mt-14 rounded-3xl border border-sky-300/20 bg-sky-300/[0.05] p-6 sm:p-8">
            <h2 className="text-xl font-semibold text-white">
              Continue through the knowledge graph
            </h2>
            <ul className="mt-4 flex flex-wrap gap-x-6 gap-y-3">
              {note.topics.map((topic) => (
                <li key={topic.slug}>
                  <Link
                    className="text-sm font-medium text-sky-300 transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
                    href={`/knowledge/${topic.slug}`}
                  >
                    {topic.title} <span aria-hidden="true">→</span>
                  </Link>
                </li>
              ))}
            </ul>
          </aside>
        )}
      </article>
    </>
  );
}
