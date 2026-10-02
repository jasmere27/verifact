import { useState } from "react";
import type { SuggestResult } from "./api";
import { ROLE_LABEL, STANCE_LABEL } from "./links";
import { questionLabel } from "./types";
import type { Project } from "./types";

/**
 * AI-suggested source ↔ question links (ADR-24). Each comes with the abstract's own words; nothing is linked until the
 * student accepts it, and a rejected pair isn't suggested again.
 */
export default function LinkSuggestions({
  project,
  onSuggest,
  onReview,
}: {
  project: Project;
  onSuggest: () => Promise<SuggestResult | null>;
  onReview: (key: string, questionId: string, accept: boolean) => Promise<void>;
}) {
  const [busy, setBusy] = useState(false);
  const [reviewing, setReviewing] = useState<string | null>(null);
  const [result, setResult] = useState<{ found: number; limitations: string[] } | null>(null);
  const pending = project.suggestions;
  const ready = project.questions.length > 0 && project.library.length > 0;

  async function run() {
    setBusy(true);
    try {
      const r = await onSuggest();
      if (r) setResult({ found: r.found, limitations: r.limitations });
    } finally {
      setBusy(false);
    }
  }

  async function review(key: string, questionId: string, accept: boolean) {
    setReviewing(`${key} ${questionId}`);
    try {
      await onReview(key, questionId, accept);
    } finally {
      setReviewing(null);
    }
  }

  return (
    <section className="report-section" id="pj-links" aria-labelledby="pj-links-heading" tabIndex={-1}>
      <h2 id="pj-links-heading">Which sources answer which question?</h2>
      <p className="muted small">
        We read the abstracts of your saved sources against your research questions and suggest links, each with the
        abstract&apos;s own words. They&apos;re AI suggestions: accept the ones that fit after checking the quote.
      </p>
      <div className="row">
        <button type="button" className="button button--primary button--small" disabled={busy || !ready} onClick={() => void run()}>
          {busy ? "Reading your sources…" : pending.length ? "Look again" : "Suggest links"}
        </button>
        {!ready && <span className="muted small">Add a research question and save some sources first.</span>}
        {busy && <span className="muted small">This takes about 20–40 seconds.</span>}
      </div>
      {result && !busy && (
        <div className="small" role="status">
          <p>{result.found === 0 ? "No new links to suggest. Your sources may not address these questions directly, or they're linked already." : `${result.found} suggestion${result.found === 1 ? "" : "s"} to review.`}</p>
          {result.limitations.map((l) => (
            <p key={l} className="muted">
              {l}
            </p>
          ))}
        </div>
      )}
      {pending.length > 0 && (
        <ul className="pj-suggestions">
          {pending.map((s) => {
            const id = `${s.key} ${s.questionId}`;
            return (
              <li key={id} className="pj-suggestion card">
                <p className="pj-sg-head">
                  <span className="pj-sg-rq">{questionLabel(project.questions, s.questionId)}</span>
                  <span className="pj-sg-role">{ROLE_LABEL[s.role]}</span>
                  {s.stance && <span className={`pj-sg-stance is-${s.stance.toLowerCase()}`}>{STANCE_LABEL[s.stance]}</span>}
                </p>
                <p className="pj-ev-title">{s.title}</p>
                <p className="small">
                  {s.how} <span className="pv-ai">AI suggestion</span>
                </p>
                <p className="st-quote">Abstract: “{s.quote}”</p>
                <div className="row">
                  <button
                    type="button"
                    className="button button--secondary button--small"
                    disabled={reviewing === id}
                    onClick={() => void review(s.key, s.questionId, true)}
                  >
                    Link to {questionLabel(project.questions, s.questionId)}
                  </button>
                  <button type="button" className="text-button" disabled={reviewing === id} onClick={() => void review(s.key, s.questionId, false)}>
                    Doesn&apos;t fit
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
