import { useState } from "react";
import { navigate } from "../../router";
import { useIntel } from "../context";
import type { DraftDetail } from "../types";
import Link from "../../components/Link";
import { fmtDateTime, humanize, useAction, useLoad, useToast } from "../lib";
import { AiLabel, Badge, Dialog, ErrorBox, ExternalLink, Notice, PageHead, Panel, Skeleton } from "../ui";

export default function ResponsePage({ id }: { id: string }) {
  const { api, href, can } = useIntel();
  const r = useLoad(() => api.draft(id), id);
  const [body, setBody] = useState<string | null>(null);
  const [note, setNote] = useState("");
  const [showRevision, setShowRevision] = useState<number | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const act = useAction();
  const toast = useToast();

  if (r.error) return <ErrorBox error={r.error} onRetry={r.reload} />;
  const d = r.data;
  if (!d) return <Skeleton lines={10} />;
  const s = d.summary;
  const editing = body !== null;

  async function save() {
    const updated = await act.run("save", () => api.editDraft(id, { body: body ?? undefined, note: note || undefined }));
    if (updated) {
      r.setData(updated);
      setBody(null);
      setNote("");
      toast.show(updated.summary.revision > s.revision ? `Saved as revision ${updated.summary.revision}.` : "No changes to save.");
    }
  }

  async function setStatus(status: string) {
    const updated = await act.run("status", () => api.draftStatus(id, status));
    if (updated) {
      r.setData(updated);
      toast.show(status === "APPROVED" ? "Approved. Copy or export it for use; VeriFact never publishes it." : `Marked ${humanize(status).toLowerCase()}.`);
    }
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(d!.body);
      toast.show("Copied to the clipboard.");
    } catch {
      toast.show("Couldn't copy: select the text and copy it instead.");
    }
  }

  function exportText() {
    const sources = d!.meta.citations.map((c) => `[${c.sourceId}] ${c.title || c.domain} — ${c.url}`).join("\n");
    const text = `${s.title}\n${s.kindLabel} · revision ${s.revision} · ${humanize(s.status)}\n\n${d!.body}\n\nSources\n${sources}\n`;
    const url = URL.createObjectURL(new Blob([text], { type: "text/plain;charset=utf-8" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = `${s.title.replace(/[^\w-]+/g, "-").slice(0, 60) || "draft"}.txt`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  }

  async function remove() {
    const done = await act.run("delete", async () => {
      await api.deleteDraft(id);
      return true;
    });
    if (done) navigate(href("responses"));
  }

  const revision = showRevision ? d.revisions.find((x) => x.revision === showRevision) : null;
  return (
    <>
      <PageHead
        title={s.title}
        crumbs={<Link href={href("responses")}>Response Studio</Link>}
        sub={
          <>
            {s.kindLabel} · <Badge tone={s.status === "APPROVED" ? "ok" : s.status === "IN_REVIEW" ? "warn" : "neutral"}>{humanize(s.status)}</Badge> · revision {s.revision}
            {s.approvedByEmail && s.status === "APPROVED" && <> · approved by {s.approvedByEmail} {fmtDateTime(s.approvedAt)}</>}
          </>
        }
        actions={
          <>
            <button type="button" className="i-btn" onClick={() => void copy()}>
              Copy
            </button>
            <button type="button" className="i-btn" onClick={exportText}>
              Export .txt
            </button>
            {s.status === "DRAFT" && (
              <button type="button" className="i-btn" onClick={() => void setStatus("IN_REVIEW")} disabled={editing}>
                Request review
              </button>
            )}
            {s.status !== "APPROVED" && can("ADMIN") && (
              <button type="button" className="i-btn i-btn--primary" onClick={() => void setStatus("APPROVED")} disabled={editing}>
                Approve
              </button>
            )}
            {s.status !== "DRAFT" && (
              <button type="button" className="i-btn i-btn--ghost" onClick={() => void setStatus("DRAFT")} disabled={editing}>
                Back to draft
              </button>
            )}
            <button type="button" className="i-btn i-btn--danger" onClick={() => setConfirmDelete(true)}>
              Delete
            </button>
          </>
        }
      />
      {act.error && <Notice tone="bad">{act.error}</Notice>}
      <div className="i-split" style={{ marginTop: 12 }}>
        <div className="i-stack">
          {d.meta.evidenceWarning && (
            <Notice tone="warn" title="Evidence warning">
              {d.meta.evidenceWarning}
            </Notice>
          )}
          <Panel
            title={
              <>
                {revision ? `Revision ${revision.revision}` : "Draft"} {d.meta.aiGenerated && s.revision === 1 && !revision && <AiLabel what="AI draft" />}
              </>
            }
            id="body"
            actions={
              !editing && !revision ? (
                <button type="button" className="i-btn i-btn--sm" onClick={() => setBody(d.body)}>
                  Edit
                </button>
              ) : revision ? (
                <button type="button" className="i-btn i-btn--sm" onClick={() => setShowRevision(null)}>
                  Back to current
                </button>
              ) : null
            }
          >
            {editing ? (
              <div className="i-stack-sm">
                <label className="visually-hidden-i" htmlFor="draft-body">
                  Draft text
                </label>
                <textarea id="draft-body" className="i-input i-draft-editor" value={body} onChange={(e) => setBody(e.target.value)} maxLength={20000} />
                <label className="i-field">
                  <span>Note for the revision history</span>
                  <input className="i-input" value={note} onChange={(e) => setNote(e.target.value)} maxLength={200} />
                </label>
                {s.status === "APPROVED" && <Notice tone="info">Saving changes returns the draft to Draft: approval covers the approved words only.</Notice>}
                <div className="i-actions">
                  <button type="button" className="i-btn i-btn--primary" onClick={() => void save()} disabled={act.busy === "save" || !body?.trim()}>
                    Save revision
                  </button>
                  <button type="button" className="i-btn" onClick={() => setBody(null)}>
                    Discard changes
                  </button>
                </div>
              </div>
            ) : (
              <div className="i-draft">{revision ? revision.body : d.body}</div>
            )}
          </Panel>
          <Panel title="Sources cited" id="citations" tight>
            {d.meta.citations.length === 0 ? (
              <p className="small muted" style={{ padding: 16 }}>
                This draft cites no sources.
              </p>
            ) : (
              <ul className="i-list">
                {d.meta.citations.map((c) => (
                  <li key={c.sourceId}>
                    <span className="mono muted">[{c.sourceId}]</span> <ExternalLink href={c.url}>{c.title || c.domain}</ExternalLink> <span className="small muted">{c.domain}</span>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        </div>
        <div className="i-stack">
          <Facts d={d} />
          <Panel title="Revision history" id="revisions" tight>
            <ul className="i-list">
              {d.revisions.map((x) => (
                <li key={x.revision}>
                  <div className="i-row">
                    <strong>Revision {x.revision}</strong>
                    <button type="button" className="i-btn i-btn--sm" onClick={() => setShowRevision(x.revision === s.revision ? null : x.revision)}>
                      View
                    </button>
                  </div>
                  <div className="i-meta">
                    <span>{fmtDateTime(x.createdAt)}</span>
                    <span>{x.editedByEmail ?? "—"}</span>
                    {x.note && <span>{x.note}</span>}
                  </div>
                </li>
              ))}
            </ul>
          </Panel>
          {s.investigationId && (
            <Panel title="Based on" id="based-on">
              <Link href={href("investigations", s.investigationId)}>{s.investigationTitle}</Link>
              {s.investigationVersion && <span className="small muted"> · evidence version {s.investigationVersion}</span>}
            </Panel>
          )}
        </div>
      </div>
      <Dialog
        open={confirmDelete}
        title="Delete this draft?"
        onClose={() => setConfirmDelete(false)}
        footer={
          <>
            <button type="button" className="i-btn" onClick={() => setConfirmDelete(false)}>
              Cancel
            </button>
            <button type="button" className="i-btn i-btn--danger" onClick={() => void remove()}>
              Delete draft and history
            </button>
          </>
        }
      >
        <p>The draft and all its revisions are deleted.</p>
      </Dialog>
      {toast.node}
    </>
  );
}

function Facts({ d }: { d: DraftDetail }) {
  const m = d.meta;
  return (
    <Panel title="Key facts and open questions" id="facts">
      <div className="i-stack-sm small">
        <span className="i-kind">Confirmed by cited sources</span>
        {m.keyFacts.length === 0 ? (
          <p className="muted">No confirmed facts: nothing in the evidence settles the claims.</p>
        ) : (
          <ul style={{ margin: 0, paddingLeft: 18 }}>
            {m.keyFacts.map((f) => (
              <li key={f.text}>
                {f.text} <span className="mono muted">{f.sourceIds.map((x) => `[${x}]`).join(" ")}</span>
              </li>
            ))}
          </ul>
        )}
        <span className="i-kind">Unresolved</span>
        {m.uncertainties.length === 0 ? <p className="muted">None listed.</p> : <ul style={{ margin: 0, paddingLeft: 18 }}>{m.uncertainties.map((u) => <li key={u}>{u}</li>)}</ul>}
        <span className="i-kind">
          Suggested next steps <AiLabel />
        </span>
        {m.nextSteps.length === 0 ? <p className="muted">None listed.</p> : <ul style={{ margin: 0, paddingLeft: 18 }}>{m.nextSteps.map((u) => <li key={u}>{u}</li>)}</ul>}
        {m.removedForGrounding > 0 && (
          <p className="muted">
            {m.removedForGrounding} sentence(s) or fact(s) from the model were removed because their figures or citations weren't in the evidence.
          </p>
        )}
      </div>
    </Panel>
  );
}
