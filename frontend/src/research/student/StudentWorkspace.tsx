import { useCallback, useEffect, useRef, useState } from "react";
import { errorMessage } from "../../api";
import Link from "../../components/Link";
import { formatDateTime, safeHttpUrl } from "../../format";
import { navigate } from "../../router";
import { apa } from "./apa";
import { addSource, deleteWorkspace, discover, getWorkspace, savedToken, updateWorkspace } from "./api";
import type { Category, Discovery, Folder, FoundSource, SavedSource, Workspace } from "./types";
import "./student.css";

const CATEGORIES: { value: Category; label: string; hint: string }[] = [
  { value: "RRL", label: "Related literature (RRL)", hint: "Reviews and key works on your topic" },
  { value: "RRS", label: "Related studies (RRS)", hint: "Empirical studies, last 10 years" },
  { value: "LOCAL", label: "Local studies", hint: "An author from your country" },
  { value: "FOREIGN", label: "Foreign studies", hint: "No author from your country" },
  { value: "THEORIES", label: "Theories & frameworks", hint: "Suggested, then verified in the literature" },
  { value: "CONCEPTS", label: "Key concepts", hint: "Concepts to define, with sources" },
  { value: "METHODS", label: "Research methods", hint: "Designs used for questions like yours" },
  { value: "RECENT", label: "Recent studies", hint: "Last 5 years" },
];

const FOLDERS: { value: Folder; label: string }[] = [
  { value: "RRL", label: "RRL" },
  { value: "RRS", label: "RRS" },
  { value: "THEORY", label: "Theory / framework" },
  { value: "CONCEPT", label: "Concept" },
  { value: "METHOD", label: "Method" },
  { value: "EVIDENCE", label: "Supporting evidence" },
  { value: "OTHER", label: "Other" },
];

const DEFAULT_FOLDER: Partial<Record<Category, Folder>> = {
  RRL: "RRL",
  RRS: "RRS",
  LOCAL: "RRS",
  FOREIGN: "RRS",
  THEORIES: "THEORY",
  CONCEPTS: "CONCEPT",
  METHODS: "METHOD",
  RECENT: "RRS",
  SUPPORTING: "EVIDENCE",
  FOR_TEXT: "EVIDENCE",
};

type Tab = "discover" | "sources" | "citations" | "notes";

function SourceCard({
  s,
  saved,
  canSave,
  defaultFolder,
  onSave,
}: {
  s: FoundSource;
  saved: boolean;
  canSave: boolean;
  defaultFolder: Folder;
  onSave: (folder: Folder) => void;
}) {
  const [folder, setFolder] = useState<Folder>(defaultFolder);
  const href = s.url ? safeHttpUrl(s.url) : null;
  return (
    <li className="st-card">
      <div className="st-card-badges">
        <span className={`st-badge ${s.verification === "VERIFIED" ? "st-badge--ok" : "st-badge--warn"}`}>
          {s.verification === "VERIFIED" ? "Verified in OpenAlex" : "Unverified"}
        </span>
        {s.local && <span className="st-badge">Local</span>}
        {!s.local && s.countries.length > 0 && <span className="st-badge st-badge--muted">{s.countries.join(", ")}</span>}
        {s.type && <span className="st-badge st-badge--muted">{s.type.replace(/-/g, " ")}</span>}
        {s.stance && <span className={`st-badge ${s.stance === "SUPPORTS" ? "st-badge--ok" : "st-badge--bad"}`}>{s.stance === "SUPPORTS" ? "Supports" : "Contradicts"}</span>}
        {s.retracted && <span className="st-badge st-badge--bad">Retracted: don&apos;t cite</span>}
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
        {s.citedByCount != null ? ` · cited ${s.citedByCount.toLocaleString()}×` : ""}
        {s.doi ? ` · doi:${s.doi}` : ""}
      </p>
      {s.relevance && s.relevanceQuote ? (
        <div className="st-why">
          <p>
            <span className="st-why-label">Why it&apos;s relevant (AI reading):</span> {s.relevance}
          </p>
          <p className="st-quote">Abstract: “{s.relevanceQuote}”</p>
        </div>
      ) : (
        <p className="muted small">{s.hasAbstract ? "Relevance not confirmed from the abstract; read it to judge." : "No abstract available; read the paper to judge relevance."}</p>
      )}
      {canSave && (
        <div className="st-save">
          {saved ? (
            <span className="small">Saved ✓</span>
          ) : (
            <>
              <label className="visually-hidden" htmlFor={`f-${s.key}`}>
                Save to
              </label>
              <select id={`f-${s.key}`} value={folder} onChange={(e) => setFolder(e.target.value as Folder)} className="st-select">
                {FOLDERS.map((f) => (
                  <option key={f.value} value={f.value}>
                    {f.label}
                  </option>
                ))}
              </select>
              <button type="button" className="button button--secondary button--small" onClick={() => onSave(folder)}>
                Save
              </button>
            </>
          )}
        </div>
      )}
    </li>
  );
}

export default function StudentWorkspace({ id }: { id: string }) {
  const [ws, setWs] = useState<Workspace | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>("discover");
  const [result, setResult] = useState<Discovery | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [text, setText] = useState("");
  const [notes, setNotes] = useState("");
  const [status, setStatus] = useState("");
  const [copied, setCopied] = useState(false);
  const token = savedToken(id);
  const noteTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    getWorkspace(id)
      .then((w) => {
        setWs(w);
        setNotes(w.notes ?? "");
      })
      .catch((err) => setError(errorMessage(err).message));
  }, [id]);

  useEffect(() => () => {
    if (noteTimer.current) clearTimeout(noteTimer.current);
  }, []);

  const run = useCallback(
    async (category: Category, label: string) => {
      if (!ws) return;
      setBusy(label);
      setProblem(null);
      setResult(null);
      try {
        setResult(await discover(ws.topic, category, category === "FOR_TEXT" || category === "SUPPORTING" || category === "CONTRADICTING" ? text : null, ws.country));
      } catch (err) {
        setProblem(errorMessage(err).message);
      } finally {
        setBusy(null);
      }
    },
    [ws, text],
  );

  if (error) {
    return (
      <section className="card state-card">
        <h1>Workspace not available</h1>
        <p className="muted">{error}</p>
        <Link href="/research" className="button button--primary">
          Start a new workspace
        </Link>
      </section>
    );
  }
  if (!ws) {
    return (
      <div className="card state-card" role="status">
        <span className="spinner" aria-hidden="true" />
        <p>Loading your workspace…</p>
      </div>
    );
  }

  const editable = Boolean(token);
  const savedKeys = new Set(ws.sources.map((s) => s.key));

  async function save(s: FoundSource, folder: Folder) {
    if (!token) return;
    try {
      setWs(await addSource(id, token, s, folder));
      setStatus("Saved to your sources.");
    } catch (err) {
      setStatus(errorMessage(err).message);
    }
  }

  async function changeSources(next: SavedSource[]) {
    if (!token) return;
    setWs({ ...ws!, sources: next });
    try {
      setWs(await updateWorkspace(id, token, { sources: next.map((s) => ({ key: s.key, folder: s.folder, studentNote: s.studentNote })) }));
    } catch (err) {
      setStatus(errorMessage(err).message);
    }
  }

  function changeNotes(value: string) {
    setNotes(value);
    if (!token) return;
    if (noteTimer.current) clearTimeout(noteTimer.current);
    setStatus("Saving notes…");
    noteTimer.current = setTimeout(() => {
      updateWorkspace(id, token, { notes: value })
        .then(() => setStatus("Notes saved."))
        .catch((err) => setStatus(errorMessage(err).message));
    }, 2500);
  }

  async function remove() {
    if (!token || !window.confirm("Delete this workspace and everything in it? This can't be undone.")) return;
    try {
      await deleteWorkspace(id, token);
      navigate("/research");
    } catch (err) {
      setStatus(errorMessage(err).message);
    }
  }

  const citations = ws.sources.map((s) => apa(s.source)).sort((a, b) => a.localeCompare(b));

  return (
    <article className="report st-workspace" aria-labelledby="st-heading">
      <header className="research-hero st-hero">
        <p className="hero-kicker">ResearchFact · Student Research Mode</p>
        <h1 id="st-heading">{ws.topic}</h1>
        <p className="muted small">
          {[ws.field, ws.country ? `Local studies: ${ws.country}` : null].filter(Boolean).join(" · ")}
          {` · ${ws.sources.length} saved source${ws.sources.length === 1 ? "" : "s"}`}
        </p>
        <p className="small st-retention">
          {editable
            ? `Saved on our server. Deleted automatically on ${formatDateTime(ws.expiresAt)} unless you keep working on it (90 days after your last change). Only this browser can edit it; anyone with the link can view it.`
            : "Read-only: only the browser that created this workspace can change it."}
        </p>
        <div className="mode-tabs" role="tablist" aria-label="Workspace">
          {(["discover", "sources", "citations", "notes"] as Tab[]).map((t) => (
            <button key={t} type="button" role="tab" className="mode-tab" aria-selected={tab === t} onClick={() => setTab(t)}>
              {t === "discover" ? "Find sources" : t === "sources" ? `My sources (${ws.sources.length})` : t === "citations" ? "Citations" : "Notes"}
            </button>
          ))}
        </div>
        <p className="small" aria-live="polite">
          {status}
        </p>
      </header>

      {tab === "discover" && (
        <section className="report-section" aria-label="Find sources">
          <div className="st-grid">
            {CATEGORIES.map((c) => (
              <button
                key={c.value}
                type="button"
                className="st-action"
                disabled={Boolean(busy) || ((c.value === "LOCAL" || c.value === "FOREIGN") && !ws.country)}
                onClick={() => void run(c.value, c.label)}
              >
                <strong>{c.label}</strong>
                <span>{c.hint}</span>
              </button>
            ))}
          </div>
          <div className="card st-text">
            <label htmlFor="st-text" className="st-why-label">
              A paragraph or claim from your paper
            </label>
            <textarea
              id="st-text"
              className="text-input"
              rows={4}
              maxLength={3000}
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="e.g. Flipped classrooms improve students' mathematics achievement."
            />
            <div className="row">
              {(
                [
                  ["FOR_TEXT", "Find sources for this"],
                  ["SUPPORTING", "Studies that support it"],
                  ["CONTRADICTING", "Studies that contradict it"],
                ] as [Category, string][]
              ).map(([cat, label]) => (
                <button
                  key={cat}
                  type="button"
                  className="button button--secondary button--small"
                  disabled={Boolean(busy) || text.trim().length < 15}
                  onClick={() => void run(cat, label)}
                >
                  {label}
                </button>
              ))}
            </div>
            <p className="muted small">
              To check whether a citation you already have supports a statement, use{" "}
              <Link href="/research">the citation check</Link>.
            </p>
          </div>

          {busy && (
            <div className="card state-card" role="status">
              <span className="spinner" aria-hidden="true" />
              <p>Searching OpenAlex: {busy}… (usually 15–40 seconds)</p>
            </div>
          )}
          {problem && (
            <div className="alert" role="alert">
              <p>{problem}</p>
            </div>
          )}
          {result && !busy && (
            <div className="st-results">
              <p className="muted small">
                Searched: {result.searches.map((s) => `“${s}”`).join(", ")} · {result.sources.length} source
                {result.sources.length === 1 ? "" : "s"}
              </p>
              {result.leads.length > 0 && (
                <ul className="st-leads">
                  {result.leads.map((l) => (
                    <li key={l.name}>
                      <span className={`st-badge ${l.verification === "VERIFIED" ? "st-badge--ok" : "st-badge--warn"}`}>
                        {l.verification === "VERIFIED" ? "Found in the literature" : "Unverified suggestion"}
                      </span>{" "}
                      <strong>{l.name}</strong>
                      {l.verification === "VERIFIED" && (
                        <span className="muted small"> · named by {l.sourceKeys.length} source{l.sourceKeys.length === 1 ? "" : "s"} below</span>
                      )}
                    </li>
                  ))}
                </ul>
              )}
              <ul className="st-list">
                {result.sources.map((s) => (
                  <SourceCard
                    key={s.key}
                    s={s}
                    saved={savedKeys.has(s.key)}
                    canSave={editable}
                    defaultFolder={DEFAULT_FOLDER[result.category] ?? "OTHER"}
                    onSave={(f) => void save(s, f)}
                  />
                ))}
              </ul>
              {result.limitations.length > 0 && (
                <ul className="limitations">
                  {result.limitations.map((l) => (
                    <li key={l}>{l}</li>
                  ))}
                </ul>
              )}
              <p className="research-disclaimer">{result.notice}</p>
            </div>
          )}
        </section>
      )}

      {tab === "sources" && (
        <section className="report-section" aria-label="My sources">
          {ws.sources.length === 0 ? (
            <p className="muted">Nothing saved yet. Use “Find sources” and save what you&apos;ll read.</p>
          ) : (
            FOLDERS.filter((f) => ws.sources.some((s) => s.folder === f.value)).map((f) => (
              <div key={f.value} className="st-folder">
                <h2>{f.label}</h2>
                <ul className="st-list">
                  {ws.sources
                    .filter((s) => s.folder === f.value)
                    .map((s) => (
                      <li key={s.key} className="st-card">
                        <p className="st-title">
                          {s.source.url && safeHttpUrl(s.source.url) ? (
                            <a href={safeHttpUrl(s.source.url)!} target="_blank" rel="noopener noreferrer nofollow">
                              {s.source.title}
                            </a>
                          ) : (
                            s.source.title
                          )}
                        </p>
                        <p className="muted small">{apa(s.source)}</p>
                        {s.source.retracted && <p className="st-badge st-badge--bad">Retracted: don&apos;t cite</p>}
                        {s.source.relevanceQuote && <p className="st-quote">Abstract: “{s.source.relevanceQuote}”</p>}
                        {editable ? (
                          <div className="st-save">
                            <select
                              aria-label="Folder"
                              className="st-select"
                              value={s.folder}
                              onChange={(e) => void changeSources(ws.sources.map((x) => (x.key === s.key ? { ...x, folder: e.target.value as Folder } : x)))}
                            >
                              {FOLDERS.map((o) => (
                                <option key={o.value} value={o.value}>
                                  {o.label}
                                </option>
                              ))}
                            </select>
                            <input
                              aria-label="Your note"
                              className="text-input st-note"
                              placeholder="Your note: how will you use it?"
                              defaultValue={s.studentNote ?? ""}
                              maxLength={1000}
                              onBlur={(e) =>
                                e.target.value !== (s.studentNote ?? "") &&
                                void changeSources(ws.sources.map((x) => (x.key === s.key ? { ...x, studentNote: e.target.value || null } : x)))
                              }
                            />
                            <button type="button" className="text-button" onClick={() => void changeSources(ws.sources.filter((x) => x.key !== s.key))}>
                              Remove
                            </button>
                          </div>
                        ) : (
                          s.studentNote && <p className="small">Note: {s.studentNote}</p>
                        )}
                      </li>
                    ))}
                </ul>
              </div>
            ))
          )}
        </section>
      )}

      {tab === "citations" && (
        <section className="report-section" aria-label="Citations">
          {citations.length === 0 ? (
            <p className="muted">Save sources to build your reference list.</p>
          ) : (
            <>
              <ol className="st-refs">
                {citations.map((c) => (
                  <li key={c}>{c}</li>
                ))}
              </ol>
              <div className="row">
                <button
                  type="button"
                  className="button button--secondary button--small"
                  onClick={() => void navigator.clipboard?.writeText(citations.join("\n")).then(() => setCopied(true))}
                >
                  {copied ? "Copied" : "Copy reference list"}
                </button>
              </div>
              <p className="muted small">
                APA 7 style from database metadata. Check capitalisation, italics and author names against the paper before
                submitting.
              </p>
            </>
          )}
        </section>
      )}

      {tab === "notes" && (
        <section className="report-section" aria-label="Notes">
          <label htmlFor="st-notes" className="st-why-label">
            Research notes, gaps you noticed, questions for your adviser
          </label>
          <textarea
            id="st-notes"
            className="text-input"
            rows={14}
            maxLength={20000}
            readOnly={!editable}
            value={notes}
            onChange={(e) => changeNotes(e.target.value)}
          />
        </section>
      )}

      {editable && (
        <p className="small">
          <button type="button" className="text-button" onClick={() => void remove()}>
            Delete this workspace now
          </button>
        </p>
      )}
    </article>
  );
}
