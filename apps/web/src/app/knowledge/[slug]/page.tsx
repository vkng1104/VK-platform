import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { KnowledgeTopicDetails } from "@/components/organisms/KnowledgeTopicDetails";
import {
  getKnowledgeTopicBySlug,
  getKnowledgeTopicSlugs,
} from "@/features/knowledge/queries";

interface KnowledgeTopicPageProps {
  params: Promise<{ slug: string }>;
}

export async function generateStaticParams() {
  const slugs = await getKnowledgeTopicSlugs();
  return slugs.map((slug) => ({ slug }));
}

export async function generateMetadata({
  params,
}: Readonly<KnowledgeTopicPageProps>): Promise<Metadata> {
  const { slug } = await params;
  const topic = await getKnowledgeTopicBySlug(slug);

  if (!topic) {
    return { title: "Knowledge topic not found" };
  }

  return {
    title: topic.title,
    description: topic.summary,
  };
}

export default async function KnowledgeTopicPage({
  params,
}: Readonly<KnowledgeTopicPageProps>) {
  const { slug } = await params;
  const topic = await getKnowledgeTopicBySlug(slug);

  if (!topic) {
    notFound();
  }

  return (
    <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-16 sm:px-10 sm:py-24 lg:px-12">
      <KnowledgeTopicDetails topic={topic} />
    </main>
  );
}
