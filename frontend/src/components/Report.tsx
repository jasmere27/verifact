import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import type { ReactNode, SyntheticEvent } from "react";
import { formatDate, formatDateTime, formatDuration, formatPublishedDate, plural, safeHttpUrl } from "../format";
import { buildReply, heroStrength, reportUrl } from "../share";
import { avatarLetter, avatarSlot, byTypePriority, domainOf, isSocial, sourceTypeMeta, toSourceType } from "../sources";
import type { ClaimAssessment, Evidence, VerificationResult } from "../types";
import { INPUT_TYPE_LABEL, strengthMeta, verdictMeta } from "../verdicts";
import Link from "./Link";
import VerdictBadge from "./VerdictBadge";
import VerdictIcon from "./VerdictIcon";

/* ---------- Helpers ---------- */

const WIDE_QUERY = "(min-width: 641px)";

function subscribeWide(onChange: () => void) {
  const mql = window.matchMedia(WIDE_QUERY);
  mql.addEventListener("change", onChange);
  return () => mql.removeEventListener("change", onChange);
}

const useWideScreen = () => useSyncExternalStore(subscribeWide, () => window.matchMedia(WIDE_QUERY).matches);

function StrengthDots({ strength }: { strength: string }) {
  const meta = strengthMeta(strength);
  const level = typeof strength === "string" ? strength.toLowerCase() : "unknown";
  return (
    <p className={`strength strength--${level}`}>
      <span className="strength-dots" aria-hidden="true">
        <span />
        <span />
        <span />
      </span>
      <span className="strength-label">{meta.label} evidence</span>
      <span className="strength-meaning"> — {meta.meaning}</span>
    </p>
  );
}

/* ---------- Sources ---------- */

function SourceItem({ evidence }: { evidence: Evidence }) {
  const href = safeHttpUrl(evidence.url ?? "");
  const domain = domainOf(evidence);
  const title = evidence.title?.trim() || domain;
  const type = toSourceType(evidence.sourceType);
  const social = type === "SOCIAL";
  return (
    <li className={`source source--${type.toLowerCase()}`}>
      <span className={`source-avatar av-${social ? "social" : avatarSlot(domain)}`} aria-hidden="true">
        {avatarLetter(domain)}
      </span>
      <div className="source-body">
        <p className="source-meta">
          <span className="source-domain">{domain}</span>
          <span className={`type-badge type-badge--${type.toLowerCase()}`}>
            {social ? "Social media / forum — lower reliability" : sourceTypeMeta(type).label}
          </span>
        </p>
        <p className="source-title">
          {href ? (
            <a href={href} target="_blank" rel="noopener noreferrer nofollow">
              {title}
              <span className="visually-hidden"> (opens in a new tab)</span>
            </a>
          ) : (
            title
          )}
        </p>
        {evidence.snippet?.trim() && <blockquote className="source-quote">{evidence.snippet.trim()}</blockquote>}
        <p className="source-foot">
          {evidence.publishedDate ? `Published ${formatPublishedDate(evidence.publishedDate)}` : "Date unknown"}
          {" · "}Retrieved {formatDate(evidence.retrievedAt)}
        </p>
      </div>
    </li>
  );
}

function SourceGroup({ heading, items, empty, kind }: { heading: string; items: Evidence[]; empty: string; kind: "support" | "contra" }) {
  return (
    <div className={`source-group source-group--${kind}`}>
      <h4>
        {heading} <span className="count">({items.length})</span>
      </h4>
      {items.length ? (
        <ul className="source-list">
          {byTypePriority(items).map((e) => (
            <SourceItem key={e.id} evidence={e} />
          ))}
        </ul>
      ) : (
        <p className="muted small">{empty}</p>
      )}
    </div>
  );
}

/* ---------- Claims ---------- */

function tally(supporting: number, contradicting: number): string {
  if (!supporting && !contradicting) return "No sources cited for this claim";
  const parts: string[] = [];
  if (supporting) parts.push(`${plural(supporting, "source")} ${supporting === 1 ? "supports" : "support"}`);
  if (contradicting) {
    const verb = contradicting === 1 ? "contradicts" : "contradict";
    parts.push(supporting ? `${contradicting} ${verb}` : `${plural(contradicting, "source")} ${verb}`);
  }
  return parts.join(" · ");
}

function ClaimCard({
  claim,
  index,
  total,
  lookup,
  wide,
}: {
  claim: ClaimAssessment;
  index: number;
  total: number;
  lookup: Map<string, Evidence>;
  wide: boolean;
}) {
  const meta = verdictMeta(claim.verdict);
  const resolve = (ids: string[]) => ids.map((id) => lookup.get(id)).filter((e): e is Evidence => Boolean(e));
  const supporting = resolve(claim.supportingEvidenceIds);
  const contradicting = resolve(claim.contradictingEvidenceIds);
  const count = supporting.length + contradicting.length;
  const [open, setOpen] = useState(wide);
  const [lastWide, setLastWide] = useState(wide);
  if (wide !== lastWide) {
    setLastWide(wide);
    setOpen(wide);
  }

  return (
    <article className={`claim-card tone-${meta.tone}`} aria-labelledby={`claim-${index}-text`}>
      <header className="claim-head">
        <VerdictBadge verdict={claim.verdict} />
        {total > 1 && (
          <span className="claim-number">
            Claim {index + 1} of {total}
          </span>
        )}
      </header>
      <h3 id={`claim-${index}-text`} className="claim-text">
        {claim.text}
      </h3>
      <p className="claim-tally">{tally(supporting.length, contradicting.length)}</p>
      <StrengthDots strength={claim.evidenceStrength} />
      {claim.explanation?.trim() && <p className="claim-why">{claim.explanation}</p>}
      {count > 0 && (
        <details
          className="claim-sources-toggle"
          open={open}
          onToggle={(e: SyntheticEvent<HTMLDetailsElement>) => setOpen(e.currentTarget.open)}
        >
          <summary>
            {open ? "Hide sources" : "Show sources"} <span className="count">({count})</span>
          </summary>
          <div className="claim-sources">
            <SourceGroup kind="support" heading="Supporting" items={supporting} empty="None cited in support." />
            <SourceGroup kind="contra" heading="Contradicting" items={contradicting} empty="None cited against this." />
          </div>
        </details>
      )}
    </article>
  );
}

/* ---------- Actions ---------- */

type Feedback =
  | { kind: "idle" }
  | { kind: "copied"; what: "reply" | "link" }
  | { kind: "manual"; what: "reply" | "link"; text: string };

function useReportActions(result: VerificationResult) {
  const [feedback, setFeedback] = useState<Feedback>({ kind: "idle" });

  useEffect(() => {
    if (feedback.kind !== "copied") return;
    const timer = window.setTimeout(() => setFeedback({ kind: "idle" }), 2500);
    return () => window.clearTimeout(timer);
  }, [feedback]);

  async function copy(text: string, what: "reply" | "link") {
    try {
      await navigator.clipboard.writeText(text);
      setFeedback({ kind: "copied", what });
    } catch {
      setFeedback({ kind: "manual", what, text });
    }
  }

  async function share() {
    const url = reportUrl(result.id);
    const label = verdictMeta(result.overallVerdict).label;
    const data = { title: `VeriFact: ${label}`, text: `VeriFact check — ${label}`, url };
    if (typeof navigator.share === "function" && (!navigator.canShare || navigator.canShare(data))) {
      try {
        await navigator.share(data);
        return;
      } catch (err) {
        if (err instanceof DOMException && err.name === "AbortError") return; // user closed the share sheet
      }
    }
    await copy(url, "link");
  }

  return {
    feedback,
    share,
    copyReply: () => copy(buildReply(result), "reply"),
    dismiss: () => setFeedback({ kind: "idle" }),
  };
}

function ActionButtons({ actions, variant }: { actions: ReturnType<typeof useReportActions>; variant: "hero" | "bar" }) {
  const { feedback } = actions;
  const copied = (what: "reply" | "link") => feedback.kind === "copied" && feedback.what === what;
  return (
    <div className={variant === "hero" ? "hero-actions" : "action-bar-inner"}>
      <button type="button" className="button button--primary" onClick={actions.share}>
        <ShareIcon />
        {copied("link") ? "Link copied" : "Share"}
      </button>
      <button type="button" className="button button--secondary" onClick={actions.copyReply}>
        <ReplyIcon />
        {copied("reply") ? "Copied" : "Copy reply"}
      </button>
      <Link href="/" className="button button--secondary">
        <PlusIcon />
        New check
      </Link>
    </div>
  );
}

const iconProps = {
  width: 18,
  height: 18,
  viewBox: "0 0 20 20",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.8,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
  "aria-hidden": true,
  focusable: false,
};

const ShareIcon = () => (
  <svg {...iconProps}>
    <path d="M10 13V3M6.5 6.5L10 3l3.5 3.5" />
    <path d="M5 10v6h10v-6" />
  </svg>
);
const ReplyIcon = () => (
  <svg {...iconProps}>
    <path d="M4 4h12v9H9l-4 3v-3H4z" />
  </svg>
);
const PlusIcon = () => (
  <svg {...iconProps}>
    <path d="M10 4v12M4 10h12" />
  </svg>
);

function Collapsible({ summary, children }: { summary: string; children: ReactNode }) {
  return (
    <details className="collapsible">
      <summary>{summary}</summary>
      <div className="collapsible-body">{children}</div>
    </details>
  );
}

/* ---------- Report ---------- */

export default function Report({ result }: { result: VerificationResult }) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  const wide = useWideScreen();
  const actions = useReportActions(result);

  useEffect(() => {
    headingRef.current?.focus();
  }, [result.id]);

  const overall = verdictMeta(result.overallVerdict);
  const lookup = new Map(result.evidence.map((e) => [e.id, e]));
  const cited = new Set(result.claims.flatMap((c) => [...c.supportingEvidenceIds, ...c.contradictingEvidenceIds]));
  const uncited = byTypePriority(result.evidence.filter((e) => !cited.has(e.id)));
  const socialCount = result.evidence.filter(isSocial).length;
  const inputType = INPUT_TYPE_LABEL[result.inputType] ?? result.inputType;
  const inputHref = result.inputType === "URL" ? safeHttpUrl(result.input) : null;
  const strength = heroStrength(result);
  const { feedback } = actions;

  return (
    <article className="report" aria-labelledby="report-heading">
      <header className={`hero tone-${overall.tone}`}>
        <p className="hero-kicker">
          Verdict · {inputType} · <time dateTime={result.createdAt}>{formatDate(result.createdAt)}</time>
        </p>
        <h1 id="report-heading" ref={headingRef} tabIndex={-1} className="hero-verdict">
          <VerdictIcon tone={overall.tone} size={34} />
          <span>
            <span className="visually-hidden">Verdict: </span>
            {overall.label}
          </span>
        </h1>
        <p className="hero-meaning">{overall.meaning}</p>

        {result.claims.length === 1 ? (
          <p className="hero-claim">
            <span className="visually-hidden">Claim checked: </span>“{result.claims[0].text}”
          </p>
        ) : result.claims.length > 1 ? (
          <p className="hero-claim hero-claim--count">{result.claims.length} claims checked — see each one below.</p>
        ) : (
          <p className="hero-claim hero-claim--count">No specific factual claim was found in this input.</p>
        )}

        {result.summary?.trim() && <p className="hero-summary">{result.summary}</p>}
        {strength && <StrengthDots strength={strength} />}

        <ActionButtons actions={actions} variant="hero" />
        <p className="share-note">Anyone with this link can view this report, including what was submitted.</p>
      </header>

      {feedback.kind === "manual" && (
        <div className="manual-copy" role="group" aria-labelledby="manual-copy-label">
          <label id="manual-copy-label" htmlFor="manual-copy-text">
            Couldn&apos;t copy automatically. Select and copy this {feedback.what === "reply" ? "message" : "link"}:
          </label>
          <textarea
            id="manual-copy-text"
            readOnly
            rows={feedback.what === "reply" ? 5 : 2}
            value={feedback.text}
            onFocus={(e) => e.currentTarget.select()}
            autoFocus
          />
          <button type="button" className="text-button" onClick={actions.dismiss}>
            Done
          </button>
        </div>
      )}

      <div className="visually-hidden" aria-live="polite" aria-atomic="true">
        {feedback.kind === "copied" ? (feedback.what === "reply" ? "Reply copied to clipboard." : "Link copied to clipboard.") : ""}
      </div>

      {result.claims.length > 0 && (
        <section className="report-section" aria-labelledby="claims-heading">
          <h2 id="claims-heading">{result.claims.length === 1 ? "The claim and its evidence" : "Claim by claim"}</h2>
          <div className="claim-list">
            {result.claims.map((claim, index) => (
              <ClaimCard
                key={claim.id}
                claim={claim}
                index={index}
                total={result.claims.length}
                lookup={lookup}
                wide={wide}
              />
            ))}
          </div>
          {socialCount > 0 && (
            <p className="muted small">
              Social media and forum posts are shown last and don&apos;t count towards evidence strength.
            </p>
          )}
        </section>
      )}

      {uncited.length > 0 && (
        <section className="report-section" aria-labelledby="other-heading">
          <h2 id="other-heading">Other sources reviewed</h2>
          <details className="collapsible">
            <summary>Show {plural(uncited.length, "source")} not cited for any claim</summary>
            <ul className="source-list collapsible-body">
              {uncited.map((e) => (
                <SourceItem key={e.id} evidence={e} />
              ))}
            </ul>
          </details>
        </section>
      )}

      <section className="report-section" aria-labelledby="limits-heading">
        <h2 id="limits-heading">What remains uncertain</h2>
        {result.limitations.length ? (
          <ul className="limitations">
            {result.limitations.map((item, i) => (
              <li key={i}>{item}</li>
            ))}
          </ul>
        ) : (
          <p className="muted">No specific limitations were reported for this check.</p>
        )}
        <p className="standing-note">
          VeriFact is an aid, not a final authority. Read the sources and weigh them yourself.
        </p>
      </section>

      <section className="report-section" aria-labelledby="details-heading">
        <h2 id="details-heading">Details</h2>
        <div className="collapsibles">
          <Collapsible summary={`Original input (${inputType.toLowerCase()})`}>
            {inputHref ? (
              <a href={inputHref} target="_blank" rel="noopener noreferrer nofollow" className="break">
                {result.input}
              </a>
            ) : (
              <p className="prewrap">{result.input}</p>
            )}
          </Collapsible>
          {result.checkedText?.trim() && (
            <Collapsible summary="Text that was analysed (excerpt)">
              <p className="prewrap">{result.checkedText}</p>
            </Collapsible>
          )}
          <Collapsible summary="About this check">
            <dl className="about-list">
              <div>
                <dt>Checked at</dt>
                <dd>{formatDateTime(result.createdAt)}</dd>
              </div>
              <div>
                <dt>Search provider</dt>
                <dd>{result.searchProvider || "—"}</dd>
              </div>
              <div>
                <dt>Time taken</dt>
                <dd>{formatDuration(result.durationMs)}</dd>
              </div>
              <div>
                <dt>Sources retrieved</dt>
                <dd>{result.evidence.length}</dd>
              </div>
              <div>
                <dt>Report ID</dt>
                <dd className="report-id">{result.id}</dd>
              </div>
            </dl>
          </Collapsible>
        </div>
      </section>

      <nav className="action-bar" aria-label="Report actions">
        <ActionButtons actions={actions} variant="bar" />
      </nav>
    </article>
  );
}
