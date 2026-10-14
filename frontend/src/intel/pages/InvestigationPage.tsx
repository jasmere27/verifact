import { useState } from "react";
import { navigate } from "../../router";
import { useIntel } from "../context";
import type { ClaimResult, Excerpt, InvestigationDetail, InvestigationResult } from "../types";
import Link from "../../components/Link";
import { ago, ASSESSMENT_LABEL, fmtDateTime, humanize, useAction, useLoad, usePoll, useToast } from "../lib";
import { AiLabel, AssessmentBadge, Badge, Dialog, ErrorBox, ExternalLink, Notice, PageHead, Panel, Skeleton } from "../ui";

export default function InvestigationPage({ id }: { id: string }) {
  const { api, href, can } = useIntel();
  const r = useLoad(() => api.investigation(id), id);
  const [viewVersion, setViewVersion] = useState<number | null>(null);
  const older = useLoad(() => (viewVersion ? api.investigationVersion(id, viewVersion) : Promise.resolve(null)), `${id}|${viewVersion}`);
  const act = useAction();
  const toast = useToast();
  const [notes, setNotes] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  usePoll(r.data?.summary.status === "RUNNING", r.reload, 3000);

  if (r.error) return <ErrorBox error={r.error} onRetry={r.reload} />;
  const d = r.data;
  if (!d) return <Skeleton lines={10} />;
  const s = d.summary;
  const showing: InvestigationResult | null = viewVersion && older.data ? older.data : d.result;

  async function rerun() {
    const updated = await act.run("rerun", () => api.rerun(id));
    if (updated) {
      r.setData(updated);
      setViewVersion(null);
      toast.show("Re-running with fresh searches. The earlier version is kept.");
    }
  }

  async function saveNotes(markReviewed: boolean) {
    const updated = await act.run("notes", () => api.saveNotes(id, notes, markReviewed));
    if (updated) {
      r.setData(updated);
      setNotes(null);
      toast.show(markReviewed ? "Marked as reviewed." : "Notes saved.");
    }
  }

  async function remove() {
    const done = await act.run("delete", async () => {
      await api.deleteInvestigation(id);
      return true;
    });
    if (done) navigate(href("investigations"));
  }

  return (
    <>
      <PageHead
        title={s.title}
        crumbs={<Link href={href("investigations")}>Investigations</Link>}
        sub={
          <>
            {s.status === "RUNNING" ? (
              <Badge tone="info">
                <span className="i-spinner" aria-hidden="true" /> Gathering and assessing evidence…
              </Badge>
            ) : (
              <Badge tone={s.status === "FAILED" ? "bad" : "ok"}>{humanize(s.status)}</Badge>
            )}
            {s.version > 0 && <> · version {s.version}</>} · updated {ago(s.updatedAt)}
            {s.lastReviewedAt && <> · reviewed {ago(s.lastReviewedAt)}</>}
            {s.incidentId && (
              <>
                {" "}
                · incident <Link href={href("incidents", s.incidentId)}>{s.incidentTitle}</Link>
              </>
            )}
          </>
        }
        actions={
          <>
            {d.result && (
              <Link href={`${href("responses")}?investigation=${id}${s.incidentId ? `&incident=${s.incidentId}` : ""}`} className="i-btn i-btn--primary">
                Draft response
              </Link>
            )}
            <button type="button" className="i-btn" disabled={s.status === "RUNNING" || act.busy === "rerun"} onClick={() => void rerun()}>
              Re-run with fresh evidence
            </button>
            {can("ADMIN") && (
              <button type="button" className="i-btn i-btn--danger" onClick={() => setConfirmDelete(true)}>
                Delete
              </button>
            )}
          </>
        }
      />
      {act.error && <Notice tone="bad">{act.error}</Notice>}
      {s.status === "FAILED" && d.errorMessage && (
        <Notice tone="bad" title="The last run failed">
          {d.errorMessage}
        </Notice>
      )}
      {s.status === "RUNNING" && !d.result && (
        <Panel>
          <p className="small muted">Extracting checkable claims, searching the web and checking each source's wording. This page updates by itself.</p>
          <Skeleton lines={5} />
        </Panel>
      )}

      {showing && (
        <div className="i-split" style={{ marginTop: 12 }}>
          <div className="i-stack">
            {viewVersion && (
              <Notice tone="info" title={`Viewing version ${viewVersion}`}>
                This is an earlier assessment, kept for the record.{" "}
                <button type="button" className="i-btn i-btn--sm" onClick={() => setViewVersion(null)}>
                  Back to the latest
                </button>
              </Notice>
            )}
            {!viewVersion && d.changes && (
              <Panel title={`What changed since version ${d.changes.previousVersion}`} id="changes">
                <p className="small">{d.changes.summary}</p>
                {d.changes.claims.length > 0 && (
                  <ul className="small" style={{ margin: "8px 0 0", paddingLeft: 18 }}>
                    {d.changes.claims.map((c) => (
                      <li key={c.claim}>
                        “{c.claim}” — {c.before ? `${ASSESSMENT_LABEL[c.before as keyof typeof ASSESSMENT_LABEL] ?? c.before} → ` : "new claim: "}
                        <strong>{ASSESSMENT_LABEL[c.after as keyof typeof ASSESSMENT_LABEL] ?? c.after}</strong>
                      </li>
                    ))}
                  </ul>
                )}
              </Panel>
            )}
            <div className="i-legend">
              <span>
                <strong>Allegation</strong>: what was submitted, not verified
              </span>
              <span>
                <strong>Source's words</strong>: found word for word in the retrieved source
              </span>
              <span>
                <AiLabel what="AI" /> interpretation: review it
              </span>
            </div>
            {showing.claims.length === 0 && <Notice tone="warn">No checkable claims were found in the input.</Notice>}
            {showing.claims.map((c) => (
              <ClaimCard key={c.id} c={c} />
            ))}
            <Panel title={`Sources retrieved (${showing.sources.length})`} id="sources" tight>
              {showing.sources.length === 0 ? (
                <p className="small muted" style={{ padding: 16 }}>
                  The search found no sources. Nothing could be confirmed or contradicted.
                </p>
              ) : (
                <ul className="i-list">
                  {showing.sources.map((src) => (
                    <li key={src.id}>
                      <div className="i-row">
                        <span>
                          <span className="mono muted">{src.id}</span> <ExternalLink href={src.url}>{src.title || src.domain}</ExternalLink>
                        </span>
                        <Badge tone={src.direction === "SUPPORTS" ? "ok" : src.direction === "CONTRADICTS" ? "bad" : src.direction === "MIXED" ? "warn" : "neutral"}>
                          {src.direction === "CONTEXT" ? "Context (not cited)" : humanize(src.direction)}
                        </Badge>
                      </div>
                      <div className="i-meta">
                        <span>{src.domain}</span>
                        <span>{humanize(src.sourceType)}</span>
                        <span>published {src.publishedDate ?? "date unknown"}</span>
                        <span>retrieved {fmtDateTime(src.retrievedAt)}</span>
                      </div>
                      {src.snippet && <p className="small muted" style={{ marginTop: 4 }}>{src.snippet}</p>}
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
          </div>

          <div className="i-stack">
            <Panel title="Unresolved questions" id="unresolved">
              {showing.unresolved.length === 0 && showing.limitations.length === 0 ? (
                <p className="small muted">None recorded for this version.</p>
              ) : (
                <ul className="small" style={{ margin: 0, paddingLeft: 18, display: "grid", gap: 6 }}>
                  {[...showing.unresolved, ...showing.limitations].map((u) => (
                    <li key={u}>{u}</li>
                  ))}
                </ul>
              )}
            </Panel>
            <Panel title="Chronology" id="chronology">
              <ol className="i-timeline">
                {showing.chronology.map((t, i) => (
                  <li key={`${t.date}-${i}`}>
                    <time>{t.date}</time>
                    {t.sourceId ? <span className="mono muted">{t.sourceId} </span> : null}
                    {t.label}
                  </li>
                ))}
              </ol>
              <p className="small muted" style={{ marginTop: 8 }}>Undated sources are listed under Sources only.</p>
            </Panel>
            <Panel title="Analyst notes" id="notes">
              <div className="i-stack-sm">
                <textarea className="i-input" value={notes ?? d.analystNotes ?? ""} onChange={(e) => setNotes(e.target.value)} placeholder="Context, calls made, primary sources contacted…" />
                <div className="i-actions">
                  <button type="button" className="i-btn i-btn--sm" disabled={notes === null || act.busy === "notes"} onClick={() => void saveNotes(false)}>
                    Save notes
                  </button>
                  <button type="button" className="i-btn i-btn--sm" disabled={act.busy === "notes"} onClick={() => void saveNotes(true)}>
                    Mark reviewed
                  </button>
                </div>
              </div>
            </Panel>
            <Versions d={d} viewing={viewVersion} onView={setViewVersion} />
            <Panel title="Method" id="method">
              <p className="small muted">{showing.method}</p>
              <p className="small muted" style={{ marginTop: 6 }}>Submitted: {d.input.length > 300 ? `${d.input.slice(0, 300)}…` : d.input}</p>
            </Panel>
          </div>
        </div>
      )}
      <Dialog
        open={confirmDelete}
        title="Delete this investigation?"
        onClose={() => setConfirmDelete(false)}
        footer={
          <>
            <button type="button" className="i-btn" onClick={() => setConfirmDelete(false)}>
              Cancel
            </button>
            <button type="button" className="i-btn i-btn--danger" onClick={() => void remove()}>
              Delete investigation and its evidence
            </button>
          </>
        }
      >
        <p>All versions and evidence records are deleted. Response drafts made from it are kept.</p>
      </Dialog>
      {toast.node}
    </>
  );
}

function ClaimCard({ c }: { c: ClaimResult }) {
  return (
    <article className="i-claim">
      <div className="i-claim-head">
        <div className="i-row">
          <span className="i-kind">
            {c.id} · {humanize(c.type)} · allegation as stated
          </span>
          <AssessmentBadge a={c.assessment} />
        </div>
        <p className="i-claim-text">{c.claim}</p>
        {c.originalWords && c.originalWords !== c.claim && <p className="small muted">Submitted wording: “{c.originalWords}”</p>}
      </div>
      <div className="i-claim-body">
        {c.supporting && <Quote kind="supports" label="Supporting source's words" e={c.supporting} />}
        {c.contradicting && <Quote kind="contradicts" label="Contradicting source's words" e={c.contradicting} />}
        {c.quoteSource && <Quote kind="quote" label="Quoted words found in" e={c.quoteSource} />}
        {!c.supporting && !c.contradicting && <p className="small muted">No retrieved source settles this claim.</p>}
        {c.sourcesConflict && <Notice tone="warn">Sources disagree on this claim.</Notice>}
        {c.contextIssue !== "NONE" && <Notice tone="warn">Context issue: {humanize(c.contextIssue)}.</Notice>}
        {c.quoteStatus === "NOT_LOCATED" && <Notice tone="info">The quoted words weren't found word for word in these sources. That doesn't mean they're fabricated.</Notice>}
        {c.aiExplanation && (
          <p className="small">
            <AiLabel what="AI interpretation" /> {c.aiExplanation}
          </p>
        )}
      </div>
    </article>
  );
}

function Quote({ kind, label, e }: { kind: "supports" | "contradicts" | "quote"; label: string; e: Excerpt }) {
  return (
    <div className={`i-quote i-quote--${kind}`}>
      <span className="i-kind">{label}</span>
      <blockquote>“{e.text}”</blockquote>
      <span className="small">
        <span className="mono muted">{e.sourceId}</span> <ExternalLink href={e.url}>{e.title || e.domain}</ExternalLink> <span className="muted">· {e.domain}</span>
      </span>
    </div>
  );
}

function Versions({ d, viewing, onView }: { d: InvestigationDetail; viewing: number | null; onView: (v: number | null) => void }) {
  if (d.versions.length === 0) return null;
  return (
    <Panel title="Versions" id="versions" tight>
      <ul className="i-list">
        {d.versions.map((v) => (
          <li key={v.version}>
            <div className="i-row">
              <span>
                <strong>v{v.version}</strong> <span className="small muted">{fmtDateTime(v.createdAt)}</span>
              </span>
              {(viewing ?? d.summary.version) === v.version ? (
                <Badge plain tone="accent">
                  Showing
                </Badge>
              ) : (
                <button type="button" className="i-btn i-btn--sm" onClick={() => onView(v.version === d.summary.version ? null : v.version)}>
                  View
                </button>
              )}
            </div>
            <div className="i-meta">
              <span>{v.claims} claim(s)</span>
              <span>{v.sources} source(s)</span>
              {v.changes && <span>{v.changes.summary}</span>}
            </div>
          </li>
        ))}
      </ul>
    </Panel>
  );
}
