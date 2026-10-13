import { useState } from "react";
import type { FormEvent } from "react";
import { navigate } from "../../router";
import { useIntel } from "../context";
import Link from "../../components/Link";
import { ago, humanize, useAction, useLoad } from "../lib";
import { AssessmentBadge, Badge, Empty, Load, Notice, PageHead, Pager, Panel } from "../ui";

export default function InvestigationsPage() {
  const { api, orgId, href, workspace } = useIntel();
  const [page, setPage] = useState(0);
  const [showNew, setShowNew] = useState(() => new URLSearchParams(window.location.search).has("new"));
  const r = useLoad(() => api.investigations({ page, size: 25 }), `${orgId}|${page}`);
  return (
    <>
      <PageHead
        title="Investigations"
        sub="Evidence for and against a statement, from sources retrieved at the time. Re-run to see what changed."
        actions={
          !showNew && (
            <button type="button" className="i-btn i-btn--primary" onClick={() => setShowNew(true)}>
              New investigation
            </button>
          )
        }
      />
      <div className="i-stack">
        {showNew && (
          <Panel title="New investigation" id="new">
            {!workspace?.subscription.entitled && (
              <Notice tone="warn">This workspace needs a plan or trial to run investigations.</Notice>
            )}
            <NewInvestigation onCancel={() => setShowNew(false)} onStarted={(id) => navigate(href("investigations", id))} />
          </Panel>
        )}
        <Panel tight>
          <Load r={r}>
            {(p) =>
              p.items.length === 0 ? (
                <Empty title="No investigations yet">Paste a claim, a passage, or a link to an article to start.</Empty>
              ) : (
                <>
                  <div className="i-table-wrap">
                    <table className="i-table">
                      <thead>
                        <tr>
                          <th>Investigation</th>
                          <th>Main claim</th>
                          <th className="hide-sm">Version</th>
                          <th className="hide-sm">Updated</th>
                        </tr>
                      </thead>
                      <tbody>
                        {p.items.map((v) => (
                          <tr key={v.id}>
                            <td style={{ maxWidth: 460 }}>
                              <Link href={href("investigations", v.id)} className="primary">
                                {v.title}
                              </Link>
                              {v.incidentTitle && <div className="small muted clamp">Incident: {v.incidentTitle}</div>}
                            </td>
                            <td>
                              {v.status === "COMPLETED" && v.headline ? (
                                <AssessmentBadge a={v.headline} short />
                              ) : (
                                <Badge tone={v.status === "FAILED" ? "bad" : "info"}>{humanize(v.status)}</Badge>
                              )}
                            </td>
                            <td className="hide-sm num">{v.version || "—"}</td>
                            <td className="hide-sm small">{ago(v.updatedAt)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <Pager page={p.page} size={p.size} total={p.total} onPage={setPage} />
                </>
              )
            }
          </Load>
        </Panel>
      </div>
    </>
  );
}

function NewInvestigation({ onCancel, onStarted }: { onCancel: () => void; onStarted: (id: string) => void }) {
  const { api } = useIntel();
  const [input, setInput] = useState("");
  const [title, setTitle] = useState("");
  const act = useAction();
  async function submit(e: FormEvent) {
    e.preventDefault();
    const d = await act.run("start", () => api.startInvestigation({ input, title: title || undefined }));
    if (d) onStarted(d.summary.id);
  }
  return (
    <form onSubmit={submit} className="i-stack-sm">
      <label className="i-field">
        <span>Claim, passage or link *</span>
        <textarea className="i-input" value={input} onChange={(e) => setInput(e.target.value)} maxLength={10000} required placeholder="e.g. “Acme Foods is recalling all peanut butter over salmonella” or https://…" />
        <small>
          Text is sent to our AI and search providers to find and assess sources (see Settings → Data processing). A link is fetched from its
          public page.
        </small>
      </label>
      <label className="i-field">
        <span>Title</span>
        <input className="i-input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} placeholder="Defaults to the start of the text" />
      </label>
      {act.error && <Notice tone="bad">{act.error}</Notice>}
      <div className="i-actions">
        <button type="submit" className="i-btn i-btn--primary" disabled={!input.trim() || act.busy !== null}>
          {act.busy ? "Starting…" : "Start investigation"}
        </button>
        <button type="button" className="i-btn" onClick={onCancel}>
          Cancel
        </button>
        <span className="small muted">Takes about 20–60 seconds; you can leave the page.</span>
      </div>
    </form>
  );
}
