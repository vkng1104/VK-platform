import { describe, expect, it, vi } from "vitest";

import { ApiError, getJson, postJson } from "./api";

const baseUrl = "https://api.example.test";

describe("getJson", () => {
  it("maps a structured error response to ApiError", async () => {
    const fetcher = vi.fn(async () =>
      Response.json(
        {
          code: "PROJECT_NOT_FOUND",
          message: "The requested project was not found.",
        },
        { status: 404 },
      ),
    );

    const request = getJson("/api/v1/projects/missing", {
      baseUrl,
      fetcher: fetcher as typeof fetch,
    });

    await expect(request).rejects.toBeInstanceOf(ApiError);
    await expect(request).rejects.toMatchObject({
      code: "PROJECT_NOT_FOUND",
      message: "The requested project was not found.",
      status: 404,
    });
  });

  it("uses safe defaults when an error response is not JSON", async () => {
    const fetcher = vi.fn(async () =>
      new Response("upstream unavailable", { status: 502 }),
    );

    await expect(
      getJson("/api/v1/projects", {
        baseUrl,
        fetcher: fetcher as typeof fetch,
      }),
    ).rejects.toEqual(
      expect.objectContaining({
        code: "API_REQUEST_FAILED",
        message: "The API request failed.",
        status: 502,
      }),
    );
  });

  it("posts JSON with bounded server-side request defaults", async () => {
    const fetcher = vi.fn(async () => Response.json({ retrieval: { matches: [] } }));

    await postJson("/api/v1/ai/retrievals", { query: "architecture" }, {
      baseUrl,
      fetcher: fetcher as typeof fetch,
    });

    expect(fetcher).toHaveBeenCalledWith(
      new URL("https://api.example.test/api/v1/ai/retrievals"),
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ query: "architecture" }),
        headers: expect.objectContaining({ "content-type": "application/json" }),
      }),
    );
  });
});
