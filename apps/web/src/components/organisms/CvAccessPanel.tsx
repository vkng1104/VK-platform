import { CvAccessDialog } from "@/components/molecules/CvAccessDialog";
import type { PublicProfile } from "@/features/experience/model";

export interface CvAccessPanelProps {
  cvUrl?: string;
  profile: PublicProfile;
}

const placeholderWidths = ["w-full", "w-11/12", "w-4/5", "w-2/3"];

export function CvAccessPanel({
  cvUrl,
  profile,
}: Readonly<CvAccessPanelProps>) {
  return (
    <section className="mt-12 overflow-hidden rounded-3xl border border-white/10 bg-white/[0.035]">
      <div className="grid gap-10 p-6 sm:p-9 lg:grid-cols-[minmax(0,1fr)_18rem] lg:p-12">
        <div>
          <p className="font-mono text-xs uppercase tracking-[0.2em] text-sky-300">
            {cvUrl ? "Access verified" : "Private by default"}
          </p>
          <h2 className="mt-4 text-3xl font-semibold tracking-tight text-white">
            {profile.name}
          </h2>
          <p className="mt-2 text-slate-300">{profile.headline}</p>
          <p className="mt-6 max-w-2xl leading-7 text-slate-400">
            {profile.summary}
          </p>
          <p className="mt-5 max-w-2xl leading-7 text-slate-400">
            The CV is shared as a public Google Drive link after a short email
            verification. The document itself is not stored or rendered by this
            site.
          </p>
          <div className="mt-8">
            <CvAccessDialog cvUrl={cvUrl} />
          </div>
        </div>

        <div
          aria-hidden="true"
          className="select-none rounded-2xl border border-white/10 bg-slate-950/70 p-5 blur-[5px]"
        >
          <div className="h-4 w-28 rounded bg-slate-600/60" />
          <div className="mt-5 space-y-3">
            {placeholderWidths.map((width) => (
              <div
                className={`h-3 rounded bg-slate-700/70 ${width}`}
                key={width}
              />
            ))}
          </div>
          <div className="mt-8 h-4 w-36 rounded bg-slate-600/60" />
          <div className="mt-5 space-y-3">
            {[...placeholderWidths].reverse().map((width) => (
              <div
                className={`h-3 rounded bg-slate-700/70 ${width}`}
                key={`second-${width}`}
              />
            ))}
          </div>
        </div>
      </div>
      <p className="border-t border-white/10 px-6 py-4 text-sm leading-6 text-slate-500 sm:px-9 lg:px-12">
        The blurred area contains placeholder shapes only. The Drive URL is not
        sent to the browser until access is verified.
      </p>
    </section>
  );
}
