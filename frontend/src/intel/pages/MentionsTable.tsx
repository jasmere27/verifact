import { useState } from "react";
import { navigate } from "../../router";
import { useIntel } from "../context";
import type { Mention } from "../types";
import Link from "../../components/Link";
import { ago, humanize, useAction, useLoad } from "../lib";
import { AiLabel, Badge, Empty, ExternalLink, Load, Notice, Pager } from "../ui";

/** Mentions found by monitoring, with triage actions: review, dismiss, or escalate to an incident. */
export default function MentionsTable({ projectId, refreshKey }: { projectId?: string; refreshKey?: string }) {
  const { api, href } = useIntel();
  const [status, setStatus] = useState("NEW");
  const [relevance, setRelevance] = useState("");
  const [withClaim, setWithClaim] = useState(false);
  const [page, setPage] = useState(0);
  const key = [projectId, status, relevance, withClaim, page, refreshKey].join("|");
  const r = useLoad(() => api.mentions({ projectId, status, relevance, withClaim, page, size: 20 }), key);
  const act = useAction();

  async function setMentionStatus(m: Mention, s: string) {
    const updated = await act.run(m.id, () => api.mentionStatus(m.id, s));
    if (updated) r.reload();
  }

  async function escalate(m: Mention) {
    const res = await act.run(m.id, () => api.escalate(m.id));
    if (res) navigate(href("incidents", res.incidentId));
  }

  return (
    <>
      <div className="i-filters">
        <label className="i-field">
          <span>Status</span>
          <select className="i-input" value={status} onChange={(e) => (setStatus(e.target.value), setPage(0))}>
            <option value="">All</option>
            <option value="NEW">New</option>
            <option value="REVIEWED">Reviewed</option>
            <option value="ESCALATED">Escalated</option>
            <option value="DISMISSED">Dismissed</option>
          </select>
        </label>
        <label className="i-field">
          <span>Relevance</span>
          <select className="i-input" value={relevance} onChange={(e) => (setRelevance(e.target.value), setPage(0))}>
            <option value="">Any</option>
            <option value="HIGH">High</option>
            <option value="MEDIUM">Medium</option>
            <option value="LOW">Low</option>
          </select>
        </label>
        <label className="i-check" style={{ alignSelf: "center" }}>
          <input type="checkbox" checked={withClaim} onChange={(e) => (setWithClaim(e.target.checked), setPage(0))} />
          Only with a flagged claim
        </label>
      </div>
      {act.error && (
        <div style={{ padding: "8px 16px" }}>
          <Notice tone="bad">{act.error}</Notice>
        </div>
      )}
      <Load r={r}>
        {(p) =>
          p.items.length === 0 ? (
            <Empty title={status === "NEW" ? "No new mentions to review" : "No mentions match"}>
              Mentions appear here after monitoring runs. Only items naming the brand, its aliases, products or people are kept.
            </Empty>
          ) : (
            <>
              <ul className="i-list">
                {p.items.map((m) => (
                  <li key={m.id}>
                    <div className="i-row">
                      <div style={{ minWidth: 0 }}>
                        <ExternalLink href={m.url}>
                          <strong>{m.title || m.domain}</strong>
                        </ExternalLink>
                        <div className="i-meta">
                          <span>{m.domain}</span>
                          <span>{humanize(m.connector)}</span>
                          {m.publishedDate && <span>published {m.publishedDate}</span>}
                          <span>found {ago(m.firstSeenAt)}</span>
                          {m.seenCount > 1 && <span>seen {m.seenCount}×</span>}
                          {!projectId && <span>{m.projectName}</span>}
                        </div>
                      </div>
                      <span className="i-actions">
                        <Badge tone={m.relevance === "HIGH" ? "bad" : m.relevance === "MEDIUM" ? "warn" : "neutral"}>{humanize(m.relevance)} relevance</Badge>
                      </span>
                    </div>
                    {m.snippet && <p className="small" style={{ marginTop: 6, color: "var(--i-text-2)" }}>{m.snippet}</p>}
                    {m.claim && (
                      <div className="i-quote i-quote--contradicts" style={{ marginTop: 8 }}>
                        <span className="i-kind">
                          Possible damaging claim <AiLabel what="AI reading" /> {m.claimSeverity && <Badge tone={m.claimSeverity === "HIGH" ? "bad" : "warn"}>{humanize(m.claimSeverity)} severity (AI)</Badge>}
                        </span>
                        <span className="small">{m.claim}</span>
                        {m.claimQuote && <span className="small muted">Source's words: “{m.claimQuote}”</span>}
                      </div>
                    )}
                    <div className="i-meta" style={{ marginTop: 6 }}>
                      {m.matchedTerms.map((t) => (
                        <span key={t} className="i-tag">
                          {t}
                        </span>
                      ))}
                    </div>
                    <div className="i-actions" style={{ marginTop: 6 }}>
                      {m.incidentId ? (
                        <Link href={href("incidents", m.incidentId)} className="i-btn i-btn--sm">
                          Open incident
                        </Link>
                      ) : (
                        <>
                          <button type="button" className="i-btn i-btn--sm i-btn--primary" disabled={act.busy === m.id} onClick={() => void escalate(m)}>
                            Escalate to incident
                          </button>
                          {m.status !== "REVIEWED" && (
                            <button type="button" className="i-btn i-btn--sm" disabled={act.busy === m.id} onClick={() => void setMentionStatus(m, "REVIEWED")}>
                              Mark reviewed
                            </button>
                          )}
                          {m.status !== "DISMISSED" && (
                            <button type="button" className="i-btn i-btn--sm i-btn--ghost" disabled={act.busy === m.id} onClick={() => void setMentionStatus(m, "DISMISSED")}>
                              Dismiss
                            </button>
                          )}
                          {m.status !== "NEW" && (
                            <button type="button" className="i-btn i-btn--sm i-btn--ghost" disabled={act.busy === m.id} onClick={() => void setMentionStatus(m, "NEW")}>
                              Back to new
                            </button>
                          )}
                        </>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
              <Pager page={p.page} size={p.size} total={p.total} onPage={setPage} />
            </>
          )
        }
      </Load>
    </>
  );
}
