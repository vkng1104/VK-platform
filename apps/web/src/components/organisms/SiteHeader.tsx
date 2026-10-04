import Link from "next/link";

const navigation = [
  { href: "/", label: "Home" },
  { href: "/experience", label: "Experience" },
  { href: "/projects", label: "Projects" },
  { href: "/cv", label: "CV" },
  { href: "/status", label: "System status" },
];

export function SiteHeader() {
  return (
    <header className="site-chrome relative z-10 border-b border-white/10 bg-slate-950/75 backdrop-blur">
      <div className="mx-auto flex w-full max-w-6xl flex-col gap-4 px-6 py-5 sm:flex-row sm:items-center sm:justify-between sm:px-10 lg:px-12">
        <Link
          className="w-fit font-mono text-sm uppercase tracking-[0.28em] text-sky-300 transition hover:text-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
          href="/"
        >
          VK Platform
        </Link>
        <nav aria-label="Primary navigation">
          <ul className="flex flex-wrap items-center gap-x-6 gap-y-3 text-sm text-slate-300">
            {navigation.map((item) => (
              <li key={item.href}>
                <Link
                  className="transition hover:text-white focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300"
                  href={item.href}
                >
                  {item.label}
                </Link>
              </li>
            ))}
          </ul>
        </nav>
      </div>
    </header>
  );
}
