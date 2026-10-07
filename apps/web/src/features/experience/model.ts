export interface ExperienceEntry {
  id: string;
  employer: string;
  role: string;
  startDate: string;
  endDate?: string;
  highlights: string[];
  technologies: string[];
}

export interface PublicProfile {
  name: string;
  headline: string;
  summary: string;
  experience: ExperienceEntry[];
}
