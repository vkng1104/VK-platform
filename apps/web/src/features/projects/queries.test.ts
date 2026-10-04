import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api";

import { fetchProject } from "./api";
import { getProjectBySlug } from "./queries";

vi.mock("./api", () => ({
  fetchProject: vi.fn(),
  fetchProjects: vi.fn(),
}));

const mockedFetchProject = vi.mocked(fetchProject);

describe("project queries", () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it("maps an API 404 to a missing project", async () => {
    mockedFetchProject.mockRejectedValue(
      new ApiError(
        404,
        "PROJECT_NOT_FOUND",
        "The requested project was not found.",
      ),
    );

    await expect(getProjectBySlug("missing-project")).resolves.toBeNull();
  });

  it("does not hide unexpected API errors", async () => {
    const error = new ApiError(500, "INTERNAL_ERROR", "Unexpected failure.");
    mockedFetchProject.mockRejectedValue(error);

    await expect(getProjectBySlug("failing-project")).rejects.toBe(error);
  });
});
