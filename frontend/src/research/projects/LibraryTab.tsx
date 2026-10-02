import { safeHttpUrl } from "../../format";
import { FOLDERS } from "../student/categories";
import type { Folder } from "../student/types";
import type { ItemChanges } from "./api";
import { questionLabel } from "./types";
import type { LibraryItem, Project, ReadingStatus } from "./types";

export type LibraryFilter = "all" | "unlinked" | "to-read" | "retracted" | "local" | "foreign" | `folder:${Folder}` | `rq:${string}`;

const STATUS: { value: ReadingStatus; label: string }[] = [
  { value: "TO_READ", label: "To read" },
  { value: "READ", label: "Read" },
  { value: "CITED", label: "Cited in my paper" },
];

function matches(item: LibraryItem, filter: LibraryFilter): boolean {
  if (filter === "all") return true;
  if (filter === "unlinked") return item.questionIds.length === 0;
  if (filter === "to-read") return (item.status ?? "TO_READ") === "TO_READ";
  if (filter === "retracted") return item.source.retracted;
  if (filter === "local") return item.source.local;
  if (filter === "foreign") return !item.source.local && item.source.countries.length > 0;
  if (filter.startsWith("folder:")) return item.folder === filter.slice(7);
  if (filter.startsWith("rq:")) return item.questionIds.includes(filter.slice(3));
  return true;
}

/** The Research Evidence Library: verified records plus the student's own reading notes. */
export default function LibraryTab({
  project,
  filter,
  onFilter,
  onChange,
  onRemove,
}: {
  project: Project;
  filter: LibraryFilter;
  onFilter: (f: LibraryFilter) => void;
  onChange: (key: string, changes: ItemChanges) => void;
  onRemove: (key: string) => void;
}) {
  const items = project.library.filter((i) => matches(i, filter));
  const count = (f: LibraryFilter) => project.library.filter((i) => matches(i, f)).length;

  return (
    <section className="report-section" aria-labelledby="pj-lib-heading">
      <h2 id="pj-lib-heading">Your saved studies</h2>
      <label className="pj-filter">
        <span className="st-why-label">Show</span>
        <select className="st-select" value={filter} onChange={(e) => onFilter(e.target.value as LibraryFilter)}>
          <option value="all">All sources ({project.library.length})</option>
          {project.questions.map((q) => (
            <option key={q.id} value={`rq:${q.id}`}>
              {questionLabel(project.questions, q.id)} ({count(`rq:${q.id}`)})
            </option>
          ))}
          <option value="unlinked">Not linked to a question ({count("unlinked")})</option>
          <option value="to-read">To read ({count("to-read")})</option>
          {project.country && <option value="local">Local ({count("local")})</option>}
          {project.country && <option value="foreign">Foreign ({count("foreign")})</option>}
          {FOLDERS.map((f) => (
            <option key={f.value} value={`folder:${f.value}`}>
              {f.label} ({count(`folder:${f.value}`)})
            </option>
          ))}
          {count("retracted") > 0 && <option value="retracted">Retracted ({count("retracted")})</option>}
        </select>
      </label>

      {project.library.length === 0 ? (
        <p className="pj-empty">Nothing saved yet. Use “Find sources” and save what you&apos;ll read.</p>
      ) : items.length === 0 ? (
        <p className="pj-empty">No sources match this filter.</p>
      ) : (
        <ul className="st-list">
          {items.map((item) => (
            <Item key={item.key} project={project} item={item} onChange={(c) => onChange(item.key, c)} onRemove={() => onRemove(item.key)} />
          ))}
        </ul>
      )}
    </section>
  );
}

function Item({ project, item, onChange, onRemove }: { project: Project; item: LibraryItem; onChange: (c: ItemChanges) => void; onRemove: () => void }) {
  const s = item.source;
  const href = s.url ? safeHttpUrl(s.url) : null;
  const text = (field: "note" | "keyFindings" | "method", label: string, placeholder: string, rows = 2) => (
    <label className="pj-field">
      <span className="st-why-label">{label}</span>
      <textarea
        className="text-input"
        rows={rows}
        maxLength={3000}
        defaultValue={item[field] ?? ""}
        placeholder={placeholder}
        onBlur={(e) => {
          const v = e.target.value.trim();
          if (v !== (item[field] ?? "")) onChange({ [field]: v } as ItemChanges);
        }}
      />
    </label>
  );

  return (
    <li className="st-card pj-item">
      <div className="st-card-badges">
        <span className="st-badge st-badge--ok">Verified record</span>
        {s.local && <span className="st-badge">Local</span>}
        {!s.local && s.countries.length > 0 && <span className="st-badge st-badge--muted">{s.countries.join(", ")}</span>}
        {s.retracted && <span className="st-badge st-badge--bad">Retracted: don&apos;t cite as evidence</span>}
      </div>
      <p className="st-title">
        {href ? (
          <a href={href} target="_blank" rel="noopener noreferrer nofollow">
            {s.title}
          </a>
        ) : (
          s.title
        )}
      </p>
      <p className="muted small">
        {[s.authors.join(", "), s.year, s.venue].filter(Boolean).join(" · ")}
        {s.doi ? ` · doi:${s.doi}` : ""}
      </p>
      {s.relevance && s.relevanceQuote && (
        <div className="st-why">
          <p>
            <span className="st-why-label">Why it seemed relevant (AI reading):</span> {s.relevance}
          </p>
          <p className="st-quote">Abstract: “{s.relevanceQuote}”</p>
        </div>
      )}

      <div className="pj-item-controls">
        <label>
          <span className="st-why-label">Status</span>
          <select className="st-select" value={item.status ?? "TO_READ"} onChange={(e) => onChange({ status: e.target.value as ReadingStatus })}>
            {STATUS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span className="st-why-label">Section</span>
          <select className="st-select" value={item.folder} onChange={(e) => onChange({ folder: e.target.value as Folder })}>
            {FOLDERS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
      </div>

      {project.questions.length > 0 && (
        <fieldset className="pj-links">
          <legend className="st-why-label">Helps answer</legend>
          {project.questions.map((q) => (
            <label key={q.id} className="pj-check" title={q.text}>
              <input
                type="checkbox"
                checked={item.questionIds.includes(q.id)}
                onChange={(e) =>
                  onChange({ questionIds: e.target.checked ? [...item.questionIds, q.id] : item.questionIds.filter((x) => x !== q.id) })
                }
              />
              {questionLabel(project.questions, q.id)}
            </label>
          ))}
        </fieldset>
      )}

      <details className="pj-notes" open={Boolean(item.keyFindings || item.method || item.note)}>
        <summary>Your reading notes</summary>
        {text("keyFindings", "Key findings (in your words)", "What did the study find? Include numbers you'll cite, and check them against the paper.", 3)}
        {text("method", "Method", "e.g. Quasi-experimental, 120 Grade 11 students, pre-test/post-test")}
        {text("note", "How you'll use it", "e.g. Supports my RQ1; same setting as my study")}
      </details>

      <button type="button" className="text-button" onClick={onRemove}>
        Remove from library
      </button>
    </li>
  );
}
