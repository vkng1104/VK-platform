import Link from "next/link";

import type { VectorSearchMatch } from "@/features/vector-search/model";

interface VectorSearchResultProps {
  match: VectorSearchMatch;
  rank: number;
}

export function VectorSearchResult({ match, rank }: VectorSearchResultProps) {
  return (
    <li className="rounded-2xl border border-white/10 bg-white/[0.035] p-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="font-mono text-xs uppercase tracking-[0.18em] text-sky-300">
          Match {rank}
        </p>
        <p className="font-mono text-xs text-slate-400">
          Cosine score {match.score.toFixed(4)}
        </p>
      </div>
      <h2 className="mt-4 text-xl font-semibold text-white">{match.title}</h2>
      <p className="mt-1 text-sm text-slate-400">{match.heading}</p>
      <p className="mt-4 whitespace-pre-line leading-7 text-slate-300">{match.excerpt}</p>
      <Link
        className="mt-5 inline-flex text-sm font-medium text-sky-300 transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
        href={match.sourceUrl}
      >
        Open source project <span aria-hidden="true">→</span>
      </Link>
    </li>
  );
}
