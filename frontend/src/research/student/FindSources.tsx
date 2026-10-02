import { useEffect, useState } from "react";
import { errorMessage } from "../../api";
import Link from "../../components/Link";
import { safeHttpUrl } from "../../format";
import { discover } from "./api";
import { CATEGORIES, DEFAULT_FOLDER, FOLDERS } from "./categories";
import type { Category, Discovery, Folder, FoundSource } from "./types";

/** A research question offered when saving (capstone projects). */
export interface QuestionOption {
  id: string;
  label: string;
}

/** A search started from elsewhere (a draft passage, a next step); {@code nonce} makes repeats run again. */
export interface SearchRequest {
  category: Category;
  label: string;
  text: string | null;
  questionId?: string | null;
  nonce: number;
}

export function SourceCard({
  s,
  saved,
  canSave,
  defaultFolder,
  questions,
  defaultQuestion,
  onSave,
}: {
  s: FoundSource;
  saved: boolean;
  canSave: boolean;
  defaultFolder: Folder;
  /** Capstone projects: link the source to a research question when saving. */
  questions?: QuestionOption[];
  defaultQuestion?: string | null;
  onSave: (folder: Folder, questionId: string | null) => void;
}) {
  const [folder, setFolder] = useState<Folder>(defaultFolder);
  const [question, setQuestion] = useState<string>(defaultQuestion ?? "");
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
              {questions && questions.length > 0 && (
                <>
                  <label className="visually-hidden" htmlFor={`q-${s.key}`}>
                    Link to research question
                  </label>
                  <select id={`q-${s.key}`} value={question} onChange={(e) => setQuestion(e.target.value)} className="st-select">
                    <option value="">No research question</option>
                    {questions.map((q) => (
                      <option key={q.id} value={q.id}>
                        {q.label}
                      </option>
                    ))}
                  </select>
                </>
              )}
              <button type="button" className="button button--secondary button--small" onClick={() => onSave(folder, question || null)}>
                Save
              </button>
            </>
          )}
        </div>
      )}
    </li>
  );
}

/**
 * Finding real sources in OpenAlex (ADR-15): category searches, or sources for a pasted paragraph/claim. Every
 * listed source is an index record; shared by quick workspaces and capstone projects.
 */
export default function FindSources({
  topic,
  country,
  savedKeys,
  canSave,
  onSave,
  request,
  questions,
}: {
  topic: string;
  country: string | null;
  savedKeys: Set<string>;
  canSave: boolean;
  onSave: (source: FoundSource, folder: Folder, questionId: string | null) => void;
  request?: SearchRequest | null;
  questions?: QuestionOption[];
}) {
  const [result, setResult] = useState<Discovery | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [text, setText] = useState("");
  const [linkTo, setLinkTo] = useState<string | null>(null);

  async function run(category: Category, label: string, passage: string | null) {
    setBusy(label);
    setProblem(null);
    setResult(null);
    const forText = category === "FOR_TEXT" || category === "SUPPORTING" || category === "CONTRADICTING";
    try {
      setResult(await discover(topic, category, forText ? passage : null, country));
    } catch (err) {
      setProblem(errorMessage(err).message);
    } finally {
      setBusy(null);
    }
  }

  // A search requested from another tab (draft passage, next step) runs once per request.
  useEffect(() => {
    if (!request) return;
    if (request.text) setText(request.text);
    setLinkTo(request.questionId ?? null);
    void run(request.category, request.label, request.text);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [request?.nonce]);

  return (
    <section className="report-section" aria-label="Find sources">
      <div className="st-grid">
        {CATEGORIES.map((c) => (
          <button
            key={c.value}
            type="button"
            className="st-action"
            disabled={Boolean(busy) || ((c.value === "LOCAL" || c.value === "FOREIGN") && !country)}
            onClick={() => {
              setLinkTo(null);
              void run(c.value, c.label, null);
            }}
          >
            <strong>{c.label}</strong>
            <span>{c.hint}</span>
          </button>
        ))}
      </div>
      <div className="card st-text">
        <label htmlFor="st-text" className="st-why-label">
          A paragraph, claim or research question
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
              onClick={() => void run(cat, label, text)}
            >
              {label}
            </button>
          ))}
        </div>
        <p className="muted small">
          To check whether a citation you already have supports a statement, use <Link href="/research">the citation check</Link>.
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
                canSave={canSave}
                defaultFolder={DEFAULT_FOLDER[result.category] ?? "OTHER"}
                questions={questions}
                defaultQuestion={linkTo}
                onSave={(f, q) => onSave(s, f, q)}
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
  );
}
