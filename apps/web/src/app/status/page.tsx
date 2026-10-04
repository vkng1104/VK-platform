import type { Metadata } from "next";
import Link from "next/link";

import { checkApiHealth } from "@/lib/api-health";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "System Status",
  description: "Current VK Platform service health.",
};

export default async function StatusPage() {
  const api = await checkApiHealth();
  const isOperational = api.status === "operational";

  return (
    <main className="min-h-screen bg-slate-950 px-6 py-12 text-slate-100 sm:px-10 lg:px-16">
      <section className="mx-auto w-full max-w-4xl">
        <header className="flex items-center justify-between border-b border-white/10 pb-6">
          <Link
            className="font-mono text-sm uppercase tracking-[0.24em] text-sky-300"
            href="/"
          >
            VK Platform
          </Link>
          <span className="text-sm text-slate-500">Architecture V1</span>
        </header>

        <div className="py-16 sm:py-24">
          <p className="font-mono text-sm uppercase tracking-[0.24em] text-sky-300">
            Live check
          </p>
          <h1 className="mt-4 text-4xl font-semibold tracking-tight text-white sm:text-6xl">
            System status
          </h1>
          <p className="mt-5 max-w-2xl text-lg leading-8 text-slate-400">
            A direct view of the services currently powering the platform.
          </p>

          <article className="mt-12 rounded-3xl border border-white/10 bg-white/[0.04] p-6 sm:p-8">
            <div className="flex flex-col gap-6 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <div className="flex items-center gap-3">
                  <span
                    aria-hidden="true"
                    className={`h-2.5 w-2.5 rounded-full ${
                      isOperational
                        ? "bg-emerald-400 shadow-[0_0_16px_rgba(52,211,153,0.7)]"
                        : "bg-rose-400 shadow-[0_0_16px_rgba(251,113,133,0.6)]"
                    }`}
                  />
                  <h2 className="text-xl font-medium text-white">Go API</h2>
                </div>
                <p className="mt-3 text-slate-400">{api.detail}</p>
              </div>
              <span
                className={`w-fit rounded-full px-4 py-2 font-mono text-xs uppercase tracking-[0.18em] ${
                  isOperational
                    ? "bg-emerald-400/10 text-emerald-300"
                    : "bg-rose-400/10 text-rose-300"
                }`}
              >
                {api.status}
              </span>
            </div>
          </article>

          <p className="mt-5 text-sm text-slate-500">
            Refresh this page to run the health check again.
          </p>
        </div>
      </section>
    </main>
  );
}
