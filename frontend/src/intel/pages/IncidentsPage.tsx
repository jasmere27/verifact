import { useState } from "react";
import type { FormEvent } from "react";
import { navigate } from "../../router";
import { useIntel } from "../context";
import Link from "../../components/Link";
import { ago, humanize, useAction, useLoad } from "../lib";
import { Dialog, Empty, IncidentStatusBadge, Load, Notice, PageHead, Pager, Panel, PriorityBadge } from "../ui";

const STATUSES = ["NEW", "NEEDS_REVIEW", "INVESTIGATING", "AWAITING_EVIDENCE", "RESPONSE_DRAFTED", "MONITORING", "RESOLVED"];

export default function IncidentsPage() {
  const { api, orgId, href, workspace } = useIntel();
  const initial = new URLSearchParams(window.location.search);
  const [status, setStatus] = useState(initial.get("status") ?? "OPEN");
  const [priority, setPriority] = useState("");
  const [projectId, setProjectId] = useState("");
  const [ownerId, setOwnerId] = useState("");
  const [q, setQ] = useState("");
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState("priority");
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const projects = useLoad(() => api.projects(), orgId);
  const key = [status, priority, projectId, ownerId, query, sort, page].join("|");
  const r = useLoad(() => api.incidents({ status, priority, projectId, ownerId, q: query, sort, page, size: 25 }), key);
  const reset = () => setPage(0);

  return (
    <>
      <PageHead
        title="Incidents"
        sub="Potentially damaging claims handled as cases: review, assign, investigate, respond, resolve."
        actions={
          <button type="button" className="i-btn i-btn--primary" onClick={() => setCreating(true)}>
            New incident
          </button>
        }
      />
      <Panel tight>
        <form
          className="i-filters"
          role="search"
          onSubmit={(e) => {
            e.preventDefault();
            setQuery(q);
            reset();
          }}
        >
          <label className="i-field grow">
            <span>Search title or claim</span>
            <input className="i-input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="e.g. recall" />
          </label>
          <label className="i-field">
            <span>Status</span>
            <select className="i-input" value={status} onChange={(e) => { setStatus(e.target.value); reset(); }}>
              <option value="OPEN">All open</option>
              <option value="">All</option>
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {humanize(s)}
                </option>
              ))}
            </select>
          </label>
          <label className="i-field">
            <span>Priority</span>
            <select className="i-input" value={priority} onChange={(e) => { setPriority(e.target.value); reset(); }}>
              <option value="">Any</option>
              {["CRITICAL", "HIGH", "MEDIUM", "LOW"].map((p) => (
                <option key={p} value={p}>
                  {humanize(p)}
                </option>
              ))}
            </select>
          </label>
          <label className="i-field">
            <span>Project</span>
            <select className="i-input" value={projectId} onChange={(e) => { setProjectId(e.target.value); reset(); }}>
              <option value="">All</option>
              {projects.data?.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
          <label className="i-field">
            <span>Owner</span>
            <select className="i-input" value={ownerId} onChange={(e) => { setOwnerId(e.target.value); reset(); }}>
              <option value="">Anyone</option>
              <option value="none">Unassigned</option>
              {workspace?.members.map((m) => (
                <option key={m.userId} value={m.userId}>
                  {m.displayName ?? m.email}
                </option>
              ))}
            </select>
          </label>
          <label className="i-field">
            <span>Sort</span>
            <select className="i-input" value={sort} onChange={(e) => { setSort(e.target.value); reset(); }}>
              <option value="priority">Priority</option>
              <option value="risk">Risk score</option>
              <option value="updated">Recently updated</option>
              <option value="firstSeen">First seen</option>
            </select>
          </label>
          <button type="submit" className="i-btn">
            Search
          </button>
        </form>
        <Load r={r}>
          {(p) =>
            p.items.length === 0 ? (
              <Empty title="No incidents match">
                {status === "OPEN" && !query ? "Nothing open right now. Monitoring opens incidents for serious claims; you can also open one by hand." : "Try other filters."}
              </Empty>
            ) : (
              <>
                <div className="i-table-wrap">
                  <table className="i-table">
                    <thead>
                      <tr>
                        <th>Incident</th>
                        <th>Priority</th>
                        <th>Status</th>
                        <th className="hide-sm">Owner</th>
                        <th className="hide-sm right">Mentions</th>
                        <th className="hide-sm">Last seen</th>
                      </tr>
                    </thead>
                    <tbody>
                      {p.items.map((i) => (
                        <tr key={i.id}>
                          <td style={{ maxWidth: 460 }}>
                            <Link href={href("incidents", i.id)} className="primary">
                              {i.title}
                            </Link>
                            <div className="small muted clamp">{i.projectName ?? "No project"}</div>
                          </td>
                          <td>
                            <PriorityBadge p={i.priority} />
                            <div className="small muted num">score {i.riskScore}</div>
                          </td>
                          <td>
                            <IncidentStatusBadge s={i.status} />
                          </td>
                          <td className="hide-sm small">{i.ownerEmail ?? <span className="muted">Unassigned</span>}</td>
                          <td className="hide-sm right num">{i.mentionCount}</td>
                          <td className="hide-sm small">{ago(i.lastSeenAt)}</td>
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
      <NewIncident open={creating} onClose={() => setCreating(false)} onCreated={(id) => navigate(href("incidents", id))} />
    </>
  );
}

function NewIncident({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (id: string) => void }) {
  const { api, orgId } = useIntel();
  const projects = useLoad(() => api.projects(), orgId);
  const [claim, setClaim] = useState("");
  const [title, setTitle] = useState("");
  const [url, setUrl] = useState("");
  const [severity, setSeverity] = useState("");
  const [projectId, setProjectId] = useState("");
  const act = useAction();
  async function submit(e: FormEvent) {
    e.preventDefault();
    const d = await act.run("create", () =>
      api.createIncident({ claim, title: title || undefined, discoveryUrl: url || undefined, severity: severity || undefined, projectId: projectId || undefined }),
    );
    if (d) onCreated(d.summary.id);
  }
  return (
    <Dialog open={open} title="New incident" onClose={onClose}>
      <form onSubmit={submit} className="i-stack-sm">
        <label className="i-field">
          <span>The claim or allegation *</span>
          <textarea className="i-input" value={claim} onChange={(e) => setClaim(e.target.value)} maxLength={1000} required />
          <small>Describe what is being said, not whether it's true.</small>
        </label>
        <label className="i-field">
          <span>Short title</span>
          <input className="i-input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} />
        </label>
        <label className="i-field">
          <span>Where it was seen (link)</span>
          <input className="i-input" type="url" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://" />
        </label>
        <div className="i-form-grid">
          <label className="i-field">
            <span>Severity (your judgement)</span>
            <select className="i-input" value={severity} onChange={(e) => setSeverity(e.target.value)}>
              <option value="">Not assessed</option>
              <option value="HIGH">High</option>
              <option value="MEDIUM">Medium</option>
              <option value="LOW">Low</option>
            </select>
          </label>
          <label className="i-field">
            <span>Project</span>
            <select className="i-input" value={projectId} onChange={(e) => setProjectId(e.target.value)}>
              <option value="">None</option>
              {projects.data?.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
        </div>
        {act.error && <Notice tone="bad">{act.error}</Notice>}
        <div className="i-actions">
          <button type="submit" className="i-btn i-btn--primary" disabled={act.busy !== null || !claim.trim()}>
            Open incident
          </button>
          <button type="button" className="i-btn" onClick={onClose}>
            Cancel
          </button>
        </div>
      </form>
    </Dialog>
  );
}
