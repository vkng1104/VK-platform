import { postJson, type ApiRequestOptions } from "@/lib/api";

import type { VectorSearchMatch } from "./model";

interface RetrievalMatchDto {
  chunk_id: string;
  title: string;
  heading: string;
  excerpt: string;
  source_url: string;
  score: number;
}

interface RetrievalEnvelopeDto {
  retrieval: {
    query: string;
    matches: RetrievalMatchDto[];
  };
}

export async function retrievePublishedContent(
  query: string,
  requestOptions: ApiRequestOptions = {},
): Promise<VectorSearchMatch[]> {
  const response = await postJson<RetrievalEnvelopeDto, { query: string; top_k: number }>(
    "/api/v1/ai/retrievals",
    { query, top_k: 5 },
    requestOptions,
  );

  return response.retrieval.matches.map((match) => ({
    chunkId: match.chunk_id,
    title: match.title,
    heading: match.heading,
    excerpt: match.excerpt,
    sourceUrl: match.source_url,
    score: match.score,
  }));
}
