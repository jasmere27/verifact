import { useCallback, useEffect, useRef, useState } from "react";
import { errorMessage } from "../api";
import { formatDateTime, formatDuration, safeHttpUrl } from "../format";
import Link from "../components/Link";
import VerdictBadge from "../components/VerdictBadge";
import type { Evidence } from "../types";
import { getNewsWorkspace, saveNewsReview, savedToken } from "./api";
import type { ClaimType, ContextIssue, DecisionStatus, NewsClaim, NewsReview, NewsWorkspace as Workspace } from "./types";
import "./news.css";

const TYPE_LABEL: Record<ClaimType, string> = {
  FACT: "Fact",
  STATISTIC: "Statistic",
  QUOTE: "Quote",
  DATE_TIME: "Date/time",
  ATTRIBUTION: "Attribution",
};

const CONTEXT_LABEL: Record<ContextIssue, string> = {
  NONE: "",
  OUTDATED: "Possibly outdated",
  OLD_EVENT_AS_NEW: "Old event presented as new",
  MISSING_CONTEXT: "Missing context",
  MISATTRIBUTED: "Possibly misattributed",
};

const DECISIONS: { value: DecisionStatus; label: string }[] = [
  { value: "CONFIRMED", label: "Confirmed" },
  { value: "NEEDS_WORK", label: "Needs work" },
  { value: "DISPUTED", label: "Disputed" },
];

const DECISION_LABEL: Record<DecisionStatus, string> = {
  UNREVIEWED: "Not reviewed",
  CONFIRMED: "Confirmed",
  NEEDS_WORK: "Needs work",
  DISPUTED: "Disputed",
};

type Save = "idle" | "saving" | "saved" | "error";

function SourceLink({ source }: { source: Evidence | undefined }) {
  if (!source) return null;
  const href = safeHttpUrl(source.url);
  return (
    <span className="small">
      {href ? (
        <a href={href} target="_blank" rel="noopener noreferrer nofollow">
          {source.title || source.domain}
        </a>
      ) : (
        source.title
      )}{" "}
      <span className="muted">
        ({source.domain}
        {source.publishedDate ? `, ${source.publishedDate}` : ""})
      </span>
    </span>
  );
}

function ClaimCard({
  c,
  sources,
  decision,
  editable,
  onDecide,
}: {
  c: NewsClaim;
  sources: Map<string, Evidence>;
  decision: { status: DecisionStatus; note: string | null } | undefined;
  editable: boolean;
  onDecide: (status: DecisionStatus, note: string | null) => void;
}) {
  const status = decision?.status ?? "UNREVIEWED";
  return (
    <li className={`news-claim news-decision--${status.toLowerCase()}`}>
      <div className="news-claim-head">
        <span className="muted small">
          {c.id} · {TYPE_LABEL[c.type]}
          {c.speaker ? ` · ${c.speaker}` : ""}
        </span>
        <VerdictBadge verdict={c.verdict} size="sm" />
      </div>
      <p className="news-article-quote">“{c.articleQuote}”</p>
      <div className="news-flags">
        {c.type === "QUOTE" && (
          <span className={`news-flag ${c.quoteStatus === "FOUND_VERBATIM" ? "news-flag--good" : "news-flag--warn"}`}>
            {c.quoteStatus === "FOUND_VERBATIM" ? "Quote found word for word" : "Quote not located in sources"}
          </span>
        )}
        {c.contextIssue !== "NONE" && <span className="news-flag news-flag--warn">{CONTEXT_LABEL[c.contextIssue]}</span>}
        {c.sourcesConflict && <span className="news-flag news-flag--bad">Sources disagree</span>}
      </div>
      {c.explanation && <p>{c.explanation}</p>}
      {c.quoteSource && (
        <div className="news-excerpt">
          <p className="news-excerpt-label">Quote appears in</p>
          <p className="news-source-words">“{c.quoteSource.excerpt}”</p>
          <SourceLink source={sources.get(c.quoteSource.sourceId)} />
        </div>
      )}
      {c.supporting && (
        <div className="news-excerpt">
          <p className="news-excerpt-label">Supporting source says</p>
          <p className="news-source-words">“{c.supporting.excerpt}”</p>
          <SourceLink source={sources.get(c.supporting.sourceId)} />
        </div>
      )}
      {c.contradicting && (
        <div className="news-excerpt news-excerpt--against">
          <p className="news-excerpt-label">Contradicting source says</p>
          <p className="news-source-words">“{c.contradicting.excerpt}”</p>
          <SourceLink source={sources.get(c.contradicting.sourceId)} />
        </div>
      )}
      <div className="news-review">
        {editable ? (
          <>
            <div className="news-review-buttons" role="group" aria-label={`Editor decision for ${c.id}`}>
              {DECISIONS.map((d) => (
                <button
                  key={d.value}
                  type="button"
                  className={`button button--small ${status === d.value ? "button--primary" : "button--secondary"}`}
                  aria-pressed={status === d.value}
                  onClick={() => onDecide(status === d.value ? "UNREVIEWED" : d.value, decision?.note ?? null)}
                >
                  {d.label}
                </button>
              ))}
            </div>
            <label className="visually-hidden" htmlFor={`note-${c.id}`}>
              Note for {c.id}
            </label>
            <textarea
              id={`note-${c.id}`}
              className="text-input news-note"
              placeholder="Editor note (sources called, corrections needed…)"
              value={decision?.note ?? ""}
              maxLength={1000}
              rows={2}
              onChange={(e) => onDecide(status, e.target.value || null)}
            />
          </>
        ) : (
          <p className="small">
            <strong>Editor:</strong> {DECISION_LABEL[status]}
            {decision?.note ? ` · ${decision.note}` : ""}
          </p>
        )}
      </div>
    </li>
  );
}

function markdown(w: Workspace): string {
  const { check, review } = w;
  const src = new Map(check.sources.map((s) => [s.id, s]));
  const lines = [
    `# NewsFact review: ${check.articleTitle ?? "Article"}`,
    check.articleUrl ? `Article: ${check.articleUrl}` : "",
    `Checked: ${check.createdAt}${review.updatedAt ? ` · Reviewed: ${review.updatedAt}` : ""}`,
    "",
    review.editorNote ? `**Editor's note:** ${review.editorNote}\n` : "",
  ];
  for (const c of check.claims) {
    const d = review.decisions[c.id];
    lines.push(`## ${c.id} · ${TYPE_LABEL[c.type]} · ${c.verdict.replace(/_/g, " ").toLowerCase()} · editor: ${DECISION_LABEL[d?.status ?? "UNREVIEWED"]}`);
    lines.push(`> ${c.articleQuote}`);
    if (c.type === "QUOTE") lines.push(`- Quote: ${c.quoteStatus === "FOUND_VERBATIM" ? "found word for word" : "not located in sources"}`);
    if (c.contextIssue !== "NONE") lines.push(`- Context: ${CONTEXT_LABEL[c.contextIssue]}`);
    if (c.supporting) lines.push(`- Supports: "${c.supporting.excerpt}" (${src.get(c.supporting.sourceId)?.url ?? ""})`);
    if (c.contradicting) lines.push(`- Contradicts: "${c.contradicting.excerpt}" (${src.get(c.contradicting.sourceId)?.url ?? ""})`);
    if (d?.note) lines.push(`- Editor note: ${d.note}`);
    lines.push("");
  }
  lines.push(`_${check.notice}_`);
  return lines.filter((l, i) => l !== "" || lines[i - 1] !== "").join("\n");
}

export default function NewsWorkspace({ id }: { id: string }) {
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [review, setReview] = useState<NewsReview | null>(null);
  const [save, setSave] = useState<Save>("idle");
  const [tab, setTab] = useState<"claims" | "report">("claims");
  const [copied, setCopied] = useState(false);
  const token = savedToken(id);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    const controller = new AbortController();
    getNewsWorkspace(id, controller.signal)
      .then((w) => {
        setWorkspace(w);
        setReview(w.review);
      })
      .catch((err) => {
        if (!controller.signal.aborted) setError(errorMessage(err).message);
      });
    return () => controller.abort();
  }, [id]);

  useEffect(() => {
    if (workspace) headingRef.current?.focus();
  }, [workspace]);

  const persist = useCallback(
    (next: NewsReview) => {
      if (!token) return;
      if (timer.current) clearTimeout(timer.current);
      setSave("saving");
      timer.current = setTimeout(() => {
        saveNewsReview(id, token, next)
          .then(() => setSave("saved"))
          .catch(() => setSave("error"));
      }, 2500); // batch rapid edits; review saves are rate limited
    },
    [id, token],
  );

  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current);
  }, []);

  if (error) {
    return (
      <section className="card state-card" aria-labelledby="news-missing">
        <h1 id="news-missing">Review not available</h1>
        <p className="muted">{error}</p>
        <Link href="/news" className="button button--primary">
          Check a story
        </Link>
      </section>
    );
  }
  if (!workspace || !review) {
    return (
      <div className="card state-card" role="status">
        <span className="spinner" aria-hidden="true" />
        <p>Loading the review…</p>
      </div>
    );
  }

  const { check } = workspace;
  const sources = new Map(check.sources.map((s) => [s.id, s]));
  const editable = Boolean(token);
  const reviewed = check.claims.filter((c) => (review.decisions[c.id]?.status ?? "UNREVIEWED") !== "UNREVIEWED").length;
  const flagged = check.claims.filter(
    (c) =>
      c.verdict === "CONTRADICTED" ||
      c.verdict === "MISLEADING" ||
      c.sourcesConflict ||
      c.contextIssue !== "NONE" ||
      c.quoteStatus === "NOT_LOCATED",
  ).length;

  function decide(claimId: string, status: DecisionStatus, note: string | null) {
    const next: NewsReview = { ...review!, decisions: { ...review!.decisions, [claimId]: { status, note } } };
    setReview(next);
    persist(next);
  }

  function setEditorNote(note: string) {
    const next: NewsReview = { ...review!, editorNote: note || null };
    setReview(next);
    persist(next);
  }

  const articleHref = check.articleUrl ? safeHttpUrl(check.articleUrl) : null;
  const report = markdown({ ...workspace, review });

  return (
    <article className="report news-workspace" aria-labelledby="news-ws-heading">
      <header className="news-hero">
        <p className="hero-kicker">NewsFact · Story check · {formatDateTime(check.createdAt)}</p>
        <h1 id="news-ws-heading" ref={headingRef} tabIndex={-1}>
          {check.articleTitle ?? "Pasted article"}
        </h1>
        {articleHref && (
          <p className="small">
            <a href={articleHref} target="_blank" rel="noopener noreferrer nofollow" className="break">
              {check.articleUrl}
            </a>
          </p>
        )}
        <dl className="news-stats">
          <div>
            <dt>Claims</dt>
            <dd>{check.claims.length}</dd>
          </div>
          <div>
            <dt>Flagged</dt>
            <dd>{flagged}</dd>
          </div>
          <div>
            <dt>Reviewed</dt>
            <dd>
              {reviewed} / {check.claims.length}
            </dd>
          </div>
          <div>
            <dt>Sources</dt>
            <dd>{check.sources.length}</dd>
          </div>
        </dl>
        <p className="news-status small" aria-live="polite">
          {!editable
            ? "Read-only: only the person who ran this check can review it."
            : save === "saving"
              ? "Saving…"
              : save === "saved"
                ? "All changes saved."
                : save === "error"
                  ? "Couldn't save the last change. Keep this page open and try again."
                  : "Your decisions save automatically."}
        </p>
        <div className="mode-tabs" role="tablist" aria-label="Workspace">
          <button type="button" role="tab" className="mode-tab" aria-selected={tab === "claims"} onClick={() => setTab("claims")}>
            Claims
          </button>
          <button type="button" role="tab" className="mode-tab" aria-selected={tab === "report"} onClick={() => setTab("report")}>
            Report
          </button>
        </div>
      </header>

      {tab === "claims" ? (
        <section className="report-section" aria-label="Claims">
          <ul className="news-claims">
            {check.claims.map((c) => (
              <ClaimCard
                key={c.id}
                c={c}
                sources={sources}
                decision={review.decisions[c.id]}
                editable={editable}
                onDecide={(status, note) => decide(c.id, status, note)}
              />
            ))}
          </ul>
          {check.limitations.length > 0 && (
            <ul className="limitations">
              {check.limitations.map((l) => (
                <li key={l}>{l}</li>
              ))}
            </ul>
          )}
        </section>
      ) : (
        <section className="report-section" aria-label="Report">
          {editable ? (
            <div className="news-editor-note">
              <label htmlFor="editor-note" className="news-excerpt-label">
                Editor&apos;s summary (appears at the top of the report)
              </label>
              <textarea
                id="editor-note"
                className="text-input news-note"
                rows={3}
                maxLength={1000}
                value={review.editorNote ?? ""}
                onChange={(e) => setEditorNote(e.target.value)}
              />
            </div>
          ) : (
            review.editorNote && (
              <p>
                <strong>Editor&apos;s summary:</strong> {review.editorNote}
              </p>
            )
          )}
          <pre className="news-report">{report}</pre>
          <div className="row">
            <button
              type="button"
              className="button button--secondary button--small"
              onClick={() => {
                void navigator.clipboard?.writeText(report).then(() => setCopied(true));
              }}
            >
              {copied ? "Copied" : "Copy as Markdown"}
            </button>
            <button type="button" className="button button--quiet button--small" onClick={() => window.print()}>
              Print or save as PDF
            </button>
          </div>
        </section>
      )}

      <p className="news-disclaimer small">{check.notice}</p>
      <p className="muted small">
        Anyone with this link can view this review. Search: {check.searchProvider} · took {formatDuration(check.durationMs)}.
      </p>
    </article>
  );
}
