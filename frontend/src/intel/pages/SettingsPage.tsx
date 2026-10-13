import { useState } from "react";
import type { FormEvent } from "react";
import { useAuth } from "../../auth/useAuth";
import { useIntel } from "../context";
import { fmtDateTime, humanize, useAction, useLoad, useToast } from "../lib";
import { Badge, ErrorBox, Load, Notice, PageHead, Panel } from "../ui";

const SOURCE_LABEL: Record<string, string> = {
  WEB_SEARCH: "Web search",
  NEWS_SEARCH: "News search",
  RSS: "RSS / Atom feeds",
  PAGE_WATCH: "Watched public pages",
};

export default function SettingsPage() {
  const { api, orgId, workspace, reloadWorkspace, can } = useIntel();
  const { account, reloadAccount } = useAuth();
  const caps = useLoad(() => api.capabilities(), orgId);
  const audit = useLoad(() => (can("ADMIN") ? api.audit() : Promise.resolve([])), `${orgId}|${can("ADMIN")}`);
  const [name, setName] = useState<string | null>(null);
  const [email, setEmail] = useState("");
  const [role, setRole] = useState("MEMBER");
  const act = useAction();
  const toast = useToast();

  if (!workspace) return null;

  async function rename(e: FormEvent) {
    e.preventDefault();
    if (name === null) return;
    const ws = await act.run("rename", () => api.rename(name));
    if (ws) {
      setName(null);
      reloadWorkspace();
      void reloadAccount();
      toast.show("Workspace renamed.");
    }
  }

  async function add(e: FormEvent) {
    e.preventDefault();
    const m = await act.run("add", () => api.addMember(email, role));
    if (m) {
      setEmail("");
      reloadWorkspace();
      toast.show(`${m.email} added.`);
    }
  }

  async function change(userId: string, newRole: string) {
    if (await act.run(userId, () => api.changeRole(userId, newRole))) reloadWorkspace();
  }

  async function remove(userId: string) {
    const done = await act.run(userId, async () => {
      await api.removeMember(userId);
      return true;
    });
    if (done) {
      if (userId === account?.id) {
        void reloadAccount();
        window.location.assign("/app");
      } else reloadWorkspace();
    }
  }

  return (
    <>
      <PageHead title="Settings & integrations" sub="Workspace, people, connected services and how data is processed." />
      {act.error && <Notice tone="bad">{act.error}</Notice>}
      <div className="i-grid i-grid-2" style={{ marginTop: 12 }}>
        <Panel title="Workspace" id="ws">
          <form onSubmit={rename} className="i-stack-sm">
            <label className="i-field">
              <span>Name</span>
              <input
                className="i-input"
                value={name ?? (workspace.personal ? "Personal workspace" : workspace.name)}
                onChange={(e) => setName(e.target.value)}
                disabled={!can("ADMIN") || workspace.personal}
                maxLength={120}
              />
            </label>
            {workspace.personal && <p className="small muted">A personal workspace is just for you. Create a team workspace to collaborate.</p>}
            {can("ADMIN") && !workspace.personal && (
              <span>
                <button type="submit" className="i-btn i-btn--sm" disabled={name === null || act.busy === "rename"}>
                  Save name
                </button>
              </span>
            )}
            <p className="small muted">Your role: {humanize(workspace.yourRole)}</p>
          </form>
        </Panel>

        <Panel title={`People (${workspace.members.length} of ${workspace.seats} seat${workspace.seats === 1 ? "" : "s"})`} id="people" tight>
          <ul className="i-list">
            {workspace.members.map((m) => (
              <li key={m.userId} className="i-row">
                <span>
                  {m.displayName ?? m.email}
                  {m.userId === account?.id && <span className="muted small"> (you)</span>}
                  <div className="small muted">{m.email}</div>
                </span>
                <span className="i-actions">
                  {can("OWNER") && m.userId !== account?.id ? (
                    <select className="i-input" style={{ width: 120 }} value={m.role} onChange={(e) => void change(m.userId, e.target.value)} aria-label={`Role of ${m.email}`}>
                      <option value="OWNER">Owner</option>
                      <option value="ADMIN">Admin</option>
                      <option value="MEMBER">Member</option>
                    </select>
                  ) : (
                    <Badge plain>{humanize(m.role)}</Badge>
                  )}
                  {!workspace.personal && (m.userId === account?.id || can("ADMIN")) && (
                    <button type="button" className="i-btn i-btn--sm i-btn--ghost" onClick={() => void remove(m.userId)} disabled={act.busy === m.userId}>
                      {m.userId === account?.id ? "Leave" : "Remove"}
                    </button>
                  )}
                </span>
              </li>
            ))}
          </ul>
          {can("ADMIN") && !workspace.personal && (
            <form onSubmit={add} className="i-stack-sm" style={{ padding: 16, borderTop: "1px solid var(--i-line)" }}>
              <div className="i-form-grid">
                <label className="i-field">
                  <span>Add by email</span>
                  <input className="i-input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
                </label>
                <label className="i-field">
                  <span>Role</span>
                  <select className="i-input" value={role} onChange={(e) => setRole(e.target.value)}>
                    <option value="MEMBER">Member: work on incidents, investigations, drafts</option>
                    <option value="ADMIN">Admin: also manage projects, people, approvals</option>
                    {can("OWNER") && <option value="OWNER">Owner: also billing</option>}
                  </select>
                </label>
              </div>
              <span>
                <button type="submit" className="i-btn i-btn--sm i-btn--primary" disabled={!email.trim() || act.busy === "add"}>
                  Add member
                </button>
              </span>
              <p className="small muted">They need a VeriFact account with that email first. Invitation emails aren't sent yet.</p>
            </form>
          )}
        </Panel>

        <Panel title="Integrations" id="integrations">
          <Load r={caps}>
            {(c) => (
              <div className="i-stack-sm small">
                {Object.entries(c.sources).map(([k, v]) => (
                  <div key={k} className="i-row">
                    <span>{SOURCE_LABEL[k] ?? humanize(k)}</span>
                    <Badge tone={v ? "ok" : "warn"}>{v ? "Available" : "Not configured"}</Badge>
                  </div>
                ))}
                <div className="i-row">
                  <span>Scheduled monitoring</span>
                  <Badge tone={c.scheduler ? "ok" : "warn"}>{c.scheduler ? "On" : "Off"}</Badge>
                </div>
                <div className="i-row">
                  <span>External scheduler (wakes a sleeping server)</span>
                  <Badge tone={c.externalCron ? "ok" : "neutral"}>{c.externalCron ? "Configured" : "Not configured"}</Badge>
                </div>
                <div className="i-row">
                  <span>Online payment ({humanize(c.billingProvider)})</span>
                  <Badge tone={c.onlinePayment ? "ok" : "neutral"}>{c.onlinePayment ? "Available" : "Not set up"}</Badge>
                </div>
                <div className="i-row">
                  <span>Email notifications and scheduled report emails</span>
                  <Badge tone="neutral">Not available yet</Badge>
                </div>
                <div className="i-row">
                  <span>Social media (X, Reddit, TikTok, Facebook…)</span>
                  <Badge tone="neutral">Not supported</Badge>
                </div>
                <p className="muted">New mentions and incidents show on the Overview; there are no push or email alerts yet.</p>
              </div>
            )}
          </Load>
        </Panel>

        <Panel title="Data processing and retention" id="data">
          <div className="i-stack-sm small">
            <p>
              <strong>External processing.</strong> Text you investigate, and mentions found by monitoring, are sent to our AI provider (OpenAI) to
              extract and assess claims, and search queries go to our search provider (Tavily). Don't submit confidential documents you aren't
              allowed to share with these processors.
            </p>
            <p>
              <strong>Fetching.</strong> Links, feeds and watched pages are fetched from their public addresses only; private network addresses are
              blocked and login-protected pages aren't accessed.
            </p>
            <p>
              <strong>Isolation.</strong> Each workspace's data is visible only to its members. Investigations always search afresh: evidence is
              never reused across workspaces.
            </p>
            <p>
              <strong>Retention and deletion.</strong> Workspace data is kept until you delete it. Deleting a project removes its mentions and runs;
              deleting an investigation removes its versions and evidence. Account deletion removes your personal workspace.
            </p>
          </div>
        </Panel>
      </div>

      {can("ADMIN") && (
        <div style={{ marginTop: 16 }}>
          <Panel title="Audit log" id="audit" tight>
            {audit.error ? (
              <ErrorBox error={audit.error} onRetry={audit.reload} />
            ) : (
              <div className="i-table-wrap">
                <table className="i-table">
                  <thead>
                    <tr>
                      <th>When</th>
                      <th>Who</th>
                      <th>Action</th>
                      <th className="hide-sm">Detail</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(audit.data ?? []).map((a) => (
                      <tr key={a.id}>
                        <td className="small">{fmtDateTime(a.createdAt)}</td>
                        <td className="small">{a.actorEmail ?? "System"}</td>
                        <td className="small mono">{a.action}</td>
                        <td className="hide-sm small">{a.detail}</td>
                      </tr>
                    ))}
                    {audit.data?.length === 0 && (
                      <tr>
                        <td colSpan={4} className="small muted">
                          No recorded actions yet.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
        </div>
      )}
      {toast.node}
    </>
  );
}
