import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { SiteHeader } from "./SiteHeader";

describe("SiteHeader", () => {
  it("links to the public experience and gated CV routes", () => {
    const markup = renderToStaticMarkup(<SiteHeader />);

    expect(markup).toContain('href="/experience"');
    expect(markup).toContain('href="/cv"');
    expect(markup).toContain("Experience");
    expect(markup).toContain(">CV<");
  });
});
