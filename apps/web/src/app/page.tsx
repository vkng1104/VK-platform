import { FeaturedProjectsSection } from "@/components/organisms/FeaturedProjectsSection";
import { HomeHero } from "@/components/organisms/HomeHero";
import { getFeaturedProjects } from "@/features/projects/queries";

export default async function Home() {
  const featuredProjects = await getFeaturedProjects();

  return (
    <main>
      <HomeHero />
      <FeaturedProjectsSection projects={featuredProjects} />
    </main>
  );
}
