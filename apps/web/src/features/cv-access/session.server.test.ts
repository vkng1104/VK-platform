import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import {
  createCvAccessToken,
  cvAccessDurationSeconds,
  isAccessSecretConfigured,
  verifyCvAccessToken,
} from "./session.server";

const secret = "test-cv-access-secret-with-at-least-32-characters";
const now = Date.UTC(2026, 9, 7, 1, 0, 0);

describe("CV access sessions", () => {
  it("creates a verifiable token without embedding protected values", () => {
    const token = createCvAccessToken(secret, now);

    expect(token).not.toContain("reviewer@example.com");
    expect(token).not.toContain("drive.google.com");
    expect(verifyCvAccessToken(token, secret, now)).toBe(true);
    expect(
      verifyCvAccessToken(
        token,
        secret,
        now + cvAccessDurationSeconds * 1_000 - 1,
      ),
    ).toBe(true);
  });

  it("rejects an expired token", () => {
    const token = createCvAccessToken(secret, now);

    expect(
      verifyCvAccessToken(
        token,
        secret,
        now + cvAccessDurationSeconds * 1_000,
      ),
    ).toBe(false);
  });

  it.each([
    ["missing token", undefined, secret],
    ["malformed token", "not-a-token", secret],
    ["wrong secret", createCvAccessToken(secret, now), `${secret}-wrong`],
  ])("rejects %s", (_name, token, candidateSecret) => {
    expect(verifyCvAccessToken(token, candidateSecret, now)).toBe(false);
  });

  it("rejects a tampered token", () => {
    const token = createCvAccessToken(secret, now);
    const [payload, signature] = token.split(".");
    const tamperedPayload = `${payload.slice(0, -1)}${payload.endsWith("A") ? "B" : "A"}`;

    expect(
      verifyCvAccessToken(`${tamperedPayload}.${signature}`, secret, now),
    ).toBe(false);
  });

  it("requires a sufficiently long server secret without printing it", () => {
    expect(isAccessSecretConfigured("too-short")).toBe(false);
    expect(() => createCvAccessToken("too-short", now)).toThrow(
      "CV access secret must contain at least 32 characters",
    );
  });
});
