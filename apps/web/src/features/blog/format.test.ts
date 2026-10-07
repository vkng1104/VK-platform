import { describe, expect, it } from "vitest";

import { calculateReadingTime, formatContentDate } from "./format";

describe("engineering note formatting", () => {
  it("calculates a minimum one-minute reading time", () => {
    expect(calculateReadingTime("A short note.")).toBe(1);
  });

  it("rounds reading time up and ignores markdown URLs", () => {
    const body = `${"word ".repeat(220)}[source](https://example.com/very-long-path)`;

    expect(calculateReadingTime(body)).toBe(1);
    expect(calculateReadingTime(`${body} final`)).toBe(2);
  });

  it("formats content dates in UTC", () => {
    expect(formatContentDate("2026-10-08")).toBe("8 October 2026");
  });
});
