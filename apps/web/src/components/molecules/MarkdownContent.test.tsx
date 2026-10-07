import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { MarkdownContent } from "./MarkdownContent";

describe("MarkdownContent", () => {
  it("renders semantic Markdown and safe link behavior", () => {
    const markup = renderToStaticMarkup(
      <MarkdownContent
        content={[
          "## Design",
          "",
          "- One boundary",
          "- Another boundary",
          "",
          "[Knowledge](/knowledge/api-design)",
          "",
          "[External](https://example.com)",
        ].join("\n")}
      />,
    );

    expect(markup).toContain("<h2>Design</h2>");
    expect(markup).toContain("<ul>");
    expect(markup).toContain('href="/knowledge/api-design"');
    expect(markup).toContain('href="https://example.com"');
    expect(markup).toContain('target="_blank"');
    expect(markup).toContain('rel="noreferrer"');
    expect(markup).toContain("opens in a new tab");
  });

  it("does not render raw HTML", () => {
    const markup = renderToStaticMarkup(
      <MarkdownContent content={'<script>alert("unsafe")</script>\n\nSafe text.'} />,
    );

    expect(markup).not.toContain("<script");
    expect(markup).not.toContain("alert");
    expect(markup).toContain("Safe text.");
  });
});
