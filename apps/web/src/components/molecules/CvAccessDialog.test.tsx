import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  actionState: undefined as unknown,
}));

vi.mock("react", async (importOriginal) => {
  const original = await importOriginal<typeof import("react")>();
  return {
    ...original,
    useActionState: (_action: unknown, initialState: unknown) => [
      mocks.actionState ?? initialState,
      vi.fn(),
      false,
    ],
  };
});

vi.mock("@/features/cv-access/actions", () => ({
  clearCvAccess: vi.fn(),
  requestCvAccess: vi.fn(),
}));

import { CvAccessDialog } from "./CvAccessDialog";

const fakeCvUrl =
  "https://drive.google.com/file/d/fake-cv-canary/view?usp=sharing";
const challengeID = "123e4567-e89b-42d3-a456-426614174000";

afterEach(() => {
  mocks.actionState = undefined;
});

describe("CvAccessDialog", () => {
  it("renders the email-verification entry state without the Drive URL", () => {
    const markup = renderToStaticMarkup(<CvAccessDialog />);

    expect(markup).toContain("Get CV access");
    expect(markup).toContain('type="email"');
    expect(markup).toContain("six-digit code");
    expect(markup).toContain("not added to a contact list");
    expect(markup).not.toContain("fake-cv-canary");
  });

  it("renders masked-destination OTP and resend controls", () => {
    mocks.actionState = {
      phase: "code",
      challengeId: challengeID,
      maskedEmail: "r******r@example.com",
      expiresAt: "2026-10-07T01:05:00Z",
      resendAfter: "2026-10-07T01:01:00Z",
    };

    const markup = renderToStaticMarkup(<CvAccessDialog />);

    expect(markup).toContain("r******r@example.com");
    expect(markup).toContain('autoComplete="one-time-code"');
    expect(markup).toContain('name="challenge_id"');
    expect(markup).toContain("Verify and show link");
    expect(markup).toContain("Use another email");
    expect(markup).toContain("Resend in");
    expect(markup).not.toContain(fakeCvUrl);
  });

  it("renders copy, open, hide, and optional VirusTotal controls after access", () => {
    const markup = renderToStaticMarkup(<CvAccessDialog cvUrl={fakeCvUrl} />);

    expect(markup).toContain(fakeCvUrl.replaceAll("&", "&amp;"));
    expect(markup).toContain("Copy link");
    expect(markup).toContain("Open CV");
    expect(markup).toContain("Hide CV link");
    expect(markup).toContain("VirusTotal");
    expect(markup).toContain("https://www.virustotal.com/gui/home/url");
    expect(markup).toContain("not submitted automatically");
    expect(markup).not.toContain(
      `virustotal.com/gui/home/url?url=${encodeURIComponent(fakeCvUrl)}`,
    );
  });
});
