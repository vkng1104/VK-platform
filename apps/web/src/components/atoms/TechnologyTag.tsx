export interface TechnologyTagProps {
  name: string;
}

export function TechnologyTag({ name }: Readonly<TechnologyTagProps>) {
  return (
    <span className="rounded-full border border-white/10 px-3 py-1.5 text-xs text-slate-300">
      {name}
    </span>
  );
}
