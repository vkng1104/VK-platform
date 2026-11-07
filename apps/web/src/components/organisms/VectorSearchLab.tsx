"use client";

import { useActionState } from "react";

import { VectorSearchForm } from "@/components/molecules/VectorSearchForm";
import { VectorSearchResult } from "@/components/molecules/VectorSearchResult";
import { searchPublishedContent } from "@/features/vector-search/actions";
import { initialVectorSearchState } from "@/features/vector-search/model";

export function VectorSearchLab() {
  const [state, action, pending] = useActionState(
    searchPublishedContent,
    initialVectorSearchState,
  );

  return (
    <section className="mt-12 rounded-3xl border border-white/10 bg-slate-900/55 p-6 sm:p-8">
      <div className="flex flex-wrap items-center gap-3">
        <span className="rounded-full border border-amber-300/30 bg-amber-300/10 px-3 py-1 font-mono text-xs uppercase tracking-[0.16em] text-amber-200">
          Retrieval only
        </span>
        <span className="text-sm text-slate-400">Published project content · top 5</span>
      </div>

      <VectorSearchForm
        action={action}
        defaultQuery={state.query}
        pending={pending}
      />

      <div aria-live="polite" aria-busy={pending} className="mt-8">
        {state.status === "error" ? (
          <p className="rounded-xl border border-rose-300/25 bg-rose-300/10 p-4 text-sm text-rose-100">
            {state.error}
          </p>
        ) : null}

        {state.status === "success" && state.matches.length === 0 ? (
          <p className="rounded-xl border border-white/10 bg-slate-950/50 p-5 text-slate-300">
            No published passage met the relevance threshold. Try a question about the projects
            shown in this portfolio.
          </p>
        ) : null}

        {state.status === "success" && state.matches.length > 0 ? (
          <div>
            <p className="mb-4 text-sm text-slate-400">
              Ranked evidence for <span className="text-slate-200">“{state.query}”</span>
            </p>
            <ol className="space-y-4">
              {state.matches.map((match, index) => (
                <VectorSearchResult key={match.chunkId} match={match} rank={index + 1} />
              ))}
            </ol>
          </div>
        ) : null}
      </div>
    </section>
  );
}
