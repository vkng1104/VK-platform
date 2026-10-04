export interface PageIntroProps {
  eyebrow: string;
  title: string;
  description: string;
}

export function PageIntro({
  eyebrow,
  title,
  description,
}: Readonly<PageIntroProps>) {
  return (
    <header className="max-w-3xl">
      <p className="font-mono text-sm uppercase tracking-[0.24em] text-sky-300">
        {eyebrow}
      </p>
      <h1 className="mt-4 text-4xl font-semibold tracking-tight text-white sm:text-6xl">
        {title}
      </h1>
      <p className="mt-6 text-lg leading-8 text-slate-400">{description}</p>
    </header>
  );
}
