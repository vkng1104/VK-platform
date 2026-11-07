import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api";

const mocks = vi.hoisted(() => ({
  retrieve: vi.fn(),
}));

vi.mock("./api", () => ({
  retrievePublishedContent: mocks.retrieve,
}));

import { searchPublishedContent } from "./actions";
import { initialVectorSearchState } from "./model";

function form(values: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(values)) {
    data.set(key, value);
  }
  return data;
}

describe("vector-search action", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.retrieve.mockResolvedValue([]);
  });

  it("normalizes valid queries before retrieval", async () => {
    const result = await searchPublishedContent(
      initialVectorSearchState,
      form({ query: "  architecture  " }),
    );

    expect(mocks.retrieve).toHaveBeenCalledWith("architecture");
    expect(result).toEqual({ status: "success", query: "architecture", matches: [] });
  });

  it("rejects empty and unexpected fields without calling the API", async () => {
    const empty = await searchPublishedContent(initialVectorSearchState, form({ query: " " }));
    const unexpected = await searchPublishedContent(
      initialVectorSearchState,
      form({ query: "architecture", top_k: "100" }),
    );

    expect(empty.status).toBe("error");
    expect(unexpected.status).toBe("error");
    expect(mocks.retrieve).not.toHaveBeenCalled();
  });

  it.each([
    ["AI_BUSY", "The search runtime is busy"],
    ["AI_TIMEOUT", "The search took too long"],
    ["AI_UNAVAILABLE", "Vector search is unavailable"],
  ])("maps %s to safe UI copy", async (code, expectedMessage) => {
    mocks.retrieve.mockRejectedValue(new ApiError(503, code, "internal wording"));

    const result = await searchPublishedContent(
      initialVectorSearchState,
      form({ query: "architecture" }),
    );

    expect(result).toMatchObject({ status: "error", query: "architecture" });
    expect(result.status === "error" && result.error).toContain(expectedMessage);
    expect(JSON.stringify(result)).not.toContain("internal wording");
  });
});
