import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/features/cv-access/actions", () => ({
  clearCvAccess: vi.fn(),
  requestCvAccess: vi.fn(),
}));

import type { PublicProfile } from "@/features/experience/model";

import { CvAccessPanel } from "./CvAccessPanel";

const profile: PublicProfile = {
  name: "Example Person",
  headline: "Software engineer",
  summary: "Builds reliable systems.",
  experience: [],
};

describe("CvAccessPanel", () => {
  it("renders a safe public preview and non-sensitive placeholders", () => {
    const markup = renderToStaticMarkup(<CvAccessPanel profile={profile} />);

    expect(markup).toContain("Example Person");
    expect(markup).toContain("Get CV access");
    expect(markup).toContain("placeholder shapes only");
    expect(markup).toContain('aria-hidden="true"');
    expect(markup).not.toContain("PRIVATE-CANARY");
  });
});
