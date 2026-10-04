import type { Metadata } from "next";

import { PageIntro } from "@/components/molecules/PageIntro";
import { ServiceStatusCard } from "@/components/molecules/ServiceStatusCard";
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
    <main className="mx-auto w-full max-w-4xl flex-1 px-6 py-16 sm:px-10 sm:py-24 lg:px-12">
      <section>
        <PageIntro
          description="A direct view of the services currently powering the platform."
          eyebrow="Live check"
          title="System status"
        />
        <div className="mt-12">
          <ServiceStatusCard
            detail={api.detail}
            operational={isOperational}
            serviceName="Go API"
            statusLabel={api.status}
          />
        </div>
        <p className="mt-5 text-sm text-slate-500">
          Refresh this page to run the health check again.
        </p>
      </section>
    </main>
  );
}
