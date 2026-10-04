import { TechnologyTag } from "@/components/atoms/TechnologyTag";
import { formatDateRange } from "@/features/experience/format";
import type { ExperienceEntry } from "@/features/experience/model";

export interface ExperienceEntryCardProps {
  entry: ExperienceEntry;
}

export function ExperienceEntryCard({
  entry,
}: Readonly<ExperienceEntryCardProps>) {
  const headingId = `experience-${entry.id}`;

  return (
    <article
      aria-labelledby={headingId}
      className="rounded-3xl border border-white/10 bg-white/[0.035] p-6 sm:p-8"
    >
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h3
            className="text-2xl font-semibold tracking-tight text-white"
            id={headingId}
          >
            {entry.role}
          </h3>
          <p className="mt-2 text-sky-300">{entry.employer}</p>
        </div>
        <time className="font-mono text-sm text-slate-400">
          {formatDateRange(entry.startDate, entry.endDate)}
        </time>
      </div>

      <ul className="mt-7 space-y-3 text-[0.96rem] leading-7 text-slate-300">
        {entry.highlights.map((highlight) => (
          <li className="flex gap-3" key={highlight}>
            <span aria-hidden="true" className="mt-0.5 text-sky-300">
              →
            </span>
            <span>{highlight}</span>
          </li>
        ))}
      </ul>

      {entry.technologies.length > 0 && (
        <ul className="mt-7 flex flex-wrap gap-2" aria-label="Technologies">
          {entry.technologies.map((technology) => (
            <li key={technology}>
              <TechnologyTag name={technology} />
            </li>
          ))}
        </ul>
      )}
    </article>
  );
}
