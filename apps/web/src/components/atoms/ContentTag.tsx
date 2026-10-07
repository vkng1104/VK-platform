export interface ContentTagProps {
  label: string;
}

export function ContentTag({ label }: Readonly<ContentTagProps>) {
  return (
    <span className="rounded-full border border-sky-300/15 bg-sky-300/[0.04] px-3 py-1.5 text-xs text-sky-100">
      {label}
    </span>
  );
}
