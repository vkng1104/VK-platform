import Link from "next/link";

export function HomeHero() {
  return (
    <section className="mx-auto w-full max-w-6xl px-6 py-20 sm:px-10 sm:py-28 lg:px-12 lg:py-32">
      <div className="max-w-4xl">
        <p className="mb-5 font-mono text-sm uppercase tracking-[0.24em] text-sky-300">
          Software engineer
        </p>
        <h1 className="text-balance text-5xl font-semibold tracking-[-0.04em] text-white sm:text-7xl lg:text-8xl">
          I build software systems.
        </h1>
        <p className="mt-8 max-w-2xl text-lg leading-8 text-slate-300 sm:text-xl">
          A portfolio and engineering lab for backend systems, infrastructure,
          distributed systems, data, and AI.
        </p>
        <div className="mt-10 flex flex-wrap gap-4">
          <Link
            className="rounded-full bg-sky-300 px-6 py-3 text-sm font-semibold text-slate-950 transition hover:bg-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
            href="/experience"
          >
            Explore experience
          </Link>
          <Link
            className="rounded-full border border-white/15 px-6 py-3 text-sm font-medium text-slate-200 transition hover:border-sky-300/60 hover:text-white focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
            href="/projects"
          >
            View projects
          </Link>
        </div>
      </div>
    </section>
  );
}
