import Link from "next/link";

export default function Home() {
  return (
    <main className="relative flex min-h-screen overflow-hidden bg-slate-950 px-6 py-12 text-slate-100 sm:px-10 lg:px-16">
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_top_left,rgba(56,189,248,0.18),transparent_34%),radial-gradient(circle_at_bottom_right,rgba(14,165,233,0.12),transparent_28%)]" />
      <div className="absolute inset-0 bg-[linear-gradient(rgba(148,163,184,0.06)_1px,transparent_1px),linear-gradient(90deg,rgba(148,163,184,0.06)_1px,transparent_1px)] bg-[size:48px_48px]" />

      <section className="relative mx-auto flex w-full max-w-6xl flex-col justify-between rounded-[2rem] border border-white/10 bg-slate-950/70 p-8 shadow-2xl shadow-sky-950/40 backdrop-blur sm:p-12 lg:p-16">
        <header className="flex items-center justify-between">
          <span className="font-mono text-sm uppercase tracking-[0.28em] text-sky-300">
            VK Platform
          </span>
          <Link
            className="rounded-full border border-white/15 px-4 py-2 text-sm text-slate-300 transition hover:border-sky-300/70 hover:text-white"
            href="/status"
          >
            System status
          </Link>
        </header>

        <div className="my-20 max-w-4xl lg:my-28">
          <p className="mb-5 font-mono text-sm uppercase tracking-[0.24em] text-sky-300">
            Software engineer
          </p>
          <h1 className="text-balance text-5xl font-semibold tracking-[-0.04em] text-white sm:text-7xl lg:text-8xl">
            I build software systems.
          </h1>
          <p className="mt-8 max-w-2xl text-lg leading-8 text-slate-300 sm:text-xl">
            A portfolio and engineering lab for backend systems,
            infrastructure, distributed systems, data, and AI.
          </p>
        </div>

        <footer className="flex flex-col gap-4 border-t border-white/10 pt-6 text-sm text-slate-400 sm:flex-row sm:items-center sm:justify-between">
          <span>Portfolio experience is under construction.</span>
          <span className="font-mono text-sky-300">Architecture V1</span>
        </footer>
      </section>
    </main>
  );
}
