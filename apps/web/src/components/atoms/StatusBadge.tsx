export interface StatusBadgeProps {
  operational: boolean;
  label: string;
}

export function StatusBadge({
  operational,
  label,
}: Readonly<StatusBadgeProps>) {
  return (
    <span
      className={`w-fit rounded-full px-4 py-2 font-mono text-xs uppercase tracking-[0.18em] ${
        operational
          ? "bg-emerald-400/10 text-emerald-300"
          : "bg-rose-400/10 text-rose-300"
      }`}
    >
      {label}
    </span>
  );
}
