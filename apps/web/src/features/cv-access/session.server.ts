import "server-only";

import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";

export const cvAccessCookieName = "vk_cv_access";
export const cvAccessDurationSeconds = 30 * 60;

interface AccessTokenPayload {
  v: 1;
  iat: number;
  exp: number;
  nonce: string;
}

function isUsableSecret(secret: string | undefined): secret is string {
  return typeof secret === "string" && secret.length >= 32;
}

function signatureFor(payload: string, secret: string): Buffer {
  return createHmac("sha256", secret).update(payload).digest();
}

export function isAccessSecretConfigured(
  secret = process.env.CV_ACCESS_SECRET,
): secret is string {
  return isUsableSecret(secret);
}

export function createCvAccessToken(secret: string, now = Date.now()): string {
  if (!isUsableSecret(secret)) {
    throw new Error("CV access secret must contain at least 32 characters");
  }

  const issuedAt = Math.floor(now / 1000);
  const payload: AccessTokenPayload = {
    v: 1,
    iat: issuedAt,
    exp: issuedAt + cvAccessDurationSeconds,
    nonce: randomBytes(16).toString("base64url"),
  };
  const encodedPayload = Buffer.from(JSON.stringify(payload)).toString(
    "base64url",
  );
  const signature = signatureFor(encodedPayload, secret).toString("base64url");

  return `${encodedPayload}.${signature}`;
}

export function verifyCvAccessToken(
  token: string | undefined,
  secret = process.env.CV_ACCESS_SECRET,
  now = Date.now(),
): boolean {
  if (!token || token.length > 1024 || !isUsableSecret(secret)) {
    return false;
  }

  const parts = token.split(".");
  if (parts.length !== 2) {
    return false;
  }

  const [encodedPayload, encodedSignature] = parts;

  try {
    const receivedSignature = Buffer.from(encodedSignature, "base64url");
    const expectedSignature = signatureFor(encodedPayload, secret);
    if (
      receivedSignature.length !== expectedSignature.length ||
      !timingSafeEqual(receivedSignature, expectedSignature)
    ) {
      return false;
    }

    const payload = JSON.parse(
      Buffer.from(encodedPayload, "base64url").toString("utf8"),
    ) as Partial<AccessTokenPayload>;
    const currentTime = Math.floor(now / 1000);

    return (
      payload.v === 1 &&
      typeof payload.iat === "number" &&
      Number.isInteger(payload.iat) &&
      typeof payload.exp === "number" &&
      Number.isInteger(payload.exp) &&
      typeof payload.nonce === "string" &&
      payload.nonce.length >= 16 &&
      payload.iat <= currentTime &&
      payload.exp > currentTime &&
      payload.exp - payload.iat === cvAccessDurationSeconds
    );
  } catch {
    return false;
  }
}
