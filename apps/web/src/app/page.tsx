import { FeaturedProjectsSection } from "@/components/organisms/FeaturedProjectsSection";
import { HomeHero } from "@/components/organisms/HomeHero";
import { KnowledgeNotesSection } from "@/components/organisms/KnowledgeNotesSection";
import { getFeaturedProjects } from "@/features/projects/queries";

export default async function Home() {
  const featuredProjects = await getFeaturedProjects();

  return (
    <main>
      <HomeHero />
      <FeaturedProjectsSection projects={featuredProjects} />
      <KnowledgeNotesSection />
    </main>
  );
}
