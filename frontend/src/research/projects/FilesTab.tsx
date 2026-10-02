import { useEffect, useRef, useState } from "react";
import { errorMessage } from "../../api";
import { formatDateTime, plural } from "../../format";
import DraftTab from "../student/DraftTab";
import type { Category, Folder, FoundSource } from "../student/types";
import { getProjectFile } from "./api";
import PaperView from "./PaperView";
import { fileName } from "./types";
import type { FileKind, FileSummary, Project, ProjectFile } from "./types";

const ACCEPT = ".pdf,.docx,.pptx,.txt,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.presentationml.presentation,text/plain";
const MAX_BYTES = 10 * 1024 * 1024;

const MATCH_CHIP: Record<NonNullable<FileSummary["match"]>, { label: string; tone: string }> = {
  VERIFIED: { label: "Verified record", tone: "ok" },
  POSSIBLE: { label: "Possible match", tone: "warn" },
  NOT_FOUND: { label: "No record found", tone: "warn" },
  LOOKUP_FAILED: { label: "Not checked", tone: "muted" },
};

/** Chapter drafts and research papers in a project (ADR-23): upload, list, open one. */
export default function FilesTab({
  project,
  openId,
  defaultKind,
  onOpen,
  onUpload,
  onRelabel,
  onDelete,
  onAdd,
  onSearch,
}: {
  project: Project;
  openId: string | null;
  defaultKind: FileKind;
  onOpen: (id: string | null) => void;
  onUpload: (file: File, kind: FileKind, label: string) => Promise<boolean>;
  onRelabel: (id: string, label: string) => void;
  onDelete: (id: string) => Promise<void>;
  onAdd: (source: FoundSource, folder: Folder, questionId: string | null) => void;
  onSearch: (category: Category, text: string, label: string) => void;
}) {
  const [kind, setKind] = useState<FileKind>(defaultKind);
  const [label, setLabel] = useState("");
  const [uploading, setUploading] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  async function upload(file: File | undefined) {
    if (!file) return;
    if (file.size > MAX_BYTES) {
      setProblem("The file is larger than 10 MB.");
      return;
    }
    setUploading(true);
    setProblem(null);
    const ok = await onUpload(file, kind, label);
    setUploading(false);
    if (ok) setLabel("");
    if (input.current) input.current.value = "";
  }

  if (openId) {
    return <FileDetail project={project} id={openId} onBack={() => onOpen(null)} onRelabel={onRelabel} onDelete={onDelete} onAdd={onAdd} onSearch={onSearch} />;
  }

  return (
    <section className="report-section" aria-labelledby="pj-files-heading">
      <h2 id="pj-files-heading">Your chapters and studies</h2>
      <div className="card fl-upload">
        <div className="mode-tabs" role="radiogroup" aria-label="What are you uploading?">
          <button type="button" role="radio" className="mode-tab" aria-checked={kind === "DRAFT"} onClick={() => setKind("DRAFT")}>
            My draft
          </button>
          <button type="button" role="radio" className="mode-tab" aria-checked={kind === "PAPER"} onClick={() => setKind("PAPER")}>
            A research paper
          </button>
        </div>
        <p className="muted small">
          {kind === "DRAFT"
            ? "A chapter of your paper: we find statements that may need citations and check your reference list."
            : "A study you're reading: we find its database record and explain it in plain words, every point backed by the paper's own words."}
        </p>
        <label className="pj-field">
          <span className="st-why-label">Label {kind === "DRAFT" ? "" : "(optional)"}</span>
          <input
            className="text-input"
            value={label}
            maxLength={80}
            enterKeyHint="done"
            placeholder={kind === "DRAFT" ? "e.g. Chapter 2" : "e.g. Reyes 2023"}
            onChange={(e) => setLabel(e.target.value)}
          />
        </label>
        <input ref={input} id="fl-file" type="file" accept={ACCEPT} className="visually-hidden" onChange={(e) => void upload(e.target.files?.[0])} disabled={uploading} />
        <label htmlFor="fl-file" className={`button button--primary fl-pick${uploading ? " is-busy" : ""}`} aria-disabled={uploading}>
          {uploading ? (kind === "DRAFT" ? "Reading your draft… (about 20 seconds)" : "Reading the paper… (about 20–60 seconds)") : "Choose a file"}
        </label>
        <p className="muted small">PDF, Word (.docx), PowerPoint (.pptx) or .txt, up to 10 MB. Only the text is kept, never the file.</p>
        {problem && (
          <p className="field-problem" role="alert">
            {problem}
          </p>
        )}
      </div>

      {project.files.length === 0 ? (
        <p className="pj-empty">No files yet. Upload a chapter draft, or a paper you need to understand.</p>
      ) : (
        <ul className="fl-list">
          {project.files.map((f) => (
            <li key={f.id}>
              <button type="button" className="fl-item" onClick={() => onOpen(f.id)}>
                <span className={`fl-kind fl-kind--${f.kind.toLowerCase()}`}>{f.kind === "DRAFT" ? "Draft" : "Paper"}</span>
                <span className="fl-name">{fileName(f)}</span>
                <span className="fl-chips">
                  {f.kind === "DRAFT" ? (
                    f.needsCitation ? (
                      <span className="st-badge st-badge--warn">{plural(f.needsCitation, "statement")} may need citations</span>
                    ) : (
                      <span className="st-badge st-badge--ok">No uncited claims found</span>
                    )
                  ) : (
                    f.match && <span className={`st-badge st-badge--${MATCH_CHIP[f.match].tone}`}>{MATCH_CHIP[f.match].label}</span>
                  )}
                  {f.kind === "PAPER" && f.findings > 0 && <span className="st-badge st-badge--muted">{plural(f.findings, "finding")}</span>}
                  <span className="muted small">{formatDateTime(f.uploadedAt)}</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function FileDetail({
  project,
  id,
  onBack,
  onRelabel,
  onDelete,
  onAdd,
  onSearch,
}: {
  project: Project;
  id: string;
  onBack: () => void;
  onRelabel: (id: string, label: string) => void;
  onDelete: (id: string) => Promise<void>;
  onAdd: (source: FoundSource, folder: Folder, questionId: string | null) => void;
  onSearch: (category: Category, text: string, label: string) => void;
}) {
  const [file, setFile] = useState<ProjectFile | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    getProjectFile(project.id, id).then(
      (f) => active && setFile(f),
      (err) => active && setProblem(errorMessage(err).message),
    );
    return () => {
      active = false;
    };
  }, [project.id, id]);

  async function remove() {
    if (!window.confirm("Delete this file and its analysis from your project?")) return;
    await onDelete(id);
    onBack();
  }

  return (
    <section className="report-section fl-detail" aria-label="File">
      <button type="button" className="text-button fl-back" onClick={onBack}>
        ← All files
      </button>
      {problem && <p className="alert">{problem}</p>}
      {!file && !problem && (
        <div className="card state-card" role="status">
          <span className="spinner" aria-hidden="true" />
          <p>Opening…</p>
        </div>
      )}
      {file && (
        <>
          <div className="fl-head">
            <label className="pj-field">
              <span className="st-why-label">Label</span>
              <input
                className="text-input"
                maxLength={80}
                defaultValue={file.label ?? ""}
                placeholder={file.kind === "DRAFT" ? "e.g. Chapter 2" : "e.g. Reyes 2023"}
                onBlur={(e) => e.target.value.trim() !== (file.label ?? "") && onRelabel(id, e.target.value.trim())}
              />
            </label>
            <p className="muted small">
              {file.fileName} · {file.docKind}
              {file.pages ? ` · ${plural(file.pages, file.docKind === "PPTX" ? "slide" : "page")}` : ""} · uploaded {formatDateTime(file.uploadedAt)}
            </p>
          </div>
          {file.kind === "DRAFT" && file.draft && (
            <DraftTab draft={file.draft} canEdit={false} busy={false} onUpload={async () => {}} onRemove={async () => {}} onSearch={onSearch} />
          )}
          {file.kind === "PAPER" && file.paper && <PaperView project={project} paper={file.paper} onAdd={onAdd} onSearch={onSearch} />}
          <p className="small">
            <button type="button" className="text-button" onClick={() => void remove()}>
              Delete this file
            </button>
          </p>
        </>
      )}
    </section>
  );
}
