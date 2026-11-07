import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  state: undefined as unknown,
  pending: false,
}));

vi.mock("react", async (importOriginal) => {
  const original = await importOriginal<typeof import("react")>();
  return {
    ...original,
    useActionState: (_action: unknown, initialState: unknown) => [
      mocks.state ?? initialState,
      vi.fn(),
      mocks.pending,
    ],
  };
});

vi.mock("@/features/vector-search/actions", () => ({ searchPublishedContent: vi.fn() }));

import { VectorSearchLab } from "./VectorSearchLab";

afterEach(() => {
  mocks.state = undefined;
  mocks.pending = false;
});

describe("VectorSearchLab", () => {
  it("makes the retrieval-only boundary explicit", () => {
    const markup = renderToStaticMarkup(<VectorSearchLab />);

    expect(markup).toContain("Retrieval only");
    expect(markup).toContain("Published project content");
    expect(markup).not.toContain("AI-generated answer");
  });

  it("renders empty evidence without inventing an answer", () => {
    mocks.state = { status: "success", query: "unknown", matches: [] };

    const markup = renderToStaticMarkup(<VectorSearchLab />);

    expect(markup).toContain("No published passage met the relevance threshold");
  });
});
