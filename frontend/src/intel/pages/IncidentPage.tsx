import { useState } from "react";
import { navigate } from "../../router";
import { useIntel } from "../context";
import type { IncidentDetail } from "../types";
import Link from "../../components/Link";
import { ago, fmtDateTime, hostOf, humanize, useAction, useLoad, useToast } from "../lib";
import { AiLabel, Badge, Dialog, Empty, ErrorBox, ExternalLink, IncidentStatusBadge, Meter, Notice, PageHead, Panel, PriorityBadge, Skeleton } from "../ui";

const STATUSES = ["NEW", "NEEDS_REVIEW", "INVESTIGATING", "AWAITING_EVIDENCE", "RESPONSE_DRAFTED", "MONITORING", "RESOLVED"];
const EVENT_LABEL: Record<string, string> = {
  OPENED: "",
  STATUS: "Status",
  ASSIGNED: "Owner",
  PRIORITY: "Priority",
  NOTE: "Note",
  RENAMED: "Renamed",
  MENTION_LINKED: "",
  INVESTIGATION: "Investigation",
  RESPONSE: "Response",
};

export default function IncidentPage({ id }: { id: string }) {
  const { api, href, can, workspace } = useIntel();
  const r = useLoad(() => api.incident(id), id);
  const act = useAction();
  const toast = useToast();
  const [note, setNote] = useState("");
  const [steps, setSteps] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

  if (r.error) return <ErrorBox error={r.error} onRetry={r.reload} />;
  const d = r.data;
  if (!d) return <Skeleton lines={10} />;
  const s = d.summary;

  async function update(changes: Parameters<typeof api.updateIncident>[1], done?: string) {
    const updated = await act.run("update", () => api.updateIncident(id, changes));
    if (updated) {
      r.setData(updated);
      if (done) toast.show(done);
    }
  }

  async function investigate(input: string) {
    const inv = await act.run("investigate", () => api.startInvestigation({ title: s.title, input, incidentId: id }));
    if (inv) navigate(href("investigations", inv.summary.id));
  }

  async function addNote() {
    const updated = await act.run("note", () => api.addNote(id, note));
    if (updated) {
      r.setData(updated);
      setNote("");
    }
  }

  async function remove() {
    const done = await act.run("delete", async () => {
      await api.deleteIncident(id);
      return true;
    });
    if (done) navigate(href("incidents"));
  }

  const latestInvestigation = d.investigations.find((i) => i.status === "COMPLETED");
  return (
    <>
      <PageHead
        title={s.title}
        crumbs={<Link href={href("incidents")}>Incidents</Link>}
        sub={
          <>
            <PriorityBadge p={s.priority} /> <IncidentStatusBadge s={s.status} /> · first seen {fmtDateTime(s.firstSeenAt)} · last seen {ago(s.lastSeenAt)}
            {s.projectName && <> · {s.projectName}</>}
          </>
        }
        actions={
          <>
            {d.investigations.length === 0 ? (
              <button type="button" className="i-btn i-btn--primary" disabled={act.busy === "investigate"} onClick={() => void investigate(s.claim)}>
                {act.busy === "investigate" ? "Starting…" : "Investigate claim"}
              </button>
            ) : (
              <Link href={href("investigations", d.investigations[0].id)} className="i-btn i-btn--primary">
                Open investigation
              </Link>
            )}
            {latestInvestigation && (
              <Link href={`${href("responses")}?investigation=${latestInvestigation.id}&incident=${id}`} className="i-btn">
                Draft response
              </Link>
            )}
            {can("ADMIN") && (
              <button type="button" className="i-btn i-btn--danger" onClick={() => setConfirmDelete(true)}>
                Delete
              </button>
            )}
          </>
        }
      />
      {act.error && <Notice tone="bad">{act.error}</Notice>}
      <div className="i-split" style={{ marginTop: 12 }}>
        <div className="i-stack">
          <Panel title="The claim" id="claim">
            <div className="i-stack-sm">
              <span className="i-kind">Allegation as reported — not verified</span>
              <p style={{ fontSize: 15 }}>{s.claim}</p>
              {d.discoveryUrl && (
                <p className="small">
                  Discovered at <ExternalLink href={d.discoveryUrl}>{hostOf(d.discoveryUrl)}</ExternalLink>
                  {d.investigations.length === 0 && (
                    <>
                      {" · "}
                      <button type="button" className="i-btn i-btn--sm" onClick={() => void investigate(d.discoveryUrl!)} disabled={act.busy === "investigate"}>
                        Investigate the source article instead
                      </button>
                    </>
                  )}
                </p>
              )}
            </div>
          </Panel>

          <Panel title="Recommended next steps" id="next">
            <div className="i-stack-sm">
              {d.suggestedSteps.length > 0 ? (
                <ul style={{ margin: 0, paddingLeft: 18 }} className="small">
                  {d.suggestedSteps.map((t) => (
                    <li key={t}>{t}</li>
                  ))}
                </ul>
              ) : (
                <p className="small muted">No suggestions for a resolved incident.</p>
              )}
              <p className="small muted">Suggestions follow fixed rules from the incident's state.</p>
              <label className="i-field">
                <span>Team plan</span>
                <textarea
                  className="i-input"
                  value={steps ?? d.nextSteps ?? ""}
                  onChange={(e) => setSteps(e.target.value)}
                  placeholder="Who does what, by when"
                  maxLength={2000}
                />
              </label>
              {steps !== null && steps !== (d.nextSteps ?? "") && (
                <span>
                  <button type="button" className="i-btn i-btn--sm" onClick={() => void update({ nextSteps: steps }, "Plan saved.").then(() => setSteps(null))}>
                    Save plan
                  </button>
                </span>
              )}
            </div>
          </Panel>

          <Panel title={`Linked mentions (${d.mentions.length})`} id="mentions" tight>
            {d.mentions.length === 0 ? (
              <Empty title="No linked mentions">Mentions link here when monitoring sees this claim again.</Empty>
            ) : (
              <ul className="i-list">
                {d.mentions.map((m) => (
                  <li key={m.id}>
                    <ExternalLink href={m.url}>{m.title || m.domain}</ExternalLink>
                    <div className="i-meta">
                      <span>{m.domain}</span>
                      <span>{humanize(m.connector)}</span>
                      {m.publishedDate && <span>published {m.publishedDate}</span>}
                      <span>found {ago(m.firstSeenAt)}</span>
                    </div>
                    {m.claimQuote && <p className="small" style={{ marginTop: 4 }}>“{m.claimQuote}”</p>}
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel title="Activity" id="activity">
            <ol className="i-timeline">
              {d.events.map((e) => (
                <li key={e.id}>
                  <time dateTime={e.createdAt}>{fmtDateTime(e.createdAt)}</time>
                  {(EVENT_LABEL[e.kind] ?? humanize(e.kind)) && <strong>{EVENT_LABEL[e.kind] ?? humanize(e.kind)}: </strong>}
                  {e.detail}
                  <span className="muted small"> — {e.actorEmail ?? (e.actorId ? "a former member" : "VeriFact")}</span>
                </li>
              ))}
            </ol>
            <div className="i-stack-sm" style={{ marginTop: 14 }}>
              <label className="i-field">
                <span>Add a note</span>
                <textarea className="i-input" value={note} onChange={(e) => setNote(e.target.value)} maxLength={1000} style={{ minHeight: 70 }} />
              </label>
              <span>
                <button type="button" className="i-btn i-btn--sm" disabled={!note.trim() || act.busy === "note"} onClick={() => void addNote()}>
                  Add note
                </button>
              </span>
            </div>
          </Panel>
        </div>

        <div className="i-stack">
          <Panel title="Handling" id="handling">
            <div className="i-stack-sm">
              <label className="i-field">
                <span>Status</span>
                <select className="i-input" value={s.status} onChange={(e) => void update({ status: e.target.value }, "Status updated.")} disabled={act.busy === "update"}>
                  {STATUSES.map((x) => (
                    <option key={x} value={x}>
                      {humanize(x)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="i-field">
                <span>Owner</span>
                <select className="i-input" value={s.ownerId ?? ""} onChange={(e) => void update({ ownerId: e.target.value }, "Owner updated.")} disabled={act.busy === "update"}>
                  <option value="">Unassigned</option>
                  {workspace?.members.map((m) => (
                    <option key={m.userId} value={m.userId}>
                      {m.displayName ?? m.email}
                    </option>
                  ))}
                </select>
              </label>
              <label className="i-field">
                <span>Priority</span>
                <select
                  className="i-input"
                  value={d.priorityOverride ? s.priority : "AUTO"}
                  onChange={(e) => void update({ priority: e.target.value }, "Priority updated.")}
                  disabled={!can("ADMIN") || act.busy === "update"}
                >
                  <option value="AUTO">Computed ({humanize(d.risk.priority)})</option>
                  {["CRITICAL", "HIGH", "MEDIUM", "LOW"].map((p) => (
                    <option key={p} value={p}>
                      Override: {humanize(p)}
                    </option>
                  ))}
                </select>
                {!can("ADMIN") && <small>Admins and owners can override the computed priority.</small>}
              </label>
            </div>
          </Panel>

          <RiskPanel d={d} />

          <Panel title="Investigations" id="investigations" tight>
            {d.investigations.length === 0 ? (
              <Empty title="Not investigated yet" />
            ) : (
              <ul className="i-list">
                {d.investigations.map((v) => (
                  <li key={v.id} className="i-row">
                    <Link href={href("investigations", v.id)}>{v.title}</Link>
                    <Badge tone={v.status === "COMPLETED" ? "ok" : v.status === "FAILED" ? "bad" : "info"}>
                      {humanize(v.status)} {v.version > 0 && `v${v.version}`}
                    </Badge>
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          {d.related.length > 0 && (
            <Panel title="Related incidents" id="related" tight>
              <ul className="i-list">
                {d.related.map((o) => (
                  <li key={o.id}>
                    <Link href={href("incidents", o.id)}>{o.title}</Link>
                    <div className="i-meta">
                      <PriorityBadge p={o.priority} /> <IncidentStatusBadge s={o.status} />
                    </div>
                  </li>
                ))}
              </ul>
            </Panel>
          )}
        </div>
      </div>
      <Dialog
        open={confirmDelete}
        title="Delete this incident?"
        onClose={() => setConfirmDelete(false)}
        footer={
          <>
            <button type="button" className="i-btn" onClick={() => setConfirmDelete(false)}>
              Cancel
            </button>
            <button type="button" className="i-btn i-btn--danger" onClick={() => void remove()}>
              Delete incident
            </button>
          </>
        }
      >
        <p>The incident and its activity history are deleted. Linked mentions return to the review list; investigations are kept.</p>
      </Dialog>
      {toast.node}
    </>
  );
}

function RiskPanel({ d }: { d: IncidentDetail }) {
  return (
    <Panel title={`Risk assessment: ${d.risk.score}/100`} id="risk">
      <div className="i-stack-sm">
        <p className="small muted">{d.risk.method}</p>
        {d.risk.inputs.severity && (
          <p className="small">
            Severity input: <strong>{humanize(d.risk.inputs.severity)}</strong> <AiLabel what="AI assessment" />
          </p>
        )}
        <div>
          {d.risk.factors.map((f) => (
            <div key={f.name} className="i-factor">
              <span className="small">
                <strong>{f.name}</strong>
                <span className="muted"> — {f.reason}</span>
              </span>
              <span className="small num right" style={{ textAlign: "right" }}>
                {f.points}/{f.max}
              </span>
              <Meter value={f.points} max={f.max} label={f.name} quota={false} />
            </div>
          ))}
        </div>
        {d.priorityOverride && <Notice tone="info">A reviewer set the priority; the score above is kept for reference.</Notice>}
      </div>
    </Panel>
  );
}
