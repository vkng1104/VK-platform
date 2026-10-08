import "server-only";

const defaultIamBaseUrl = "http://localhost:8081";
const defaultTimeoutMs = 5_000;

type Fetcher = typeof fetch;

export const cvVerificationPurpose = "restricted_resource_access" as const;

interface ApiRequestOptions {
  baseUrl?: string;
  fetcher?: Fetcher;
  timeoutMs?: number;
}

interface ApiErrorPayload {
  code?: unknown;
  message?: unknown;
  request_id?: unknown;
  retryable?: unknown;
}

interface StartVerificationEnvelope {
  verification?: {
    id?: unknown;
    masked_email?: unknown;
    expires_at?: unknown;
    resend_after?: unknown;
  };
}

interface VerifyVerificationEnvelope {
  verification?: {
    id?: unknown;
    purpose?: unknown;
    verified_at?: unknown;
  };
}

export interface StartedEmailVerification {
  id: string;
  maskedEmail: string;
  expiresAt: string;
  resendAfter: string;
}

export interface CompletedEmailVerification {
  id: string;
  purpose: typeof cvVerificationPurpose;
  verifiedAt: string;
}

export class EmailVerificationApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId?: string;
  readonly retryable: boolean;
  readonly retryAfterSeconds?: number;

  constructor({
    status,
    code,
    message,
    requestId,
    retryable,
    retryAfterSeconds,
  }: {
    status: number;
    code: string;
    message: string;
    requestId?: string;
    retryable: boolean;
    retryAfterSeconds?: number;
  }) {
    super(message);
    this.name = "EmailVerificationApiError";
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.retryable = retryable;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

function requiredString(value: unknown): string {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error("Email verification API returned an invalid response");
  }

  return value;
}

function requiredTimestamp(value: unknown): string {
  const timestamp = requiredString(value);
  if (Number.isNaN(Date.parse(timestamp))) {
    throw new Error("Email verification API returned an invalid response");
  }

  return timestamp;
}

function retryAfterSeconds(response: Response): number | undefined {
  const value = response.headers.get("retry-after");
  if (!value || !/^\d+$/.test(value)) {
    return undefined;
  }

  const seconds = Number(value);
  return Number.isSafeInteger(seconds) && seconds > 0 ? seconds : undefined;
}

async function readApiError(response: Response): Promise<EmailVerificationApiError> {
  let payload: ApiErrorPayload = {};
  try {
    payload = (await response.json()) as ApiErrorPayload;
  } catch {
    // Use the safe defaults below when the upstream body is not JSON.
  }

  return new EmailVerificationApiError({
    status: response.status,
    code:
      typeof payload.code === "string"
        ? payload.code
        : "EMAIL_VERIFICATION_REQUEST_FAILED",
    message:
      typeof payload.message === "string"
        ? payload.message
        : "Email verification is temporarily unavailable.",
    requestId:
      typeof payload.request_id === "string" ? payload.request_id : undefined,
    retryable: payload.retryable === true,
    retryAfterSeconds: retryAfterSeconds(response),
  });
}

async function postJson<T>(
  path: string,
  body: Record<string, string>,
  {
    baseUrl = process.env.IAM_BASE_URL ?? defaultIamBaseUrl,
    fetcher = fetch,
    timeoutMs = defaultTimeoutMs,
  }: ApiRequestOptions = {},
): Promise<T> {
  const response = await fetcher(new URL(path, baseUrl), {
    method: "POST",
    cache: "no-store",
    headers: {
      accept: "application/json",
      "content-type": "application/json",
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs),
  });

  if (!response.ok) {
    throw await readApiError(response);
  }

  return (await response.json()) as T;
}

export async function startEmailVerification(
  email: string,
  options?: ApiRequestOptions,
): Promise<StartedEmailVerification> {
  const payload = await postJson<StartVerificationEnvelope>(
    "/api/v1/email-verifications",
    { email, purpose: cvVerificationPurpose },
    options,
  );
  const verification = payload.verification;
  if (!verification) {
    throw new Error("Email verification API returned an invalid response");
  }

  return {
    id: requiredString(verification.id),
    maskedEmail: requiredString(verification.masked_email),
    expiresAt: requiredTimestamp(verification.expires_at),
    resendAfter: requiredTimestamp(verification.resend_after),
  };
}

export async function verifyEmailVerification(
  id: string,
  code: string,
  options?: ApiRequestOptions,
): Promise<CompletedEmailVerification> {
  const payload = await postJson<VerifyVerificationEnvelope>(
    `/api/v1/email-verifications/${encodeURIComponent(id)}/verify`,
    { code },
    options,
  );
  const verification = payload.verification;
  if (!verification) {
    throw new Error("Email verification API returned an invalid response");
  }

  const purpose = requiredString(verification.purpose);
  if (purpose !== cvVerificationPurpose) {
    throw new Error("Email verification API returned an unexpected purpose");
  }

  return {
    id: requiredString(verification.id),
    purpose,
    verifiedAt: requiredTimestamp(verification.verified_at),
  };
}
