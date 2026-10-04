import type {
  ExperienceEntry,
  PublicProfile,
} from "./model";

type UnknownRecord = Record<string, unknown>;

function fail(source: string, field: string, message: string): never {
  throw new Error(`${source}: ${field} ${message}`);
}

function record(value: unknown, source: string, field: string): UnknownRecord {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    fail(source, field, "must be an object");
  }

  return value as UnknownRecord;
}

function text(value: unknown, source: string, field: string): string {
  if (typeof value !== "string" || value.trim() === "") {
    fail(source, field, "must be a non-empty string");
  }

  return value.trim();
}

function textList(
  value: unknown,
  source: string,
  field: string,
  allowEmpty = false,
): string[] {
  if (!Array.isArray(value) || (!allowEmpty && value.length === 0)) {
    fail(source, field, allowEmpty ? "must be an array" : "must be a non-empty array");
  }

  return value.map((item, index) => text(item, source, `${field}[${index}]`));
}

function objectList(
  value: unknown,
  source: string,
  field: string,
  allowEmpty = false,
): unknown[] {
  if (!Array.isArray(value) || (!allowEmpty && value.length === 0)) {
    fail(source, field, allowEmpty ? "must be an array" : "must be a non-empty array");
  }

  return value;
}

const monthPattern = /^\d{4}-(0[1-9]|1[0-2])$/;

function month(value: unknown, source: string, field: string): string {
  const parsed = text(value, source, field);
  if (!monthPattern.test(parsed)) {
    fail(source, field, "must use YYYY-MM format");
  }

  return parsed;
}

function optionalMonth(
  value: unknown,
  source: string,
  field: string,
): string | undefined {
  if (value === null || value === undefined) {
    return undefined;
  }

  return month(value, source, field);
}

function experienceEntry(
  value: unknown,
  source: string,
  field: string,
): ExperienceEntry {
  const item = record(value, source, field);
  const startDate = month(item.start_date, source, `${field}.start_date`);
  const endDate = optionalMonth(item.end_date, source, `${field}.end_date`);

  if (endDate && endDate < startDate) {
    fail(source, field, "has an end date before its start date");
  }

  return {
    id: text(item.id, source, `${field}.id`),
    employer: text(item.employer, source, `${field}.employer`),
    role: text(item.role, source, `${field}.role`),
    startDate,
    endDate,
    highlights: textList(item.highlights, source, `${field}.highlights`),
    technologies: textList(
      item.technologies,
      source,
      `${field}.technologies`,
      true,
    ),
  };
}

function requireUniqueIds(
  items: Array<{ id: string }>,
  source: string,
  field: string,
) {
  const ids = new Set<string>();
  for (const item of items) {
    if (ids.has(item.id)) {
      fail(source, field, `contains duplicate id "${item.id}"`);
    }
    ids.add(item.id);
  }
}

export function parsePublicProfile(
  value: unknown,
  source = "public profile",
): PublicProfile {
  const profile = record(value, source, "root");
  const experience = objectList(
    profile.experience,
    source,
    "experience",
  ).map((item, index) =>
    experienceEntry(item, source, `experience[${index}]`),
  );
  requireUniqueIds(experience, source, "experience");

  return {
    name: text(profile.name, source, "name"),
    headline: text(profile.headline, source, "headline"),
    summary: text(profile.summary, source, "summary"),
    experience,
  };
}
