import type { ExperienceEntry } from "./model";

const monthFormatter = new Intl.DateTimeFormat("en", {
  month: "short",
  timeZone: "UTC",
  year: "numeric",
});

export function formatMonthYear(value: string): string {
  const [year, month] = value.split("-").map(Number);
  return monthFormatter.format(new Date(Date.UTC(year, month - 1, 1)));
}

export function formatDateRange(
  startDate: string,
  endDate?: string,
): string {
  return `${formatMonthYear(startDate)} - ${endDate ? formatMonthYear(endDate) : "Present"}`;
}

export function sortExperienceNewestFirst(
  entries: ExperienceEntry[],
): ExperienceEntry[] {
  return [...entries].sort((left, right) => {
    const leftEnd = left.endDate ?? "9999-12";
    const rightEnd = right.endDate ?? "9999-12";

    return rightEnd.localeCompare(leftEnd) || right.startDate.localeCompare(left.startDate);
  });
}
