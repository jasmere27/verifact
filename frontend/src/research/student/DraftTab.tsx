import { useRef, useState } from "react";
import { errorMessage } from "../../api";
import { formatDateTime, plural } from "../../format";
import { checkResearchStream } from "../api";
import ResearchReport from "../ResearchReport";
import type { ResearchCheck } from "../types";
import type { Category, Draft } from "./types";

const ACCEPT = ".pdf,.docx,.pptx,.txt,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.presentationml.presentation,text/plain";
const MAX_BYTES = 10 * 1024 * 1024;

type Search = (category: Category, text: string, label: string) => void;

function SearchButtons({ text, onSearch, disabled }: { text: string; onSearch: Search; disabled: boolean }) {
  return (
    <div className="row">
      <button type="button" className="button button--secondary button--small" disabled={disabled} onClick={() => onSearch("FOR_TEXT", text, "Sources for this passage")}>
        Find sources
      </button>
      <button type="button" className="button button--secondary button--small" disabled={disabled} onClick={() => onSearch("SUPPORTING", text, "Studies that support it")}>
        Supporting
      </button>
      <button type="button" className="button button--secondary button--small" disabled={disabled} onClick={() => onSearch("CONTRADICTING", text, "Studies that contradict it")}>
        Contradicting
      </button>
    </div>
  );
}

/** The uploaded draft and its analysis; shared by quick workspaces and capstone projects (they supply the upload/remove calls). */
export default function DraftTab({
  draft,
  canEdit,
  busy,
  onUpload,
  onRemove,
  onSearch,
}: {
  draft: Draft | null;
  canEdit: boolean;
  busy: boolean;
  onUpload: (file: File) => Promise<void>;
  onRemove: () => Promise<void>;
  onSearch: Search;
}) {
  const [uploading, setUploading] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [selection, setSelection] = useState("");
  const [check, setCheck] = useState<{ status: "idle" } | { status: "loading" } | { status: "done"; result: ResearchCheck } | { status: "error"; message: string }>({
    status: "idle",
  });
  const input = useRef<HTMLInputElement>(null);
  const textBox = useRef<HTMLDivElement>(null);

  async function upload(file: File | undefined) {
    if (!file || !canEdit) return;
    if (file.size > MAX_BYTES) {
      setProblem("The file is larger than 10 MB.");
      return;
    }
    setUploading(true);
    setProblem(null);
    setCheck({ status: "idle" });
    try {
      await onUpload(file);
    } catch (err) {
      setProblem(errorMessage(err).message);
    } finally {
      setUploading(false);
      if (input.current) input.current.value = "";
    }
  }

  async function remove() {
    if (!canEdit || !window.confirm("Remove the draft's text and analysis?")) return;
    try {
      await onRemove();
      setCheck({ status: "idle" });
    } catch (err) {
      setProblem(errorMessage(err).message);
    }
  }

  async function runCheck() {
    if (!draft?.citationCheckText) return;
    setCheck({ status: "loading" });
    try {
      setCheck({ status: "done", result: await checkResearchStream(draft.citationCheckText, {}) });
    } catch (err) {
      setCheck({ status: "error", message: errorMessage(err).message });
    }
  }

  function captureSelection() {
    const sel = window.getSelection();
    if (!sel || sel.isCollapsed || !textBox.current || !textBox.current.contains(sel.anchorNode)) return;
    const text = sel.toString().replace(/\s+/g, " ").trim();
    setSelection(text.length >= 15 ? text.slice(0, 3000) : "");
  }

  const picker = canEdit && (
    <div className="card st-upload">
      <label htmlFor="st-file" className="st-why-label">
        {draft ? "Replace with a newer version" : "Upload your draft: PDF, Word (.docx), PowerPoint (.pptx) or .txt, up to 10 MB"}
      </label>
      <input
        id="st-file"
        ref={input}
        type="file"
        accept={ACCEPT}
        disabled={uploading}
        onChange={(e) => void upload(e.target.files?.[0])}
      />
      <p className="muted small">
        We keep the text we extract (never the file) in this workspace so you can come back to it; it&apos;s deleted with the
        workspace. The text is sent to our AI provider for the analysis. Scanned PDFs without a text layer can&apos;t be read.
      </p>
    </div>
  );

  if (uploading) {
    return (
      <div className="card state-card" role="status">
        <span className="spinner" aria-hidden="true" />
        <p>Reading your draft… (usually 20–60 seconds)</p>
      </div>
    );
  }

  if (!draft) {
    return (
      <section className="report-section" aria-label="My draft">
        {problem && (
          <div className="alert" role="alert">
            <p>{problem}</p>
          </div>
        )}
        {picker ?? <p className="muted">No draft uploaded.</p>}
      </section>
    );
  }

  return (
    <section className="report-section st-draft" aria-label="My draft">
      {problem && (
        <div className="alert" role="alert">
          <p>{problem}</p>
        </div>
      )}
      <div className="st-draft-head">
        <p>
          <strong>{draft.fileName ?? "Your draft"}</strong>{" "}
          <span className="muted small">
            · {draft.kind}
            {draft.pages ? ` · ${plural(draft.pages, draft.kind === "PPTX" ? "slide" : "page")}` : ""} · {draft.chars.toLocaleString()} characters ·
            uploaded {formatDateTime(draft.uploadedAt)}
          </span>
        </p>
        {canEdit && (
          <button type="button" className="text-button" onClick={() => void remove()}>
            Remove draft
          </button>
        )}
      </div>

      {draft.summary && (
        <div className="st-block">
          <h2>Summary (AI reading of your draft)</h2>
          <p>{draft.summary}</p>
        </div>
      )}

      {draft.concepts.length > 0 && (
        <div className="st-block">
          <h2>Key concepts in your draft</h2>
          <ul className="st-chips">
            {draft.concepts.map((c) => (
              <li key={c}>
                <button type="button" className="st-chip" disabled={busy} onClick={() => onSearch("FOR_TEXT", c, `Sources on “${c}”`)} title="Find sources on this concept">
                  {c}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="st-block">
        <h2>Statements that may need a citation</h2>
        {draft.needsCitation.length === 0 ? (
          <p className="muted small">None found. That doesn&apos;t mean every claim is cited; check with your adviser.</p>
        ) : (
          <ul className="st-list">
            {draft.needsCitation.map((s) => (
              <li key={s.quote} className="st-card">
                <p className="st-quote">“{s.quote}”</p>
                {s.why && <p className="muted small">{s.why}</p>}
                <SearchButtons text={s.quote} onSearch={onSearch} disabled={busy} />
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="st-block">
        <h2>Your references</h2>
        <p className="small">
          {draft.citationCheckText
            ? `About ${plural(draft.referenceEntries, "reference entry", "reference entries")} and ${plural(draft.inTextCitations, "in-text citation")} detected.`
            : "No reference list found."}
        </p>
        {draft.citationCheckText && check.status !== "done" && (
          <div className="row">
            <button type="button" className="button button--primary button--small" disabled={check.status === "loading"} onClick={() => void runCheck()}>
              {check.status === "loading" ? "Checking… (about 1–2 minutes)" : "Check my citations"}
            </button>
            <span className="muted small">Checks up to 12 references and 8 cited claims: does each exist, is it retracted, does it support your sentence?</span>
          </div>
        )}
        {check.status === "error" && (
          <div className="alert" role="alert">
            <p>{check.message}</p>
          </div>
        )}
      </div>
      {check.status === "done" && <ResearchReport result={check.result} onNewCheck={() => setCheck({ status: "idle" })} />}

      <div className="st-block">
        <h2>Your draft</h2>
        <p className="muted small">Highlight a passage to find sources for it.</p>
        <div ref={textBox} className="st-draft-text" tabIndex={0} onMouseUp={captureSelection} onKeyUp={captureSelection} onTouchEnd={captureSelection}>
          {draft.text}
        </div>
        {selection && (
          <div className="st-selection" role="region" aria-label="Selected passage">
            <p className="small">
              <strong>Selected:</strong> “{selection.length > 160 ? `${selection.slice(0, 160)}…` : selection}”
            </p>
            <SearchButtons text={selection} onSearch={onSearch} disabled={busy} />
          </div>
        )}
      </div>

      {draft.limitations.length > 0 && (
        <ul className="limitations">
          {draft.limitations.map((l) => (
            <li key={l}>{l}</li>
          ))}
        </ul>
      )}
      {picker}
    </section>
  );
}
