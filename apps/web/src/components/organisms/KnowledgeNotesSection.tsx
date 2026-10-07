import Link from "next/link";

export function KnowledgeNotesSection() {
  return (
    <section className="border-t border-white/10">
      <div className="mx-auto grid w-full max-w-6xl gap-6 px-6 py-16 sm:px-10 sm:py-20 lg:grid-cols-2 lg:px-12">
        <article className="rounded-3xl border border-white/10 bg-white/[0.035] p-7 sm:p-9">
          <p className="font-mono text-sm uppercase tracking-[0.2em] text-sky-300">
            Knowledge graph
          </p>
          <h2 className="mt-4 text-3xl font-semibold tracking-tight text-white">
            Follow the ideas behind the systems.
          </h2>
          <p className="mt-4 leading-7 text-slate-400">
            Explore backend, data, infrastructure, and AI topics through their
            practical relationships.
          </p>
          <Link
            className="mt-7 inline-block text-sm font-medium text-sky-300 transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
            href="/knowledge"
          >
            Explore knowledge <span aria-hidden="true">→</span>
          </Link>
        </article>

        <article className="rounded-3xl border border-white/10 bg-white/[0.035] p-7 sm:p-9">
          <p className="font-mono text-sm uppercase tracking-[0.2em] text-sky-300">
            Engineering notes
          </p>
          <h2 className="mt-4 text-3xl font-semibold tracking-tight text-white">
            Read the decisions, not only the outcome.
          </h2>
          <p className="mt-4 leading-7 text-slate-400">
            Long-form notes explain the trade-offs and boundaries behind each
            implementation.
          </p>
          <Link
            className="mt-7 inline-block text-sm font-medium text-sky-300 transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
            href="/blog"
          >
            Read engineering notes <span aria-hidden="true">→</span>
          </Link>
        </article>
      </div>
    </section>
  );
}
