import "server-only";

const driveFilePathPattern = /^\/file\/d\/[A-Za-z0-9_-]+(?:\/(?:view|preview))?\/?$/;
const driveFileIDPattern = /^[A-Za-z0-9_-]+$/;

export class CvLinkConfigurationError extends Error {
  constructor() {
    super("CV link configuration is unavailable");
    this.name = "CvLinkConfigurationError";
  }
}

export function parseCvDriveUrl(value: string | undefined): string {
  const configuredValue = value?.trim();
  if (!configuredValue) {
    throw new CvLinkConfigurationError();
  }

  let url: URL;
  try {
    url = new URL(configuredValue);
  } catch {
    throw new CvLinkConfigurationError();
  }

  const isDriveFilePath = driveFilePathPattern.test(url.pathname);
  const isDriveOpenPath =
    url.pathname === "/open" &&
    driveFileIDPattern.test(url.searchParams.get("id") ?? "");

  if (
    url.protocol !== "https:" ||
    url.hostname !== "drive.google.com" ||
    url.port !== "" ||
    url.username !== "" ||
    url.password !== "" ||
    (!isDriveFilePath && !isDriveOpenPath)
  ) {
    throw new CvLinkConfigurationError();
  }

  return url.toString();
}

export function getCvDriveUrl(): string {
  return parseCvDriveUrl(process.env.CV_GOOGLE_DRIVE_URL);
}
