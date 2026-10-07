"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";

import {
  EmailVerificationApiError,
  startEmailVerification,
  verifyEmailVerification,
} from "./api.server";
import { getCvDriveUrl } from "./cv-link.server";
import {
  createCvAccessToken,
  cvAccessCookieName,
  cvAccessDurationSeconds,
  isAccessSecretConfigured,
} from "./session.server";
import {
  hasOnlyExpectedFormFields,
  validateChallengeID,
  validateEmailAddress,
  validateVerificationCode,
} from "./validation";

interface EmailState {
  phase: "email";
  error?: string;
  retryAfterSeconds?: number;
}

interface CodeState {
  phase: "code";
  challengeId: string;
  maskedEmail: string;
  expiresAt: string;
  resendAfter: string;
  error?: string;
  retryAfterSeconds?: number;
}

interface VerifiedState {
  phase: "verified";
  cvUrl: string;
}

export type CvAccessActionState = EmailState | CodeState | VerifiedState;

const unavailableMessage =
  "CV access is not available right now. Please try again later.";
const invalidOrExpiredMessage =
  "The verification code is invalid or expired. Request a new code and try again.";

function accessConfiguration(): { cvUrl: string; secret: string } | null {
  const secret = process.env.CV_ACCESS_SECRET;
  if (!isAccessSecretConfigured(secret)) {
    return null;
  }

  try {
    return { cvUrl: getCvDriveUrl(), secret };
  } catch {
    return null;
  }
}

function stateWithError(
  previousState: CvAccessActionState,
  error: string,
  retryAfterSeconds?: number,
): CvAccessActionState {
  if (previousState.phase === "code") {
    const retryAfter = retryAfterSeconds
      ? new Date(Date.now() + retryAfterSeconds * 1_000).toISOString()
      : previousState.resendAfter;
    return {
      ...previousState,
      error,
      retryAfterSeconds,
      resendAfter:
        Date.parse(retryAfter) > Date.parse(previousState.resendAfter)
          ? retryAfter
          : previousState.resendAfter,
    };
  }

  return { phase: "email", error, retryAfterSeconds };
}

function rateLimitMessage(retryAfterSeconds?: number): string {
  if (!retryAfterSeconds) {
    return "Please wait before requesting another verification code.";
  }

  return `Please wait ${retryAfterSeconds} seconds before requesting another verification code.`;
}

function mapStartError(
  previousState: CvAccessActionState,
  error: unknown,
): CvAccessActionState {
  if (!(error instanceof EmailVerificationApiError)) {
    return stateWithError(previousState, unavailableMessage);
  }

  switch (error.code) {
    case "INVALID_EMAIL":
      return stateWithError(previousState, "Enter a valid email address.");
    case "EMAIL_VERIFICATION_RATE_LIMITED":
      return stateWithError(
        previousState,
        rateLimitMessage(error.retryAfterSeconds),
        error.retryAfterSeconds,
      );
    default:
      return stateWithError(previousState, unavailableMessage);
  }
}

function mapVerificationError(
  previousState: CvAccessActionState,
  error: unknown,
): CvAccessActionState {
  if (!(error instanceof EmailVerificationApiError)) {
    return stateWithError(previousState, unavailableMessage);
  }

  switch (error.code) {
    case "INVALID_CODE_FORMAT":
      return stateWithError(
        previousState,
        "Enter the six-digit verification code.",
      );
    case "INVALID_VERIFICATION_ID":
    case "INVALID_OR_EXPIRED_CODE":
      return stateWithError(previousState, invalidOrExpiredMessage);
    default:
      return stateWithError(previousState, unavailableMessage);
  }
}

export async function requestCvAccess(
  previousState: CvAccessActionState,
  formData: FormData,
): Promise<CvAccessActionState> {
  const intent = formData.get("intent");

  if (intent === "restart") {
    if (!hasOnlyExpectedFormFields(formData, ["intent"])) {
      return stateWithError(previousState, "The request is invalid.");
    }
    return { phase: "email" };
  }

  if (intent === "start" || intent === "resend") {
    if (!hasOnlyExpectedFormFields(formData, ["intent", "email"])) {
      return stateWithError(previousState, "Enter a valid email address.");
    }

    const email = validateEmailAddress(formData.get("email"));
    if (!email.value) {
      return stateWithError(
        previousState,
        email.error ?? "Enter a valid email address.",
      );
    }

    if (!accessConfiguration()) {
      return stateWithError(previousState, unavailableMessage);
    }

    try {
      const verification = await startEmailVerification(email.value);
      return {
        phase: "code",
        challengeId: verification.id,
        maskedEmail: verification.maskedEmail,
        expiresAt: verification.expiresAt,
        resendAfter: verification.resendAfter,
      };
    } catch (error) {
      return mapStartError(previousState, error);
    }
  }

  if (intent === "verify") {
    if (
      !hasOnlyExpectedFormFields(formData, [
        "intent",
        "challenge_id",
        "code",
      ])
    ) {
      return stateWithError(previousState, "The request is invalid.");
    }

    const challengeID = validateChallengeID(formData.get("challenge_id"));
    if (!challengeID.value) {
      return stateWithError(
        previousState,
        challengeID.error ?? invalidOrExpiredMessage,
      );
    }

    const code = validateVerificationCode(formData.get("code"));
    if (!code.value) {
      return stateWithError(
        previousState,
        code.error ?? "Enter the six-digit verification code.",
      );
    }

    const configuration = accessConfiguration();
    if (!configuration) {
      return stateWithError(previousState, unavailableMessage);
    }

    try {
      const verification = await verifyEmailVerification(
        challengeID.value,
        code.value,
      );
      if (verification.id !== challengeID.value) {
        return stateWithError(previousState, unavailableMessage);
      }

      const cookieStore = await cookies();
      cookieStore.set({
        name: cvAccessCookieName,
        value: createCvAccessToken(configuration.secret),
        httpOnly: true,
        maxAge: cvAccessDurationSeconds,
        path: "/cv",
        sameSite: "lax",
        secure: process.env.NODE_ENV === "production",
      });

      return { phase: "verified", cvUrl: configuration.cvUrl };
    } catch (error) {
      return mapVerificationError(previousState, error);
    }
  }

  return stateWithError(previousState, "The request is invalid.");
}

export async function clearCvAccess(): Promise<void> {
  const cookieStore = await cookies();
  cookieStore.delete({ name: cvAccessCookieName, path: "/cv" });
  redirect("/cv");
}
