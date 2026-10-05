import { describe, expect, it } from "vitest";

import {
  hasOnlyExpectedFormFields,
  validateChallengeID,
  validateEmailAddress,
  validateVerificationCode,
} from "./validation";

describe("CV access input validation", () => {
  it("normalizes a valid email address", () => {
    expect(validateEmailAddress("  Reviewer@Example.COM ")).toEqual({
      value: "reviewer@example.com",
    });
  });

  it.each([
    undefined,
    "",
    "not-an-email",
    `name@${"a".repeat(250)}.com`,
  ])("rejects invalid email input", (value) => {
    expect(validateEmailAddress(value).value).toBeUndefined();
  });

  it("accepts only canonical challenge IDs and six-digit codes", () => {
    expect(
      validateChallengeID("123e4567-e89b-42d3-a456-426614174000"),
    ).toEqual({ value: "123e4567-e89b-42d3-a456-426614174000" });
    expect(validateVerificationCode(" 123456 ")).toEqual({ value: "123456" });
  });

  it.each(["not-a-uuid", "123", "12ab56", "1234567"])(
    "rejects invalid challenge or code values",
    (value) => {
      expect(
        validateChallengeID(value).value &&
          validateVerificationCode(value).value,
      ).toBeFalsy();
    },
  );

  it("accepts exactly the expected fields and ignores action metadata", () => {
    const expected = new FormData();
    expected.set("$ACTION_ID", "internal");
    expected.set("intent", "start");
    expected.set("email", "reviewer@example.com");

    const unexpected = new FormData();
    unexpected.set("intent", "start");
    unexpected.set("email", "reviewer@example.com");
    unexpected.set("role", "admin");

    expect(
      hasOnlyExpectedFormFields(expected, ["intent", "email"]),
    ).toBe(true);
    expect(
      hasOnlyExpectedFormFields(unexpected, ["intent", "email"]),
    ).toBe(false);
  });
});
