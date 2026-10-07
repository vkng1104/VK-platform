import type { EngineeringNoteSummary } from "@/features/blog/model";

import { EngineeringNoteCard } from "../molecules/EngineeringNoteCard";

export interface EngineeringNotesListProps {
  notes: EngineeringNoteSummary[];
}

export function EngineeringNotesList({
  notes,
}: Readonly<EngineeringNotesListProps>) {
  if (notes.length === 0) {
    return (
      <p className="rounded-3xl border border-dashed border-white/15 p-8 text-slate-400">
        Engineering notes are being prepared.
      </p>
    );
  }

  return (
    <div className="space-y-6">
      {notes.map((note) => (
        <EngineeringNoteCard key={note.slug} note={note} />
      ))}
    </div>
  );
}
