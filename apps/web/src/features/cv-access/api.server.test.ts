import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import {
  cvVerificationPurpose,
  EmailVerificationApiError,
  startEmailVerification,
  verifyEmailVerification,
} from "./api.server";

const baseUrl = "https://api.example.test";
const challengeID = "123e4567-e89b-42d3-a456-426614174000";

describe("CV email verification API", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("uses the IAM service base URL by default", async () => {
    vi.stubEnv("IAM_BASE_URL", "https://iam.example.test");
    vi.stubEnv("API_BASE_URL", "https://legacy-api.example.test");
    const fetcher = vi.fn<typeof fetch>(async () =>
      Response.json(
        {
          verification: {
            id: challengeID,
            masked_email: "r******r@example.com",
            expires_at: "2026-10-07T01:05:00Z",
            resend_after: "2026-10-07T01:01:00Z",
          },
        },
        { status: 202 },
      ),
    );

    await startEmailVerification("reviewer@example.com", {
      fetcher: fetcher as typeof fetch,
    });

    expect(fetcher.mock.calls[0][0].toString()).toBe(
      "https://iam.example.test/api/v1/email-verifications",
    );
  });

  it("starts a purpose-bound challenge with the exact wire contract", async () => {
    const fetcher = vi.fn<typeof fetch>(async () =>
      Response.json(
        {
          verification: {
            id: challengeID,
            masked_email: "r******r@example.com",
            expires_at: "2026-10-07T01:05:00Z",
            resend_after: "2026-10-07T01:01:00Z",
          },
        },
        { status: 202 },
      ),
    );

    await expect(
      startEmailVerification("reviewer@example.com", {
        baseUrl,
        fetcher: fetcher as typeof fetch,
      }),
    ).resolves.toEqual({
      id: challengeID,
      maskedEmail: "r******r@example.com",
      expiresAt: "2026-10-07T01:05:00Z",
      resendAfter: "2026-10-07T01:01:00Z",
    });

    const [url, options] = fetcher.mock.calls[0];
    expect(url.toString()).toBe(`${baseUrl}/api/v1/email-verifications`);
    expect(options).toMatchObject({
      method: "POST",
      cache: "no-store",
      body: JSON.stringify({
        email: "reviewer@example.com",
        purpose: cvVerificationPurpose,
      }),
    });
  });

  it("verifies a challenge and requires the expected purpose", async () => {
    const fetcher = vi.fn<typeof fetch>(async () =>
      Response.json({
        verification: {
          id: challengeID,
          purpose: cvVerificationPurpose,
          verified_at: "2026-10-07T01:02:00Z",
        },
      }),
    );

    await expect(
      verifyEmailVerification(challengeID, "123456", {
        baseUrl,
        fetcher: fetcher as typeof fetch,
      }),
    ).resolves.toEqual({
      id: challengeID,
      purpose: cvVerificationPurpose,
      verifiedAt: "2026-10-07T01:02:00Z",
    });

    const [url, options] = fetcher.mock.calls[0];
    expect(url.toString()).toBe(
      `${baseUrl}/api/v1/email-verifications/${challengeID}/verify`,
    );
    expect(options).toMatchObject({
      method: "POST",
      body: JSON.stringify({ code: "123456" }),
    });
  });

  it("rejects a successful response with another purpose", async () => {
    const fetcher = vi.fn<typeof fetch>(async () =>
      Response.json({
        verification: {
          id: challengeID,
          purpose: "another_flow",
          verified_at: "2026-10-07T01:02:00Z",
        },
      }),
    );

    await expect(
      verifyEmailVerification(challengeID, "123456", {
        baseUrl,
        fetcher: fetcher as typeof fetch,
      }),
    ).rejects.toThrow("unexpected purpose");
  });

  it("maps safe error metadata and Retry-After", async () => {
    const fetcher = vi.fn<typeof fetch>(async () =>
      Response.json(
        {
          code: "EMAIL_VERIFICATION_RATE_LIMITED",
          message: "Please wait before requesting another verification code.",
          request_id: "request-123",
          retryable: true,
        },
        { status: 429, headers: { "Retry-After": "45" } },
      ),
    );

    const request = startEmailVerification("reviewer@example.com", {
      baseUrl,
      fetcher: fetcher as typeof fetch,
    });

    await expect(request).rejects.toBeInstanceOf(EmailVerificationApiError);
    await expect(request).rejects.toMatchObject({
      status: 429,
      code: "EMAIL_VERIFICATION_RATE_LIMITED",
      requestId: "request-123",
      retryable: true,
      retryAfterSeconds: 45,
    });
  });

  it("rejects malformed success payloads without echoing them", async () => {
    const fetcher = vi.fn<typeof fetch>(async () =>
      Response.json({ verification: { id: "private-canary" } }),
    );

    await expect(
      startEmailVerification("reviewer@example.com", {
        baseUrl,
        fetcher: fetcher as typeof fetch,
      }),
    ).rejects.toThrow("Email verification API returned an invalid response");
  });
});
