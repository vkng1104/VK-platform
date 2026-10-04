import { StatusBadge } from "../atoms/StatusBadge";

export interface ServiceStatusCardProps {
  detail: string;
  operational: boolean;
  serviceName: string;
  statusLabel: string;
}

export function ServiceStatusCard({
  detail,
  operational,
  serviceName,
  statusLabel,
}: Readonly<ServiceStatusCardProps>) {
  return (
    <article className="rounded-3xl border border-white/10 bg-white/[0.04] p-6 sm:p-8">
      <div className="flex flex-col gap-6 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="flex items-center gap-3">
            <span
              aria-hidden="true"
              className={`h-2.5 w-2.5 rounded-full ${
                operational
                  ? "bg-emerald-400 shadow-[0_0_16px_rgba(52,211,153,0.7)]"
                  : "bg-rose-400 shadow-[0_0_16px_rgba(251,113,133,0.6)]"
              }`}
            />
            <h2 className="text-xl font-medium text-white">{serviceName}</h2>
          </div>
          <p className="mt-3 text-slate-400">{detail}</p>
        </div>
        <StatusBadge label={statusLabel} operational={operational} />
      </div>
    </article>
  );
}
