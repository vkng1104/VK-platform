import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { VectorSearchForm } from "./VectorSearchForm";

describe("VectorSearchForm", () => {
  it("labels and bounds the retrieval-only question input", () => {
    const markup = renderToStaticMarkup(
      <VectorSearchForm action={vi.fn()} defaultQuery="architecture" pending={false} />,
    );

    expect(markup).toContain('name="query"');
    expect(markup).toContain('maxLength="500"');
    expect(markup).toContain("Ask about a published project");
    expect(markup).toContain("does not generate an answer yet");
  });
});
