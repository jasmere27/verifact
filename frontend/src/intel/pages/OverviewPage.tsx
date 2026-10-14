import { useIntel } from "../context";
import type { Overview } from "../types";
import Link from "../../components/Link";
import { ago, humanize, useLoad } from "../lib";
import { AssessmentBadge, Badge, Empty, HealthBadge, IncidentStatusBadge, Load, Meter, PageHead, Panel, PriorityBadge, RunBadge } from "../ui";

const FEATURE_LABEL: Record<string, string> = {
  MONITORING_RUN: "Monitoring runs",
  INVESTIGATION: "Investigations",
  RESPONSE_DRAFT: "Response drafts",
  REPORT: "Reports",
};

export default function OverviewPage() {
  const { api, orgId, href, workspace } = useIntel();
  const r = useLoad(() => api.overview(), orgId);
  return (
    <>
      <PageHead
        title="Overview"
        sub={workspace ? `${workspace.personal ? "Personal workspace" : workspace.name}: what needs attention now.` : undefined}
        actions={
          <>
            <Link href={href("investigations") + "?new=1"} className="i-btn">
              New investigation
            </Link>
            <Link href={href("monitoring", "new")} className="i-btn i-btn--primary">
              New monitoring project
            </Link>
          </>
        }
      />
      <Load r={r} lines={8}>
        {(o) => (o.onboarding ? <Onboarding o={o} /> : <Dashboard o={o} />)}
      </Load>
    </>
  );
}

function Onboarding({ o }: { o: Overview }) {
  const { href, can } = useIntel();
  const hasPlan = o.subscription.entitled;
  const steps = [
    {
      done: true,
      title: "Create your workspace",
      body: "Done. Invite colleagues under Settings when you're ready.",
      action: null,
    },
    {
      done: hasPlan,
      title: "Start a trial or choose a plan",
      body: hasPlan ? `${o.subscription.plan?.name} plan active.` : "Monitoring and investigations need a plan. A free trial is available once per person.",
      action: hasPlan ? null : (
        <Link href={href("billing")} className="i-btn i-btn--sm">
          Subscription
        </Link>
      ),
    },
    {
      done: false,
      title: "Configure your first brand",
      body: "Name the brand, its aliases, products and executives, and the topics you're worried about.",
      action: can("ADMIN") ? (
        <Link href={href("monitoring", "new")} className="i-btn i-btn--sm i-btn--primary">
          Set up monitoring
        </Link>
      ) : (
        <span className="small muted">Ask an admin</span>
      ),
    },
    { done: false, title: "Choose sources and alert preferences", body: "Web and news search, public RSS feeds and pages you name. Social platforms aren't covered.", action: null },
    { done: false, title: "Run the first check and review results", body: "Run a check now from the project page; new mentions appear under Monitoring.", action: null },
    {
      done: false,
      title: "Investigate a claim",
      body: "Paste any statement or link to gather evidence for and against it, even before monitoring finds something.",
      action: (
        <Link href={href("investigations") + "?new=1"} className="i-btn i-btn--sm">
          Investigate
        </Link>
      ),
    },
    { done: false, title: "Generate your first report", body: "Reports summarise monitoring, incidents and findings for a period, with citations.", action: null },
  ];
  return (
    <div className="i-split">
      <Panel title="Get set up" id="setup">
        <ol className="i-steps">
          {steps.map((s, i) => (
            <li key={s.title}>
              <span className={`i-step-num${s.done ? " done" : ""}`} aria-label={s.done ? "Done" : `Step ${i + 1}`}>
                {s.done ? "✓" : i + 1}
              </span>
              <span>
                <strong>{s.title}</strong>
                <span className="i-meta">{s.body}</span>
              </span>
              {s.action}
            </li>
          ))}
        </ol>
      </Panel>
      <Panel title="What VeriFact Intelligence does" id="what">
        <div className="i-stack-sm small">
          <p>Watches the sources you choose for mentions of your brand, flags potentially damaging claims, and turns them into incidents.</p>
          <p>Investigations gather web evidence for and against a claim. Every excerpt shown is checked word for word against the retrieved source.</p>
          <p className="muted">
            Assessments are automated aids, not verdicts. Nothing is published or sent on your behalf; drafts need human approval.
          </p>
        </div>
      </Panel>
    </div>
  );
}

function Dashboard({ o }: { o: Overview }) {
  const { href } = useIntel();
  const total = Object.values(o.openByPriority).reduce((a, b) => a + b, 0);
  return (
    <div className="i-stack">
      {!o.subscription.entitled && (
        <div className="i-alert i-alert--warn" role="status">
          <strong>No active plan</strong>
          <span>
            Scheduled monitoring and new investigations are paused until this workspace has a plan. <Link href={href("billing")}>Subscription</Link>
          </span>
        </div>
      )}
      <div className="i-kpis">
        <Kpi label="Incidents needing review" value={o.incidentsNeedingReview} sub={`${o.openIncidents} open in total`} to={href("incidents") + "?status=NEW"} />
        <Kpi label="New mentions (7 days)" value={o.newMentions7d} sub={`${o.unreviewedMentions} not yet reviewed`} to={href("monitoring")} />
        <Kpi label="Claims flagged (7 days)" value={o.claimsFlagged7d} sub="potentially damaging, AI-identified" to={href("monitoring")} />
        <Kpi label="Monitored projects" value={o.activeProjects} sub={o.pausedProjects ? `${o.pausedProjects} paused` : "all active"} to={href("monitoring")} />
        <Kpi label="Evidence retrieved (7 days)" value={o.evidenceRecords7d} sub={`${o.changedInvestigations7d} investigations changed`} to={href("evidence")} />
      </div>

      <div className="i-split">
        <div className="i-stack">
          <Panel
            title="Highest-priority open incidents"
            id="top-incidents"
            tight
            actions={
              <Link href={href("incidents")} className="i-btn i-btn--sm">
                All incidents
              </Link>
            }
          >
            {total > 0 && (
              <div style={{ padding: "12px 16px", borderBottom: "1px solid var(--i-line)" }}>
                <div className="i-dist" aria-hidden="true">
                  {(["CRITICAL", "HIGH", "MEDIUM", "LOW"] as const).map((p) =>
                    o.openByPriority[p] ? (
                      <span
                        key={p}
                        style={{
                          flex: o.openByPriority[p],
                          background: { CRITICAL: "var(--i-critical)", HIGH: "var(--i-bad)", MEDIUM: "var(--i-warn)", LOW: "var(--i-muted)" }[p],
                        }}
                      />
                    ) : null,
                  )}
                </div>
                <div className="i-legend" style={{ marginTop: 6 }}>
                  {(["CRITICAL", "HIGH", "MEDIUM", "LOW"] as const).map((p) => (
                    <span key={p}>
                      {humanize(p)} <strong className="num">{o.openByPriority[p]}</strong>
                    </span>
                  ))}
                </div>
              </div>
            )}
            {o.topIncidents.length === 0 ? (
              <Empty title="No open incidents">Incidents open automatically when monitoring finds a serious claim, or you can open one by hand.</Empty>
            ) : (
              <ul className="i-list">
                {o.topIncidents.map((i) => (
                  <li key={i.id}>
                    <div className="i-row">
                      <Link href={href("incidents", i.id)}>
                        <strong>{i.title}</strong>
                      </Link>
                      <PriorityBadge p={i.priority} />
                    </div>
                    <div className="i-meta">
                      <IncidentStatusBadge s={i.status} />
                      <span>score {i.riskScore}</span>
                      {i.projectName && <span>{i.projectName}</span>}
                      <span>{i.ownerEmail ?? "unassigned"}</span>
                      <span>last seen {ago(i.lastSeenAt)}</span>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel
            title="Recent investigations"
            id="recent-investigations"
            tight
            actions={
              <Link href={href("investigations")} className="i-btn i-btn--sm">
                All investigations
              </Link>
            }
          >
            {o.recentInvestigations.length === 0 ? (
              <Empty title="No investigations yet">Investigate a claim to see the evidence for and against it.</Empty>
            ) : (
              <ul className="i-list">
                {o.recentInvestigations.map((v) => (
                  <li key={v.id} className="i-row">
                    <span>
                      <Link href={href("investigations", v.id)}>{v.title}</Link>
                      <span className="i-meta">
                        <span>v{v.version || "—"}</span>
                        <span>updated {ago(v.updatedAt)}</span>
                        {v.incidentTitle && <span>incident: {v.incidentTitle}</span>}
                      </span>
                    </span>
                    {v.status === "COMPLETED" && v.headline ? (
                      <AssessmentBadge a={v.headline} short />
                    ) : (
                      <Badge tone={v.status === "FAILED" ? "bad" : "info"}>{humanize(v.status)}</Badge>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        </div>

        <div className="i-stack">
          <Panel title="Monitoring source health" id="health" tight>
            {o.sourceHealth.length === 0 ? (
              <Empty title="No monitoring yet" action={<Link href={href("monitoring", "new")} className="i-btn i-btn--sm">Set up monitoring</Link>} />
            ) : (
              <ul className="i-list">
                {o.sourceHealth.map((h) => (
                  <li key={h.projectId}>
                    <div className="i-row">
                      <Link href={href("monitoring", h.projectId)}>{h.projectName}</Link>
                      <HealthBadge h={h.health} />
                    </div>
                    <div className="i-meta">
                      <span>last run {ago(h.lastRunAt)}</span>
                      <span>{h.status === "ACTIVE" ? `next ${ago(h.nextRunAt)}` : "paused"}</span>
                    </div>
                    {h.lastSources.some((s) => s.status !== "OK") && (
                      <div className="i-meta">
                        {h.lastSources
                          .filter((s) => s.status !== "OK")
                          .map((s) => (
                            <span key={s.connector}>
                              {humanize(s.connector)}: {humanize(s.status)}
                            </span>
                          ))}
                      </div>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel title="Recent monitoring activity" id="runs" tight>
            {o.recentRuns.length === 0 ? (
              <Empty title="No runs yet" />
            ) : (
              <ul className="i-list">
                {o.recentRuns.map((run) => (
                  <li key={run.id}>
                    <div className="i-row">
                      <span className="small">{run.projectName}</span>
                      <RunBadge s={run.status} />
                    </div>
                    <div className="i-meta">
                      <span>{ago(run.startedAt)}</span>
                      <span>{humanize(run.trigger)}</span>
                      <span className="num">{run.newMentions} new</span>
                      {run.newIncidents > 0 && <span className="num">{run.newIncidents} incident(s)</span>}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel
            title="Usage this month"
            id="usage"
            actions={
              <Link href={href("billing")} className="i-btn i-btn--sm">
                Details
              </Link>
            }
          >
            <div className="i-stack-sm">
              {o.usage.features.map((f) => (
                <div key={f.feature} className="i-stack-sm" style={{ gap: 4 }}>
                  <div className="i-row small">
                    <span>{FEATURE_LABEL[f.feature]}</span>
                    <span className="num muted">
                      {f.used} / {f.limit || "—"}
                    </span>
                  </div>
                  <Meter value={f.used} max={f.limit} label={FEATURE_LABEL[f.feature]} />
                </div>
              ))}
            </div>
          </Panel>
        </div>
      </div>
    </div>
  );
}

function Kpi({ label, value, sub, to }: { label: string; value: number; sub: string; to: string }) {
  return (
    <div className="i-kpi">
      <span className="i-kpi-label">{label}</span>
      <Link href={to} className="i-kpi-value">
        {value}
      </Link>
      <span className="i-kpi-sub">{sub}</span>
    </div>
  );
}
