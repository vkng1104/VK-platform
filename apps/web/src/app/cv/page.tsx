import type { Metadata } from "next";
import { cookies } from "next/headers";

import { PageIntro } from "@/components/molecules/PageIntro";
import { CvAccessPanel } from "@/components/organisms/CvAccessPanel";
import {
  cvAccessCookieName,
  verifyCvAccessToken,
} from "@/features/cv-access/session.server";
import { getCvDriveUrl } from "@/features/cv-access/cv-link.server";
import { loadPublicProfile } from "@/features/experience/content";

export const dynamic = "force-dynamic";
export const fetchCache = "force-no-store";
export const revalidate = 0;

export const metadata: Metadata = {
  title: "CV",
  description: "Request verified access to the public Google Drive CV link.",
};

export default async function CvPage() {
  const profile = await loadPublicProfile();
  const cookieStore = await cookies();
  const token = cookieStore.get(cvAccessCookieName)?.value;
  let cvUrl: string | undefined;

  if (verifyCvAccessToken(token)) {
    try {
      cvUrl = getCvDriveUrl();
    } catch {
      cvUrl = undefined;
    }
  }

  return (
    <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-16 sm:px-10 sm:py-24 lg:px-12">
      <PageIntro
        description="Verify your email to reveal the public Google Drive link. The link remains hidden from unauthorized page responses and client bundles."
        eyebrow="Private by default"
        title="Curriculum vitae"
      />
      <CvAccessPanel cvUrl={cvUrl} profile={profile} />
    </main>
  );
}
