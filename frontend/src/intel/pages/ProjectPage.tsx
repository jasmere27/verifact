import { useState } from "react";
import { navigate } from "../../router";
import { useIntel } from "../context";
import Link from "../../components/Link";
import { ago, fmtDateTime, humanize, useAction, useLoad, usePoll, useToast } from "../lib";
import { Dialog, ErrorBox, HealthBadge, Notice, PageHead, Panel, RunBadge, Skeleton } from "../ui";
import MentionsTable from "./MentionsTable";
import ProjectForm from "./ProjectForm";

export default function ProjectPage({ id }: { id: string }) {
  const { href } = useIntel();
  if (id === "new") {
    return (
      <>
        <PageHead title="New monitoring project" crumbs={<Link href={href("monitoring")}>Monitoring</Link>} sub="Configure a brand to watch. You can change this later." />
        <ProjectForm onSaved={(p) => navigate(href("monitoring", p.id))} onCancel={() => navigate(href("monitoring"))} />
      </>
    );
  }
  return <ProjectDetail id={id} />;
}

function ProjectDetail({ id }: { id: string }) {
  const { api, href, can } = useIntel();
  const project = useLoad(() => api.project(id), id);
  const runs = useLoad(() => api.runs(id, 20), id);
  const [editing, setEditing] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const act = useAction();
  const toast = useToast();
  const running = Boolean(runs.data?.some((r) => r.status === "RUNNING"));
  usePoll(running, runs.reload, 2500);
  usePoll(running, project.reload, 5000);

  if (project.error) return <ErrorBox error={project.error} onRetry={project.reload} />;
  const p = project.data;
  if (!p) return <Skeleton lines={8} />;

  if (editing) {
    return (
      <>
        <PageHead title={`Edit ${p.name}`} crumbs={<Link href={href("monitoring")}>Monitoring</Link>} />
        <ProjectForm
          project={p}
          onSaved={(saved) => {
            project.setData(saved);
            setEditing(false);
            toast.show("Project saved.");
          }}
          onCancel={() => setEditing(false)}
        />
      </>
    );
  }

  async function runNow() {
    const run = await act.run("run", () => api.runNow(id));
    if (run) {
      toast.show("Run started. Results appear here as soon as it finishes.");
      runs.reload();
    }
  }

  async function toggle() {
    const updated = await act.run("status", () => api.setProjectStatus(id, p!.status === "ACTIVE" ? "PAUSED" : "ACTIVE"));
    if (updated) project.setData(updated);
  }

  async function remove() {
    const done = await act.run("delete", async () => {
      await api.deleteProject(id);
      return true;
    });
    if (done) navigate(href("monitoring"));
  }

  const cfg = p.config;
  return (
    <>
      <PageHead
        title={p.name}
        crumbs={<Link href={href("monitoring")}>Monitoring</Link>}
        sub={
          <>
            {p.brand} · <HealthBadge h={p.health} /> · every {p.checkIntervalHours} h · {p.status === "ACTIVE" ? `next run ${ago(p.nextRunAt)}` : "paused"}
          </>
        }
        actions={
          <>
            <button type="button" className="i-btn i-btn--primary" onClick={() => void runNow()} disabled={act.busy === "run" || running}>
              {running ? "Running…" : "Run check now"}
            </button>
            {can("ADMIN") && (
              <>
                <button type="button" className="i-btn" onClick={() => setEditing(true)}>
                  Edit
                </button>
                <button type="button" className="i-btn" onClick={() => void toggle()} disabled={act.busy === "status"}>
                  {p.status === "ACTIVE" ? "Pause" : "Resume"}
                </button>
                <button type="button" className="i-btn i-btn--danger" onClick={() => setConfirmDelete(true)}>
                  Delete
                </button>
              </>
            )}
          </>
        }
      />
      {act.error && <Notice tone="bad">{act.error}</Notice>}
      <div className="i-split" style={{ marginTop: act.error ? 12 : 0 }}>
        <div className="i-stack">
          <Panel title="Run history" id="runs" tight>
            {runs.error ? (
              <ErrorBox error={runs.error} onRetry={runs.reload} />
            ) : !runs.data ? (
              <Skeleton />
            ) : runs.data.length === 0 ? (
              <p className="muted small" style={{ padding: 16 }}>
                No runs yet. The first scheduled run starts within a minute or two of creating the project while the service is up, or run a
                check now.
              </p>
            ) : (
              <div className="i-table-wrap">
                <table className="i-table">
                  <thead>
                    <tr>
                      <th>Started</th>
                      <th>Status</th>
                      <th className="hide-sm">Trigger</th>
                      <th className="right">Retrieved</th>
                      <th className="right">New</th>
                      <th>Sources</th>
                    </tr>
                  </thead>
                  <tbody>
                    {runs.data.map((r) => (
                      <tr key={r.id}>
                        <td className="small">{fmtDateTime(r.startedAt)}</td>
                        <td>
                          <RunBadge s={r.status} />
                        </td>
                        <td className="hide-sm small">
                          {humanize(r.trigger)}
                          {r.attempt > 1 ? ` #${r.attempt}` : ""}
                        </td>
                        <td className="right num">{r.resultsFound}</td>
                        <td className="right num">
                          {r.newMentions}
                          {r.newIncidents > 0 && <div className="small muted">{r.newIncidents} incident(s)</div>}
                        </td>
                        <td className="small">
                          {r.sources.map((s) => (
                            <div key={s.connector}>
                              {humanize(s.connector)}: {humanize(s.status)} ({s.relevant}/{s.items})
                              {s.detail && <div className="muted">{s.detail}</div>}
                            </div>
                          ))}
                          {r.errorSummary && <div className="muted">{r.errorSummary}</div>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
          <Panel title="Mentions" id="project-mentions" tight>
            <MentionsTable projectId={id} refreshKey={String(runs.data?.[0]?.finishedAt ?? "")} />
          </Panel>
        </div>
        <Panel title="Configuration" id="config">
          <dl className="i-stack-sm small" style={{ margin: 0 }}>
            <Def label="Aliases" items={cfg.aliases} />
            <Def label="Products" items={cfg.products} />
            <Def label="People" items={cfg.executives} />
            <Def label="Keywords" items={cfg.keywords} />
            <Def label="Topics" items={cfg.topics} />
            <Def label="Excluded" items={cfg.excludedTerms} />
            <Def
              label="Sources"
              items={[
                ...(cfg.sources.webSearch ? ["Web search"] : []),
                ...(cfg.sources.newsSearch ? ["News search"] : []),
                ...cfg.sources.rssFeeds.map((f) => `RSS: ${f}`),
                ...cfg.sources.pages.map((f) => `Page: ${f}`),
              ]}
            />
            <Def label="Look-back" items={[`${cfg.lookbackDays} day(s)`]} />
            <Def label="Incidents open for" items={[cfg.alertThreshold === "NONE" ? "never (manual review)" : `${humanize(cfg.alertThreshold)}${cfg.alertThreshold === "MEDIUM" ? " and high" : ""} severity claims`]} />
            <Def label="Last successful run" items={[p.lastSuccessAt ? fmtDateTime(p.lastSuccessAt) : "none yet"]} />
          </dl>
        </Panel>
      </div>
      <Dialog
        open={confirmDelete}
        title="Delete this project?"
        onClose={() => setConfirmDelete(false)}
        footer={
          <>
            <button type="button" className="i-btn" onClick={() => setConfirmDelete(false)}>
              Cancel
            </button>
            <button type="button" className="i-btn i-btn--danger" onClick={() => void remove()} disabled={act.busy === "delete"}>
              Delete project and its mentions
            </button>
          </>
        }
      >
        <p>Its runs and mentions are deleted. Incidents and investigations stay, without the link to this project.</p>
      </Dialog>
      {toast.node}
    </>
  );
}

function Def({ label, items }: { label: string; items: string[] }) {
  return (
    <div>
      <dt className="i-label">{label}</dt>
      <dd style={{ margin: "2px 0 0" }}>{items.length === 0 ? <span className="muted">—</span> : items.map((t) => <span key={t} className="i-tag">{t}</span>)}</dd>
    </div>
  );
}
