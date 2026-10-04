import type { ReactNode } from "react";

export interface SiteTemplateProps {
  children: ReactNode;
  footer: ReactNode;
  header: ReactNode;
}

export function SiteTemplate({
  children,
  footer,
  header,
}: Readonly<SiteTemplateProps>) {
  return (
    <>
      <div className="pointer-events-none fixed inset-0 bg-[radial-gradient(circle_at_top_left,rgba(56,189,248,0.16),transparent_32%),radial-gradient(circle_at_bottom_right,rgba(14,165,233,0.1),transparent_26%)]" />
      <div className="pointer-events-none fixed inset-0 bg-[linear-gradient(rgba(148,163,184,0.045)_1px,transparent_1px),linear-gradient(90deg,rgba(148,163,184,0.045)_1px,transparent_1px)] bg-[size:48px_48px]" />
      {header}
      <div className="relative z-0 flex flex-1 flex-col">{children}</div>
      {footer}
    </>
  );
}
