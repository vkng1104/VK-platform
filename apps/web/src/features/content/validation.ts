type UnknownRecord = Record<string, unknown>;

export function fail(source: string, field: string, message: string): never {
  throw new Error(`${source}: ${field} ${message}`);
}

export function readRecord(
  value: unknown,
  source: string,
  field: string,
): UnknownRecord {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    fail(source, field, "must be an object");
  }

  return value as UnknownRecord;
}

export function assertOnlyKeys(
  value: UnknownRecord,
  allowedKeys: readonly string[],
  source: string,
  field: string,
): void {
  const allowed = new Set(allowedKeys);
  const unknownKey = Object.keys(value).find((key) => !allowed.has(key));

  if (unknownKey) {
    fail(source, `${field}.${unknownKey}`, "is not supported");
  }
}

export function readText(
  value: unknown,
  source: string,
  field: string,
): string {
  if (typeof value !== "string" || value.trim() === "") {
    fail(source, field, "must be a non-empty string");
  }

  return value.trim();
}

export function readOptionalText(
  value: unknown,
  source: string,
  field: string,
): string | undefined {
  if (value === undefined || value === null) {
    return undefined;
  }

  return readText(value, source, field);
}

export function readArray(
  value: unknown,
  source: string,
  field: string,
  allowEmpty = true,
): unknown[] {
  if (!Array.isArray(value) || (!allowEmpty && value.length === 0)) {
    fail(
      source,
      field,
      allowEmpty ? "must be an array" : "must be a non-empty array",
    );
  }

  return value;
}

export function readTextList(
  value: unknown,
  source: string,
  field: string,
  allowEmpty = true,
): string[] {
  return readArray(value, source, field, allowEmpty).map((item, index) =>
    readText(item, source, `${field}[${index}]`),
  );
}

const slugPattern = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

export function readSlug(
  value: unknown,
  source: string,
  field: string,
): string {
  const slug = readText(value, source, field);
  if (!slugPattern.test(slug)) {
    fail(source, field, "must use lowercase kebab-case");
  }

  return slug;
}

export function readStatus(
  value: unknown,
  source: string,
  field: string,
): "draft" | "published" {
  if (value !== "draft" && value !== "published") {
    fail(source, field, 'must be "draft" or "published"');
  }

  return value;
}

export function readNonNegativeInteger(
  value: unknown,
  source: string,
  field: string,
): number {
  if (!Number.isInteger(value) || (value as number) < 0) {
    fail(source, field, "must be a non-negative integer");
  }

  return value as number;
}

const datePattern = /^\d{4}-(0[1-9]|1[0-2])-([0-2]\d|3[01])$/;

export function readDate(
  value: unknown,
  source: string,
  field: string,
): string {
  const date = readText(value, source, field);
  const parsed = new Date(`${date}T00:00:00.000Z`);

  if (
    !datePattern.test(date) ||
    Number.isNaN(parsed.getTime()) ||
    parsed.toISOString().slice(0, 10) !== date
  ) {
    fail(source, field, "must be a valid YYYY-MM-DD date");
  }

  return date;
}

export function ensureUnique(
  values: readonly string[],
  source: string,
  field: string,
): void {
  const seen = new Set<string>();

  for (const value of values) {
    if (seen.has(value)) {
      fail(source, field, `contains duplicate value "${value}"`);
    }
    seen.add(value);
  }
}
