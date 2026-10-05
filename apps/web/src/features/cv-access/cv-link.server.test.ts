import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import {
  CvLinkConfigurationError,
  getCvDriveUrl,
  parseCvDriveUrl,
} from "./cv-link.server";

const fakeDriveUrl =
  "https://drive.google.com/file/d/fake-public-cv-id/view?usp=sharing";

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("CV Drive link configuration", () => {
  it.each([
    fakeDriveUrl,
    "https://drive.google.com/file/d/fake-public-cv-id/preview",
    "https://drive.google.com/open?id=fake-public-cv-id",
  ])("accepts supported public Drive URLs", (value) => {
    expect(parseCvDriveUrl(value)).toBe(value);
  });

  it.each([
    undefined,
    "",
    "http://drive.google.com/file/d/fake/view",
    "https://evil.example/file/d/fake/view",
    "https://drive.google.com/drive/folders/fake",
    "https://user:secret@drive.google.com/file/d/fake/view",
  ])("rejects missing or unsafe configuration", (value) => {
    expect(() => parseCvDriveUrl(value)).toThrow(CvLinkConfigurationError);
  });

  it("does not include the rejected value in its error", () => {
    const secretValue = "https://evil.example/private-cv-canary";

    expect(() => parseCvDriveUrl(secretValue)).toThrow(
      "CV link configuration is unavailable",
    );
    try {
      parseCvDriveUrl(secretValue);
    } catch (error) {
      expect(String(error)).not.toContain(secretValue);
    }
  });

  it("loads the Drive URL only from server configuration", () => {
    vi.stubEnv("CV_GOOGLE_DRIVE_URL", fakeDriveUrl);
    expect(getCvDriveUrl()).toBe(fakeDriveUrl);
  });
});
