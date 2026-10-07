import Link from "next/link";

import type { KnowledgeTopicDetail } from "@/features/knowledge/model";

import { ContentTag } from "../atoms/ContentTag";
import { MarkdownContent } from "../molecules/MarkdownContent";

export interface KnowledgeTopicDetailsProps {
  topic: KnowledgeTopicDetail;
}

export function KnowledgeTopicDetails({
  topic,
}: Readonly<KnowledgeTopicDetailsProps>) {
  return (
    <>
      <Link
        className="text-sm font-medium text-sky-300 transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
        href="/knowledge"
      >
        <span aria-hidden="true">←</span> All knowledge topics
      </Link>

      <article className="mt-10 grid gap-12 lg:grid-cols-[minmax(0,1fr)_20rem] lg:gap-16">
        <div>
          <p className="font-mono text-sm uppercase tracking-[0.2em] text-sky-300">
            {topic.category}
          </p>
          <h1 className="mt-4 text-4xl font-semibold tracking-tight text-white sm:text-6xl">
            {topic.title}
          </h1>
          <p className="mt-6 max-w-3xl text-xl leading-8 text-slate-300">
            {topic.summary}
          </p>
          <div className="mt-14 border-t border-white/10 pt-10">
            <MarkdownContent content={topic.body} />
          </div>
        </div>

        <aside className="h-fit space-y-8 rounded-3xl border border-white/10 bg-white/[0.035] p-6 lg:sticky lg:top-28">
          <section aria-labelledby="topic-concepts">
            <h2
              className="font-mono text-xs uppercase tracking-[0.18em] text-slate-500"
              id="topic-concepts"
            >
              Key concepts
            </h2>
            <ul className="mt-3 flex flex-wrap gap-2">
              {topic.concepts.map((concept) => (
                <li key={concept}>
                  <ContentTag label={concept} />
                </li>
              ))}
            </ul>
          </section>

          {topic.relatedTopics.length > 0 && (
            <section
              className="border-t border-white/10 pt-6"
              aria-labelledby="related-topics"
            >
              <h2
                className="font-mono text-xs uppercase tracking-[0.18em] text-slate-500"
                id="related-topics"
              >
                Related topics
              </h2>
              <ul className="mt-3 space-y-2">
                {topic.relatedTopics.map((relatedTopic) => (
                  <li key={relatedTopic.slug}>
                    <Link
                      className="text-sm text-sky-300 transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
                      href={`/knowledge/${relatedTopic.slug}`}
                    >
                      {relatedTopic.title}
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {topic.relatedNotes.length > 0 && (
            <section
              className="border-t border-white/10 pt-6"
              aria-labelledby="related-notes"
            >
              <h2
                className="font-mono text-xs uppercase tracking-[0.18em] text-slate-500"
                id="related-notes"
              >
                Engineering notes
              </h2>
              <ul className="mt-3 space-y-4">
                {topic.relatedNotes.map((note) => (
                  <li key={note.slug}>
                    <Link
                      className="text-sm font-medium text-sky-300 transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
                      href={`/blog/${note.slug}`}
                    >
                      {note.title}
                    </Link>
                    <p className="mt-1 text-xs text-slate-500">
                      {note.readingTimeMinutes} min read
                    </p>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {topic.relatedProjects.length > 0 && (
            <section
              className="border-t border-white/10 pt-6"
              aria-labelledby="related-projects"
            >
              <h2
                className="font-mono text-xs uppercase tracking-[0.18em] text-slate-500"
                id="related-projects"
              >
                Used in
              </h2>
              <ul className="mt-3 space-y-2">
                {topic.relatedProjects.map((project) => (
                  <li key={project.slug}>
                    <Link
                      className="text-sm text-sky-300 transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
                      href={`/projects/${project.slug}`}
                    >
                      {project.title}
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </aside>
      </article>
    </>
  );
}
