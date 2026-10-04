import type { Metadata } from "next";
import Link from "next/link";

import { PageIntro } from "@/components/molecules/PageIntro";
import { ExperienceTimeline } from "@/components/organisms/ExperienceTimeline";
import { loadPublicProfile } from "@/features/experience/content";

export const metadata: Metadata = {
  title: "Experience",
  description:
    "Professional experience building backend systems and developer-friendly platforms.",
};

export default async function ExperiencePage() {
  const profile = await loadPublicProfile();

  return (
    <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-16 sm:px-10 sm:py-24 lg:px-12">
      <PageIntro
        description={profile.summary}
        eyebrow={profile.headline}
        title="Experience"
      />
      <div className="mt-12">
        <ExperienceTimeline
          entries={profile.experience}
          title="Career timeline"
        />
      </div>
      <aside className="mt-12 flex flex-col items-start gap-4 rounded-3xl border border-sky-300/20 bg-sky-300/[0.06] p-6 sm:flex-row sm:items-center sm:justify-between sm:p-8">
        <div>
          <h2 className="text-xl font-semibold text-white">Looking for the CV?</h2>
          <p className="mt-2 leading-7 text-slate-400">
            The public CV link is available after a short email verification.
          </p>
        </div>
        <Link
          className="shrink-0 rounded-full border border-sky-300/40 px-5 py-2.5 text-sm font-medium text-sky-200 transition hover:border-sky-300 hover:text-white focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
          href="/cv"
        >
          Open CV
        </Link>
      </aside>
    </main>
  );
}
