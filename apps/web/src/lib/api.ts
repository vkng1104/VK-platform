const defaultApiBaseUrl = "http://localhost:8080";

type Fetcher = typeof fetch;

interface ErrorPayload {
  code?: string;
  message?: string;
}

export interface ApiRequestOptions {
  baseUrl?: string;
  fetcher?: Fetcher;
  timeoutMs?: number;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

export async function getJson<T>(
  path: string,
  {
    baseUrl = process.env.API_BASE_URL ?? defaultApiBaseUrl,
    fetcher = fetch,
    timeoutMs = 3000,
  }: ApiRequestOptions = {},
): Promise<T> {
  const response = await fetcher(new URL(path, baseUrl), {
    cache: "no-store",
    headers: {
      accept: "application/json",
    },
    signal: AbortSignal.timeout(timeoutMs),
  });

  if (!response.ok) {
    const payload = await readErrorPayload(response);
    throw new ApiError(
      response.status,
      payload.code ?? "API_REQUEST_FAILED",
      payload.message ?? "The API request failed.",
    );
  }

  return (await response.json()) as T;
}

async function readErrorPayload(response: Response): Promise<ErrorPayload> {
  try {
    return (await response.json()) as ErrorPayload;
  } catch {
    return {};
  }
}
