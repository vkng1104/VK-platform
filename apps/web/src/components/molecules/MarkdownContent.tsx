import Link from "next/link";
import type { ComponentPropsWithoutRef } from "react";
import ReactMarkdown from "react-markdown";

export interface MarkdownContentProps {
  content: string;
}

function MarkdownLink({
  children,
  href = "",
}: Readonly<ComponentPropsWithoutRef<"a">>) {
  if (href.startsWith("/")) {
    return <Link href={href}>{children}</Link>;
  }

  if (/^https?:\/\//.test(href)) {
    return (
      <a href={href} rel="noreferrer" target="_blank">
        {children} <span aria-hidden="true">↗</span>
        <span className="sr-only"> (opens in a new tab)</span>
      </a>
    );
  }

  return <a href={href}>{children}</a>;
}

export function MarkdownContent({ content }: Readonly<MarkdownContentProps>) {
  return (
    <div className="markdown-content">
      <ReactMarkdown components={{ a: MarkdownLink }} skipHtml>
        {content}
      </ReactMarkdown>
    </div>
  );
}
