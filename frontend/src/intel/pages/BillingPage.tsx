import { useIntel } from "../context";
import type { BillingState, Feature, Plan } from "../types";
import { fmtDate, humanize, useAction, useLoad, useToast } from "../lib";
import { Badge, Load, Meter, Notice, PageHead, Panel } from "../ui";

const FEATURE_LABEL: Record<Feature, string> = {
  MONITORING_RUN: "Monitoring runs",
  INVESTIGATION: "Investigations",
  RESPONSE_DRAFT: "Response Studio drafts",
  REPORT: "Reports",
};

export default function BillingPage() {
  const { api, orgId, can, reloadWorkspace } = useIntel();
  const r = useLoad(() => api.billing(), orgId);
  const act = useAction();
  const toast = useToast();

  async function trial() {
    if (await act.run("trial", () => api.startTrial())) {
      r.reload();
      reloadWorkspace();
      toast.show("Trial started.");
    }
  }

  async function choose(plan: Plan) {
    const res = await act.run(plan.id, () => api.choosePlan(plan.id));
    if (!res) return;
    if (res.checkoutUrl) window.location.assign(res.checkoutUrl);
    else toast.show(res.message ?? "Plan change requested.");
  }

  async function cancel() {
    const res = await act.run("cancel", () => api.cancel());
    if (res) toast.show(res.message);
  }

  async function portal() {
    const res = await act.run("portal", () => api.portal());
    if (res) window.open(res.url, "_blank", "noopener");
  }

  return (
    <>
      <PageHead title="Subscription & usage" sub="Plans, this month's usage against your plan, and what it cost to run (for transparency)." />
      {act.error && <Notice tone="bad">{act.error}</Notice>}
      <Load r={r} lines={8}>
        {(b) => (
          <div className="i-stack" style={{ marginTop: 12 }}>
            <Current b={b} onTrial={trial} onCancel={cancel} onPortal={portal} busy={act.busy} />
            <Panel title="Usage this month" id="usage">
              <div className="i-grid i-grid-2">
                {b.usage.features.map((f) => (
                  <div key={f.feature} className="i-stack-sm" style={{ gap: 4 }}>
                    <div className="i-row small">
                      <span>{FEATURE_LABEL[f.feature]}</span>
                      <span className="num muted">
                        {f.used} / {f.limit || "not included"}
                      </span>
                    </div>
                    <Meter value={f.used} max={f.limit} label={FEATURE_LABEL[f.feature]} />
                  </div>
                ))}
              </div>
              <p className="small muted" style={{ marginTop: 10 }}>
                Resets {fmtDate(b.usage.periodEnd)} (UTC month). Processing so far: {b.usage.costs.llmCalls} AI calls (
                {(b.usage.costs.promptTokens + b.usage.costs.completionTokens).toLocaleString()} tokens) and {b.usage.costs.searchCalls} web searches.
              </p>
            </Panel>
            <div className="i-plan-grid">
              {b.plans.map((p) => {
                const current = b.subscription.plan?.id === p.id && b.subscription.entitled;
                return (
                  <section key={p.id} className={`i-panel i-plan${current ? " current" : ""}`} aria-label={`${p.name} plan`}>
                    <div className="i-row">
                      <h2>{p.name}</h2>
                      {current && <Badge tone="accent">Current</Badge>}
                    </div>
                    <div className="i-price">
                      ${p.priceUsdMonthly}
                      <small> / month</small>
                    </div>
                    <p className="small muted">{p.summary}</p>
                    <ul>
                      <li>{p.maxProjects} monitoring project{p.maxProjects === 1 ? "" : "s"}, {p.maxTermsPerProject} terms each</li>
                      <li>Checks as often as every {p.minCheckIntervalHours} h · {p.monitoringRunsPerMonth} runs/month</li>
                      <li>{p.investigationsPerMonth} investigations/month</li>
                      <li>{p.responseDraftsPerMonth > 0 ? `Response Studio: ${p.responseDraftsPerMonth} drafts/month` : "No Response Studio"}</li>
                      <li>{p.reportsPerMonth} reports/month</li>
                      <li>{p.seats} seat{p.seats === 1 ? "" : "s"} · {p.maxRssFeedsPerProject} RSS feeds per project</li>
                    </ul>
                    {can("OWNER") && !current && (
                      <button
                        type="button"
                        className="i-btn i-btn--primary"
                        disabled={!b.onlinePayment || !p.purchasable || act.busy === p.id}
                        onClick={() => void choose(p)}
                      >
                        {b.subscription.status === "ACTIVE" ? `Switch to ${p.name}` : `Choose ${p.name}`}
                      </button>
                    )}
                  </section>
                );
              })}
            </div>
            {!b.onlinePayment && (
              <Notice tone="info" title="Online payment isn't set up yet">
                Plans can't be bought in the app until the payment provider is connected. Contact us to subscribe; nothing is charged here.
              </Notice>
            )}
            {!can("OWNER") && <p className="small muted">Only workspace owners can change the plan.</p>}
          </div>
        )}
      </Load>
      {toast.node}
    </>
  );
}

function Current({ b, onTrial, onCancel, onPortal, busy }: { b: BillingState; onTrial: () => void; onCancel: () => void; onPortal: () => void; busy: string | null }) {
  const { can } = useIntel();
  const s = b.subscription;
  const tone = s.status === "ACTIVE" || s.status === "TRIALING" ? "ok" : s.status === "PAST_DUE" ? "warn" : "neutral";
  return (
    <Panel title="Current plan" id="current">
      <div className="i-row" style={{ alignItems: "center" }}>
        <div className="i-stack-sm" style={{ gap: 4 }}>
          <span>
            <strong style={{ fontSize: 16 }}>{s.plan ? s.plan.name : "No plan"}</strong> <Badge tone={tone}>{humanize(s.status)}</Badge>
          </span>
          <span className="small muted">
            {s.status === "TRIALING" && s.trialEndsAt && `Trial ends ${fmtDate(s.trialEndsAt)}.`}
            {s.status === "ACTIVE" && s.currentPeriodEnd && (s.cancelAtPeriodEnd ? `Cancels on ${fmtDate(s.currentPeriodEnd)}.` : `Renews ${fmtDate(s.currentPeriodEnd)}.`)}
            {s.status === "PAST_DUE" && "The last payment failed. The payment provider is retrying; update your payment details to keep access."}
            {s.status === "EXPIRED" && "The trial has ended. Existing data stays readable; choose a plan to keep monitoring."}
            {s.status === "CANCELED" && "The subscription has ended. Existing data stays readable."}
            {s.status === "NONE" && "Start the free trial or choose a plan to enable monitoring and investigations."}
          </span>
        </div>
        <div className="i-actions">
          {s.trialAvailable && can("ADMIN") && (
            <button type="button" className="i-btn i-btn--primary" onClick={onTrial} disabled={busy === "trial"}>
              Start free trial
            </button>
          )}
          {(s.status === "ACTIVE" || s.status === "PAST_DUE") && can("OWNER") && (
            <>
              <button type="button" className="i-btn" onClick={onPortal} disabled={busy === "portal"}>
                Billing history &amp; payment details
              </button>
              {!s.cancelAtPeriodEnd && (
                <button type="button" className="i-btn i-btn--danger" onClick={onCancel} disabled={busy === "cancel"}>
                  Cancel subscription
                </button>
              )}
            </>
          )}
        </div>
      </div>
    </Panel>
  );
}
