import { useEffect, useRef, useState } from "react";
import { errorMessage } from "../../api";
import Link from "../../components/Link";
import { formatDateTime, safeHttpUrl } from "../../format";
import { useAuth } from "../../auth/useAuth";
import { navigate } from "../../router";
import { importWorkspace } from "../projects/api";
import { apa } from "./apa";
import { addSource, deleteDraft, deleteWorkspace, generateInsights, getWorkspace, savedToken, updateWorkspace, uploadDraft } from "./api";
import type { Folder, FoundSource, SavedSource, Workspace } from "./types";
import DraftTab from "./DraftTab";
import { FOLDERS } from "./categories";
import FindSources from "./FindSources";
import type { SearchRequest } from "./FindSources";
import InsightsTab from "./InsightsTab";
import "./student.css";

type Tab = "discover" | "draft" | "sources" | "insights" | "citations" | "notes";

const TABS: { value: Tab; label: string }[] = [
  { value: "discover", label: "Find sources" },
  { value: "draft", label: "My draft" },
  { value: "sources", label: "My sources" },
  { value: "insights", label: "Gaps & framework" },
  { value: "citations", label: "Citations" },
  { value: "notes", label: "Notes" },
];

export default function StudentWorkspace({ id }: { id: string }) {
  const [ws, setWs] = useState<Workspace | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>("discover");
  const [request, setRequest] = useState<SearchRequest | null>(null);
  const [notes, setNotes] = useState("");
  const [status, setStatus] = useState("");
  const [copied, setCopied] = useState(false);
  const token = savedToken(id);
  const { session, enabled } = useAuth();
  const [moving, setMoving] = useState(false);
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

  /** Copies this workspace into a capstone project on the signed-in account. */
  async function moveToAccount() {
    if (!token) return;
    setMoving(true);
    try {
      const project = await importWorkspace(id, token);
      navigate(`/research/p/${project.id}`);
    } catch (err) {
      setStatus(errorMessage(err).message);
      setMoving(false);
    }
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
        {editable && enabled && (
          <p className="small st-move">
            {session ? (
              <button type="button" className="button button--primary button--small" disabled={moving} onClick={() => void moveToAccount()}>
                {moving ? "Moving…" : "Make it a capstone project on my account"}
              </button>
            ) : (
              <>
                <Link href={`/signin?next=${encodeURIComponent(`/research/w/${id}`)}`}>Sign in</Link> to turn this into a capstone
                project: research questions, an evidence library, progress and next steps, on any device.
              </>
            )}
          </p>
        )}
        <div className="mode-tabs" role="tablist" aria-label="Workspace">
          {TABS.map((t) => (
            <button key={t.value} type="button" role="tab" className="mode-tab" aria-selected={tab === t.value} onClick={() => setTab(t.value)}>
              {t.value === "sources" ? `${t.label} (${ws.sources.length})` : t.label}
            </button>
          ))}
        </div>
        <p className="small" aria-live="polite">
          {status}
        </p>
      </header>

      {tab === "discover" && (
        <FindSources
          topic={ws.topic}
          country={ws.country}
          savedKeys={savedKeys}
          canSave={editable}
          request={request}
          onSave={(src, folder) => void save(src, folder)}
        />
      )}

      {tab === "draft" && (
        <DraftTab
          draft={ws.draft}
          canEdit={editable}
          busy={false}
          onUpload={async (file) => setWs(await uploadDraft(id, token!, file))}
          onRemove={async () => setWs(await deleteDraft(id, token!))}
          onSearch={(category, passage, label) => {
            setTab("discover");
            setRequest({ category, label, text: passage, nonce: Date.now() });
          }}
        />
      )}

      {tab === "insights" && (
        <InsightsTab
          insights={ws.insights}
          sources={ws.sources.map((s) => ({ key: s.key, title: s.source.title }))}
          country={ws.country}
          canEdit={editable}
          onGenerate={async () => setWs(await generateInsights(id, token!))}
        />
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
