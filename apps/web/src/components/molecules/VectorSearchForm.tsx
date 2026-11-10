interface VectorSearchFormProps {
  action: (payload: FormData) => void;
  defaultQuery: string;
  pending: boolean;
}

export function VectorSearchForm({
  action,
  defaultQuery,
  pending,
}: VectorSearchFormProps) {
  return (
    <form action={action} className="mt-8" aria-describedby="vector-search-help">
      <label className="block text-sm font-medium text-slate-100" htmlFor="vector-query">
        Ask about a published project
      </label>
      <p className="mt-2 max-w-3xl text-sm leading-6 text-slate-400" id="vector-search-help">
        This lab returns matching source passages. It does not generate an answer yet.
      </p>
      <div className="mt-4 flex flex-col gap-3 sm:flex-row">
        <input
          className="min-w-0 flex-1 rounded-xl border border-white/15 bg-slate-950 px-4 py-3 text-base text-white outline-none transition placeholder:text-slate-500 focus:border-sky-300 focus:ring-2 focus:ring-sky-300/20"
          defaultValue={defaultQuery}
          disabled={pending}
          id="vector-query"
          maxLength={500}
          name="query"
          placeholder="What backend systems are implemented in VK Platform?"
          required
          type="search"
        />
        <button
          className="rounded-xl bg-sky-300 px-6 py-3 text-sm font-semibold text-slate-950 transition hover:bg-sky-200 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-300 disabled:cursor-wait disabled:opacity-60"
          disabled={pending}
          type="submit"
        >
          {pending ? "Searching…" : "Search vectors"}
        </button>
      </div>
    </form>
  );
}
