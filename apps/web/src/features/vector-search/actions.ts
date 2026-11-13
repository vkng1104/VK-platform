"use server";

import { ApiError } from "@/lib/api";
import { hasOnlyExpectedFormFields } from "@/lib/form-fields";

import { retrievePublishedContent } from "./api";
import type { VectorSearchActionState } from "./model";

const invalidQueryMessage = "Enter a question between 1 and 500 characters.";

export async function searchPublishedContent(
  _previousState: VectorSearchActionState,
  formData: FormData,
): Promise<VectorSearchActionState> {
  if (!hasOnlyExpectedFormFields(formData, ["query"])) {
    return { status: "error", query: "", error: invalidQueryMessage };
  }
  const value = formData.get("query");
  const query = typeof value === "string" ? value.trim() : "";
  if (!query || [...query].length > 500) {
    return { status: "error", query, error: invalidQueryMessage };
  }

  try {
    const matches = await retrievePublishedContent(query);
    return { status: "success", query, matches };
  } catch (error) {
    if (error instanceof ApiError) {
      switch (error.code) {
        case "INVALID_AI_QUERY":
          return { status: "error", query, error: invalidQueryMessage };
        case "AI_BUSY":
          return {
            status: "error",
            query,
            error: "The search runtime is busy. Wait a moment and try again.",
          };
        case "AI_TIMEOUT":
          return {
            status: "error",
            query,
            error: "The search took too long. Please try again.",
          };
      }
    }
    return {
      status: "error",
      query,
      error: "Vector search is unavailable right now. The rest of the portfolio still works.",
    };
  }
}
