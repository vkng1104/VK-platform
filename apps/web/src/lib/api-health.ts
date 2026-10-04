const defaultApiBaseUrl = "http://localhost:8080";

type Fetcher = typeof fetch;

interface ApiHealthPayload {
  status?: string;
  service?: string;
}

export interface ApiHealth {
  status: "operational" | "unavailable";
  service: string;
  detail: string;
}

interface CheckApiHealthOptions {
  baseUrl?: string;
  fetcher?: Fetcher;
}

export async function checkApiHealth({
  baseUrl = process.env.API_BASE_URL ?? defaultApiBaseUrl,
  fetcher = fetch,
}: CheckApiHealthOptions = {}): Promise<ApiHealth> {
  try {
    const response = await fetcher(new URL("/healthz", baseUrl), {
      cache: "no-store",
      signal: AbortSignal.timeout(3000),
    });

    if (!response.ok) {
      return unavailableHealth;
    }

    const payload = (await response.json()) as ApiHealthPayload;
    if (payload.status !== "ok") {
      return unavailableHealth;
    }

    return {
      status: "operational",
      service: payload.service ?? "api",
      detail: "The API is accepting requests.",
    };
  } catch {
    return unavailableHealth;
  }
}

const unavailableHealth: ApiHealth = {
  status: "unavailable",
  service: "api",
  detail: "The API did not respond to the health check.",
};
