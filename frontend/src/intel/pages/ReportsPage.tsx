import { useState } from "react";
import type { FormEvent } from "react";
import { navigate } from "../../router";
import { useIntel } from "../context";
import Link from "../../components/Link";
import { fmtDate, useAction, useLoad } from "../lib";
import { Empty, Load, Notice, PageHead, Panel } from "../ui";

export default function ReportsPage() {
  const { api, orgId, href } = useIntel();
  const r = useLoad(() => api.reports(), orgId);
  const [days, setDays] = useState(30);
  const [title, setTitle] = useState("");
  const act = useAction();
  async function generate(e: FormEvent) {
    e.preventDefault();
    const rep = await act.run("generate", () => api.generateReport(days, title || undefined));
    if (rep) navigate(href("reports", rep.id));
  }
  return (
    <>
      <PageHead title="Reports" sub="Period reports built from this workspace's monitoring, incidents and investigations, with citations. A report is a snapshot and doesn't change later." />
      <div className="i-split">
        <Panel title="Generated reports" id="reports" tight>
          <Load r={r}>
            {(list) =>
              list.length === 0 ? (
                <Empty title="No reports yet">Generate one for the last week or month.</Empty>
              ) : (
                <ul className="i-list">
                  {list.map((x) => (
                    <li key={x.id}>
                      <Link href={href("reports", x.id)}>
                        <strong>{x.title}</strong>
                      </Link>
                      <div className="i-meta">
                        <span>
                          {fmtDate(x.periodStart)} – {fmtDate(x.periodEnd)}
                        </span>
                        <span>generated {fmtDate(x.generatedAt)}</span>
                        {x.generatedBy && <span>by {x.generatedBy}</span>}
                      </div>
                    </li>
                  ))}
                </ul>
              )
            }
          </Load>
        </Panel>
        <Panel title="New report" id="new-report">
          <form onSubmit={generate} className="i-stack-sm">
            <label className="i-field">
              <span>Period</span>
              <select className="i-input" value={days} onChange={(e) => setDays(Number(e.target.value))}>
                <option value={7}>Last 7 days</option>
                <option value={30}>Last 30 days</option>
                <option value={90}>Last 90 days</option>
              </select>
            </label>
            <label className="i-field">
              <span>Title</span>
              <input className="i-input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} placeholder="Defaults to workspace and dates" />
            </label>
            {act.error && <Notice tone="bad">{act.error}</Notice>}
            <button type="submit" className="i-btn i-btn--primary" disabled={act.busy !== null}>
              {act.busy ? "Generating…" : "Generate report"}
            </button>
            <p className="small muted">
              Scheduled email delivery isn't available yet: generate reports here and share the PDF.
            </p>
          </form>
        </Panel>
      </div>
    </>
  );
}
