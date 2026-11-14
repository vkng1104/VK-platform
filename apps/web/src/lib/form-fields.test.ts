import { describe, expect, it } from "vitest";

import { hasOnlyExpectedFormFields } from "./form-fields";

function form(entries: [string, string][]): FormData {
  const data = new FormData();
  for (const [name, value] of entries) {
    data.append(name, value);
  }
  return data;
}

describe("hasOnlyExpectedFormFields", () => {
  it("ignores the hidden fields React adds to server-action forms", () => {
    const submitted = form([
      ["$ACTION_REF_1", ""],
      ["$ACTION_1:0", "{}"],
      ["$ACTION_1:1", "[]"],
      ["$ACTION_KEY", "k"],
      ["question", "Which database?"],
    ]);

    expect(hasOnlyExpectedFormFields(submitted, ["question"])).toBe(true);
  });

  it("rejects unexpected and missing fields", () => {
    expect(hasOnlyExpectedFormFields(form([["question", "q"], ["model", "x"]]), ["question"])).toBe(
      false,
    );
    expect(hasOnlyExpectedFormFields(form([["$ACTION_KEY", "k"]]), ["question"])).toBe(false);
  });
});
