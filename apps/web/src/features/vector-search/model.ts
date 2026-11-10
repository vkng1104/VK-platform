export interface VectorSearchMatch {
  chunkId: string;
  title: string;
  heading: string;
  excerpt: string;
  sourceUrl: string;
  score: number;
}

interface IdleState {
  status: "idle";
  query: "";
}

interface SuccessState {
  status: "success";
  query: string;
  matches: VectorSearchMatch[];
}

interface ErrorState {
  status: "error";
  query: string;
  error: string;
}

export type VectorSearchActionState = IdleState | SuccessState | ErrorState;

export const initialVectorSearchState: VectorSearchActionState = {
  status: "idle",
  query: "",
};
