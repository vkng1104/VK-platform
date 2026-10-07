import { ExperienceEntryCard } from "@/components/molecules/ExperienceEntryCard";
import type { ExperienceEntry } from "@/features/experience/model";

export interface ExperienceTimelineProps {
  emptyMessage?: string;
  entries: ExperienceEntry[];
  title: string;
}

export function ExperienceTimeline({
  emptyMessage = "No approved experience is available yet.",
  entries,
  title,
}: Readonly<ExperienceTimelineProps>) {
  return (
    <section aria-labelledby="experience-timeline-title">
      <h2
        className="text-2xl font-semibold tracking-tight text-white sm:text-3xl"
        id="experience-timeline-title"
      >
        {title}
      </h2>
      {entries.length > 0 ? (
        <div className="mt-7 space-y-6">
          {entries.map((entry) => (
            <ExperienceEntryCard entry={entry} key={entry.id} />
          ))}
        </div>
      ) : (
        <p className="mt-6 rounded-3xl border border-dashed border-white/15 p-8 text-slate-400">
          {emptyMessage}
        </p>
      )}
    </section>
  );
}
