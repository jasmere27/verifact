import { useState } from "react";
import type { FormEvent } from "react";
import { navigate } from "../../router";
import { useIntel } from "../context";
import type { DraftKind } from "../types";
import Link from "../../components/Link";
import { ago, humanize, useAction, useLoad } from "../lib";
import { Badge, Empty, Load, Notice, PageHead, Panel } from "../ui";

const KINDS: { id: DraftKind; label: string; hint: string }[] = [
  { id: "HOLDING_STATEMENT", label: "Holding statement", hint: "Acknowledge, commit to looking into it, no position yet." },
  { id: "INTERNAL_BRIEFING", label: "Internal incident briefing", hint: "For the team: claim, evidence, gaps, actions." },
  { id: "EXECUTIVE_SUMMARY", label: "Executive summary", hint: "Short, for leadership: position, risk, decision." },
  { id: "CUSTOMER_COMMUNICATION", label: "Customer communication", hint: "Calm message: confirmed, not confirmed, what to do." },
  { id: "PUBLIC_RESPONSE", label: "Public response draft", hint: "Only what the cited evidence establishes." },
  { id: "CORRECTIVE_INFORMATION", label: "Corrective information", hint: "For points the evidence contradicts or contextualises." },
  { id: "FAQ", label: "Frequently asked questions", hint: "Q&A answered from the evidence; open points stay open." },
  { id: "ESCALATION_CHECKLIST", label: "Escalation checklist", hint: "Who to inform, what to verify, decision points." },
];

export default function ResponsesPage() {
  const { api, orgId, href, workspace } = useIntel();
  const params = new URLSearchParams(window.location.search);
  const [investigationId, setInvestigationId] = useState(params.get("investigation") ?? "");
  const incidentId = params.get("incident") ?? undefined;
  const [kind, setKind] = useState<DraftKind>("HOLDING_STATEMENT");
  const [speakingFor, setSpeakingFor] = useState("");
  const drafts = useLoad(() => api.drafts({}), orgId);
  const investigations = useLoad(() => api.investigations({ size: 100 }), orgId);
  const act = useAction();
  const plan = workspace?.subscription.plan;
  const included = !plan || plan.responseDraftsPerMonth > 0;

  async function generate(e: FormEvent) {
    e.preventDefault();
    const d = await act.run("generate", () => api.generateDraft({ investigationId, kind, speakingFor: speakingFor || undefined, incidentId }));
    if (d) navigate(href("responses", d.summary.id));
  }

  const completed = investigations.data?.items.filter((v) => v.version > 0) ?? [];
  return (
    <>
      <PageHead title="Response Studio" sub="Communication drafts built only from an investigation's evidence, for your team to edit and approve. Nothing is ever sent or published." />
      <div className="i-split">
        <Panel title="Drafts" id="drafts" tight>
          <Load r={drafts}>
            {(list) =>
              list.length === 0 ? (
                <Empty title="No drafts yet">Choose a completed investigation and a draft type to generate the first one.</Empty>
              ) : (
                <ul className="i-list">
                  {list.map((d) => (
                    <li key={d.id}>
                      <div className="i-row">
                        <Link href={href("responses", d.id)}>
                          <strong>{d.title}</strong>
                        </Link>
                        <Badge tone={d.status === "APPROVED" ? "ok" : d.status === "IN_REVIEW" ? "warn" : "neutral"}>{humanize(d.status)}</Badge>
                      </div>
                      <div className="i-meta">
                        <span>{d.kindLabel}</span>
                        <span>revision {d.revision}</span>
                        {d.investigationTitle && <span>from: {d.investigationTitle}{d.investigationVersion ? ` (v${d.investigationVersion})` : ""}</span>}
                        <span>updated {ago(d.updatedAt)}</span>
                      </div>
                    </li>
                  ))}
                </ul>
              )
            }
          </Load>
        </Panel>
        <Panel title="Generate a draft" id="generate">
          {!included ? (
            <Notice tone="info" title={`Not included in ${plan?.name}`}>
              Response Studio is part of the Intelligence and Business plans. <Link href={href("billing")}>Compare plans</Link>
            </Notice>
          ) : (
            <form onSubmit={generate} className="i-stack-sm">
              <label className="i-field">
                <span>From investigation *</span>
                <select className="i-input" value={investigationId} onChange={(e) => setInvestigationId(e.target.value)} required>
                  <option value="">Choose…</option>
                  {completed.map((v) => (
                    <option key={v.id} value={v.id}>
                      {v.title} (v{v.version})
                    </option>
                  ))}
                </select>
                {investigations.data && completed.length === 0 && <small>No completed investigations yet.</small>}
              </label>
              <fieldset className="i-fieldset" style={{ padding: "10px 12px" }}>
                <legend>Draft type</legend>
                {KINDS.map((k) => (
                  <label key={k.id} className="i-check">
                    <input type="radio" name="kind" value={k.id} checked={kind === k.id} onChange={() => setKind(k.id)} />
                    <span>
                      {k.label}
                      <span className="small muted"> — {k.hint}</span>
                    </span>
                  </label>
                ))}
              </fieldset>
              <label className="i-field">
                <span>Writing on behalf of</span>
                <input className="i-input" value={speakingFor} onChange={(e) => setSpeakingFor(e.target.value)} maxLength={120} placeholder="e.g. Acme Foods communications team" />
              </label>
              {act.error && <Notice tone="bad">{act.error}</Notice>}
              <button type="submit" className="i-btn i-btn--primary" disabled={!investigationId || act.busy !== null}>
                {act.busy ? "Drafting… (about 20 seconds)" : "Generate draft"}
              </button>
              <p className="small muted">The model sees only the investigation's claims, assessments and verified excerpts. Figures not found in that evidence are removed.</p>
            </form>
          )}
        </Panel>
      </div>
    </>
  );
}
