import type { Metadata } from "next";
import type { ReactNode } from "react";

import { SiteFooter } from "@/components/organisms/SiteFooter";
import { SiteHeader } from "@/components/organisms/SiteHeader";
import { SiteTemplate } from "@/components/templates/SiteTemplate";

import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "VK Platform",
    template: "%s | VK Platform",
  },
  description: "A systems-focused software engineering portfolio.",
};

interface RootLayoutProps {
  children: ReactNode;
}

export default function RootLayout({ children }: Readonly<RootLayoutProps>) {
  return (
    <html lang="en" className="h-full antialiased">
      <body className="flex min-h-full flex-col bg-slate-950 text-slate-100">
        <SiteTemplate header={<SiteHeader />} footer={<SiteFooter />}>
          {children}
        </SiteTemplate>
      </body>
    </html>
  );
}
