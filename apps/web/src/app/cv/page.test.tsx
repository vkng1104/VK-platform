import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getCookie: vi.fn(),
  getCvDriveUrl: vi.fn(),
  loadPublicProfile: vi.fn(),
  verifyCvAccessToken: vi.fn(),
}));

vi.mock("server-only", () => ({}));
vi.mock("next/headers", () => ({
  cookies: vi.fn(async () => ({ get: mocks.getCookie })),
}));
vi.mock("@/features/cv-access/actions", () => ({
  clearCvAccess: vi.fn(),
  requestCvAccess: vi.fn(),
}));
vi.mock("@/features/cv-access/cv-link.server", () => ({
  getCvDriveUrl: mocks.getCvDriveUrl,
}));
vi.mock("@/features/cv-access/session.server", () => ({
  cvAccessCookieName: "vk_cv_access",
  verifyCvAccessToken: mocks.verifyCvAccessToken,
}));
vi.mock("@/features/experience/content", () => ({
  loadPublicProfile: mocks.loadPublicProfile,
}));

import CvPage from "./page";

const fakeCvUrl =
  "https://drive.google.com/file/d/route-disclosure-canary/view";

describe("CV route disclosure boundary", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getCookie.mockReturnValue({ value: "signed-cookie" });
    mocks.loadPublicProfile.mockResolvedValue({
      name: "Example Person",
      headline: "Software engineer",
      summary: "Builds reliable systems.",
      experience: [],
    });
    mocks.getCvDriveUrl.mockReturnValue(fakeCvUrl);
  });

  it("does not load or render the Drive URL without a valid session", async () => {
    mocks.verifyCvAccessToken.mockReturnValue(false);

    const markup = renderToStaticMarkup(await CvPage());

    expect(markup).not.toContain("route-disclosure-canary");
    expect(mocks.getCvDriveUrl).not.toHaveBeenCalled();
  });

  it("renders the Drive URL only after the signed session is valid", async () => {
    mocks.verifyCvAccessToken.mockReturnValue(true);

    const markup = renderToStaticMarkup(await CvPage());

    expect(markup).toContain("route-disclosure-canary");
    expect(mocks.getCvDriveUrl).toHaveBeenCalledOnce();
  });

  it("fails closed when Drive configuration becomes invalid", async () => {
    mocks.verifyCvAccessToken.mockReturnValue(true);
    mocks.getCvDriveUrl.mockImplementation(() => {
      throw new Error("invalid configuration");
    });

    const markup = renderToStaticMarkup(await CvPage());

    expect(markup).not.toContain("route-disclosure-canary");
    expect(markup).toContain("Get CV access");
  });
});
