import { describe, expect, it, vi } from "vitest";

import { checkApiHealth } from "./api-health";

const baseUrl = "https://api.example.test";

describe("checkApiHealth", () => {
  it("maps a healthy API response to operational", async () => {
    const fetcher = vi.fn(async () =>
      Response.json({ status: "ok", service: "api" }),
    );

    const health = await checkApiHealth({
      baseUrl,
      fetcher: fetcher as typeof fetch,
    });

    expect(health).toEqual({
      status: "operational",
      service: "api",
      detail: "The API is accepting requests.",
    });
    expect(fetcher).toHaveBeenCalledWith(
      new URL("/healthz", baseUrl),
      expect.objectContaining({ cache: "no-store" }),
    );
  });

  it.each([
    ["non-success response", () => new Response(null, { status: 503 })],
    [
      "unexpected payload",
      () => Response.json({ status: "degraded", service: "api" }),
    ],
  ])("maps a %s to unavailable", async (_name, responseFactory) => {
    const fetcher = vi.fn(async () => responseFactory());

    await expect(
      checkApiHealth({ baseUrl, fetcher: fetcher as typeof fetch }),
    ).resolves.toEqual({
      status: "unavailable",
      service: "api",
      detail: "The API did not respond to the health check.",
    });
  });

  it("maps a network failure to unavailable", async () => {
    const fetcher = vi.fn(async () => {
      throw new TypeError("network unavailable");
    });

    await expect(
      checkApiHealth({ baseUrl, fetcher: fetcher as typeof fetch }),
    ).resolves.toMatchObject({
      status: "unavailable",
      service: "api",
    });
  });
});
