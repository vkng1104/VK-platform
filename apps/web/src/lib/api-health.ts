import { getJson, type ApiRequestOptions } from "./api";

interface ApiHealthPayload {
  status?: string;
  service?: string;
}

export interface ApiHealth {
  status: "operational" | "unavailable";
  service: string;
  detail: string;
}

type CheckApiHealthOptions = ApiRequestOptions;

export async function checkApiHealth({
  ...requestOptions
}: CheckApiHealthOptions = {}): Promise<ApiHealth> {
  try {
    const payload = await getJson<ApiHealthPayload>("/healthz", requestOptions);
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
