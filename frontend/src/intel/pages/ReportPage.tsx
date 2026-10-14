import { useState } from "react";
import { navigate } from "../../router";
import { useIntel } from "../context";
import type { Report } from "../types";
import Link from "../../components/Link";
import { ASSESSMENT_LABEL, fmtDate, humanize, useAction, useLoad } from "../lib";
import { Dialog, ErrorBox, ExternalLink, Notice, PageHead, Skeleton } from "../ui";

export default function ReportPage({ id }: { id: string }) {
  const { api, href, can } = useIntel();
  const r = useLoad(() => api.report(id), id);
  const act = useAction();
  const [confirmDelete, setConfirmDelete] = useState(false);
  if (r.error) return <ErrorBox error={r.error} onRetry={r.reload} />;
  const rep = r.data;
  if (!rep) return <Skeleton lines={12} />;

  async function remove() {
    const done = await act.run("delete", async () => {
      await api.deleteReport(id);
      return true;
    });
    if (done) navigate(href("reports"));
  }

  return (
    <>
      <PageHead
        title="Report"
        crumbs={<Link href={href("reports")}>Reports</Link>}
        actions={
          <>
            <button
              type="button"
              className="i-btn i-btn--primary"
              disabled={act.busy === "pdf"}
              onClick={() => void act.run("pdf", () => api.downloadReportPdf(id, `verifact-report-${rep.generatedAt.slice(0, 10)}.pdf`))}
            >
              {act.busy === "pdf" ? "Preparing PDF…" : "Download PDF"}
            </button>
            <button type="button" className="i-btn" onClick={() => window.print()}>
              Print
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
      <ReportView rep={rep} />
      <Dialog
        open={confirmDelete}
        title="Delete this report?"
        onClose={() => setConfirmDelete(false)}
        footer={
          <>
            <button type="button" className="i-btn" onClick={() => setConfirmDelete(false)}>
              Cancel
            </button>
            <button type="button" className="i-btn i-btn--danger" onClick={() => void remove()}>
              Delete report
            </button>
          </>
        }
      >
        <p>The stored report is deleted. The underlying data isn't affected.</p>
      </Dialog>
    </>
  );
}

function counts(m: Record<string, number>) {
  return Object.entries(m)
    .map(([k, v]) => `${v} ${humanize(k).toLowerCase()}`)
    .join(", ");
}

function ReportView({ rep }: { rep: Report }) {
  return (
    <article className="i-report">
      <header>
        <h1 style={{ fontSize: 22 }}>{rep.title}</h1>
        <p className="small muted" style={{ marginTop: 4 }}>
          {rep.workspaceName} · {fmtDate(rep.periodStart)} – {fmtDate(rep.periodEnd)} · generated {fmtDate(rep.generatedAt)}
          {rep.generatedBy && ` by ${rep.generatedBy}`}
        </p>
      </header>
      <section>
        <h2>Executive summary</h2>
        {rep.executiveSummary.map((s) => (
          <p key={s}>{s}</p>
        ))}
      </section>
      <section>
        <h2>Recommended actions</h2>
        {rep.recommendedActions.length === 0 ? <p className="muted">No actions triggered for this period.</p> : <ul>{rep.recommendedActions.map((a) => <li key={a}>{a}</li>)}</ul>}
      </section>
      <section>
        <h2>Monitoring coverage</h2>
        <p>
          {rep.coverage.runs} runs ({rep.coverage.failedRuns} failed, {rep.coverage.partialRuns} partial).
        </p>
        {rep.coverage.projects.length > 0 && (
          <div className="i-table-wrap">
            <table className="i-table">
              <thead>
                <tr>
                  <th>Project</th>
                  <th>Sources</th>
                  <th className="right">Runs</th>
                  <th className="right">Failed</th>
                  <th>Last success</th>
                </tr>
              </thead>
              <tbody>
                {rep.coverage.projects.map((p) => (
                  <tr key={p.name}>
                    <td>
                      {p.name} <span className="muted small">({humanize(p.status)})</span>
                    </td>
                    <td className="small">{p.sources.join(", ")}</td>
                    <td className="right num">{p.runs}</td>
                    <td className="right num">{p.failedRuns}</td>
                    <td className="small">{fmtDate(p.lastSuccessAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      <section>
        <h2>Important new mentions</h2>
        <p>
          {rep.mentions.total} new mentions ({counts(rep.mentions.byRelevance)} relevance); {rep.mentions.withClaims} with a potentially damaging claim
          identified by AI.
        </p>
        {rep.importantMentions.length > 0 && (
          <ul>
            {rep.importantMentions.map((m) => (
              <li key={m.url}>
                <ExternalLink href={m.url}>{m.title || m.domain}</ExternalLink> <span className="muted small">({m.domain}{m.publishedDate ? `, ${m.publishedDate}` : ""})</span>
                {m.aiClaim && <div className="small">AI reading: {m.aiClaim}</div>}
              </li>
            ))}
          </ul>
        )}
      </section>
      <section>
        <h2>Incidents</h2>
        <p>
          {rep.incidents.opened} opened and {rep.incidents.resolved} resolved in the period; {rep.incidents.openNow} open now ({counts(rep.incidents.openByPriority)}).
        </p>
        {rep.openIncidents.length > 0 && (
          <ul>
            {rep.openIncidents.map((i) => (
              <li key={i.title + i.firstSeenAt}>
                <strong>{humanize(i.priority)}</strong> (score {i.riskScore}) · {i.title} — {humanize(i.status).toLowerCase()}, {i.owner ?? "unassigned"}, {i.mentions} mention(s)
              </li>
            ))}
          </ul>
        )}
      </section>
      <section>
        <h2>Investigation findings</h2>
        {rep.findings.length === 0 ? (
          <p className="muted">No investigations completed in this period.</p>
        ) : (
          <ul>
            {rep.findings.map((f, i) => (
              <li key={i}>
                <strong>{ASSESSMENT_LABEL[f.assessment] ?? f.assessment}:</strong> {f.claim}
                {f.excerpt && f.citationUrl && (
                  <div className="small muted">
                    “{f.excerpt}” — <ExternalLink href={f.citationUrl}>{f.citationDomain}</ExternalLink>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
      {rep.themes.length > 0 && (
        <section>
          <h2>Emerging themes</h2>
          <p className="small muted">Monitored keywords and topics most often matched by new mentions.</p>
          <ul>{rep.themes.map((t) => <li key={t.term}>{t.term}: {t.mentions} mention(s)</li>)}</ul>
        </section>
      )}
      {rep.evidenceChanges.length > 0 && (
        <section>
          <h2>Changes in available evidence</h2>
          <ul>{rep.evidenceChanges.map((c) => <li key={c.investigation + c.version}>{c.investigation} v{c.version} ({fmtDate(c.at)}): {c.summary}</li>)}</ul>
        </section>
      )}
      {rep.unresolved.length > 0 && (
        <section>
          <h2>Unresolved issues</h2>
          <ul>{rep.unresolved.map((u) => <li key={u}>{u}</li>)}</ul>
        </section>
      )}
      <section>
        <h2>Source coverage and limitations</h2>
        <ul>{rep.limitations.map((l) => <li key={l}>{l}</li>)}</ul>
      </section>
      <p className="small muted">{rep.disclaimer}</p>
    </article>
  );
}
