import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  cookieDelete: vi.fn(),
  cookieSet: vi.fn(),
  getCvDriveUrl: vi.fn(),
  redirect: vi.fn(),
  startEmailVerification: vi.fn(),
  verifyEmailVerification: vi.fn(),
}));

vi.mock("server-only", () => ({}));
vi.mock("next/headers", () => ({
  cookies: vi.fn(async () => ({
    delete: mocks.cookieDelete,
    set: mocks.cookieSet,
  })),
}));
vi.mock("next/navigation", () => ({ redirect: mocks.redirect }));
vi.mock("./cv-link.server", () => ({
  getCvDriveUrl: mocks.getCvDriveUrl,
}));
vi.mock("./api.server", async (importOriginal) => {
  const original = await importOriginal<typeof import("./api.server")>();
  return {
    ...original,
    startEmailVerification: mocks.startEmailVerification,
    verifyEmailVerification: mocks.verifyEmailVerification,
  };
});

import {
  clearCvAccess,
  requestCvAccess,
  type CvAccessActionState,
} from "./actions";
import {
  cvVerificationPurpose,
  EmailVerificationApiError,
} from "./api.server";
import { cvAccessCookieName, cvAccessDurationSeconds } from "./session.server";

const secret = "test-cv-access-secret-with-at-least-32-characters";
const cvUrl =
  "https://drive.google.com/file/d/fake-public-cv-id/view?usp=sharing";
const challengeID = "123e4567-e89b-42d3-a456-426614174000";
const codeState: CvAccessActionState = {
  phase: "code",
  challengeId: challengeID,
  maskedEmail: "r******r@example.com",
  expiresAt: "2026-10-07T01:05:00Z",
  resendAfter: "2026-10-07T01:01:00Z",
};

function form(values: Record<string, string>): FormData {
  const formData = new FormData();
  for (const [name, value] of Object.entries(values)) {
    formData.set(name, value);
  }
  return formData;
}

describe("CV access actions", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.unstubAllEnvs();
    vi.stubEnv("CV_ACCESS_SECRET", secret);
    vi.stubEnv("NODE_ENV", "test");
    mocks.getCvDriveUrl.mockReturnValue(cvUrl);
    mocks.startEmailVerification.mockResolvedValue({
      id: challengeID,
      maskedEmail: "r******r@example.com",
      expiresAt: "2026-10-07T01:05:00Z",
      resendAfter: "2026-10-07T01:01:00Z",
    });
    mocks.verifyEmailVerification.mockResolvedValue({
      id: challengeID,
      purpose: cvVerificationPurpose,
      verifiedAt: "2026-10-07T01:02:00Z",
    });
  });

  it("starts a challenge without granting CV access", async () => {
    const result = await requestCvAccess(
      { phase: "email" },
      form({
        intent: "start",
        email: " Reviewer@Example.COM ",
      }),
    );

    expect(mocks.startEmailVerification).toHaveBeenCalledWith(
      "reviewer@example.com",
    );
    expect(result).toEqual(codeState);
    expect(mocks.cookieSet).not.toHaveBeenCalled();
    expect(JSON.stringify(result)).not.toContain("reviewer@example.com");
    expect(JSON.stringify(result)).not.toContain(cvUrl);
  });

  it("rejects malformed and unexpected start input before calling the API", async () => {
    const malformed = await requestCvAccess(
      { phase: "email" },
      form({ intent: "start", email: "invalid" }),
    );
    const unexpected = await requestCvAccess(
      { phase: "email" },
      form({ intent: "start", email: "reviewer@example.com", role: "admin" }),
    );

    expect(malformed).toEqual({
      phase: "email",
      error: "Enter a valid email address.",
      retryAfterSeconds: undefined,
    });
    expect(unexpected).toEqual(malformed);
    expect(mocks.startEmailVerification).not.toHaveBeenCalled();
  });

  it("fails closed before delivery when server-only configuration is invalid", async () => {
    vi.stubEnv("CV_ACCESS_SECRET", "short");

    const result = await requestCvAccess(
      { phase: "email" },
      form({ intent: "start", email: "reviewer@example.com" }),
    );

    expect(result).toMatchObject({
      phase: "email",
      error: "CV access is not available right now. Please try again later.",
    });
    expect(mocks.getCvDriveUrl).not.toHaveBeenCalled();
    expect(mocks.startEmailVerification).not.toHaveBeenCalled();
  });

  it("preserves challenge state and retry timing when resend is throttled", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-10-07T01:10:00Z"));
    mocks.startEmailVerification.mockRejectedValue(
      new EmailVerificationApiError({
        status: 429,
        code: "EMAIL_VERIFICATION_RATE_LIMITED",
        message: "internal upstream wording",
        retryable: true,
        retryAfterSeconds: 42,
      }),
    );

    try {
      const result = await requestCvAccess(
        codeState,
        form({ intent: "resend", email: "reviewer@example.com" }),
      );

      expect(result).toMatchObject({
        ...codeState,
        resendAfter: "2026-10-07T01:10:42.000Z",
        error:
          "Please wait 42 seconds before requesting another verification code.",
        retryAfterSeconds: 42,
      });
    } finally {
      vi.useRealTimers();
    }
  });

  it("grants access only after the backend verifies the expected challenge", async () => {
    const result = await requestCvAccess(
      codeState,
      form({ intent: "verify", challenge_id: challengeID, code: "123456" }),
    );

    expect(mocks.verifyEmailVerification).toHaveBeenCalledWith(
      challengeID,
      "123456",
    );
    expect(result).toEqual({ phase: "verified", cvUrl });
    expect(mocks.cookieSet).toHaveBeenCalledOnce();
    const cookie = mocks.cookieSet.mock.calls[0][0];
    expect(cookie).toMatchObject({
      httpOnly: true,
      maxAge: cvAccessDurationSeconds,
      name: cvAccessCookieName,
      path: "/cv",
      sameSite: "lax",
      secure: false,
    });
    expect(cookie.value).not.toContain("reviewer@example.com");
    expect(cookie.value).not.toContain("123456");
    expect(cookie.value).not.toContain(cvUrl);
  });

  it("does not grant access when the verified challenge ID is inconsistent", async () => {
    mocks.verifyEmailVerification.mockResolvedValue({
      id: "223e4567-e89b-42d3-a456-426614174000",
      purpose: cvVerificationPurpose,
      verifiedAt: "2026-10-07T01:02:00Z",
    });

    const result = await requestCvAccess(
      codeState,
      form({ intent: "verify", challenge_id: challengeID, code: "123456" }),
    );

    expect(result).toMatchObject({
      phase: "code",
      error: "CV access is not available right now. Please try again later.",
    });
    expect(mocks.cookieSet).not.toHaveBeenCalled();
  });

  it("maps wrong, expired, and exhausted codes to one safe response", async () => {
    mocks.verifyEmailVerification.mockRejectedValue(
      new EmailVerificationApiError({
        status: 422,
        code: "INVALID_OR_EXPIRED_CODE",
        message: "do not expose upstream detail",
        retryable: false,
      }),
    );

    const result = await requestCvAccess(
      codeState,
      form({ intent: "verify", challenge_id: challengeID, code: "123456" }),
    );

    expect(result).toMatchObject({
      phase: "code",
      error:
        "The verification code is invalid or expired. Request a new code and try again.",
    });
    expect(JSON.stringify(result)).not.toContain("upstream");
    expect(mocks.cookieSet).not.toHaveBeenCalled();
  });

  it("clears the scoped access cookie", async () => {
    await clearCvAccess();

    expect(mocks.cookieDelete).toHaveBeenCalledWith({
      name: cvAccessCookieName,
      path: "/cv",
    });
    expect(mocks.redirect).toHaveBeenCalledWith("/cv");
  });
});
