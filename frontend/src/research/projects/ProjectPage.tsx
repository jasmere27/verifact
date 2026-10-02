import { useEffect, useRef, useState } from "react";
import { errorMessage } from "../../api";
import { useAuth } from "../../auth/useAuth";
import Link from "../../components/Link";
import { formatDate } from "../../format";
import { navigate } from "../../router";
import { apa } from "../student/apa";
import DraftTab from "../student/DraftTab";
import FindSources from "../student/FindSources";
import type { SearchRequest } from "../student/FindSources";
import type { Category } from "../student/types";
import "../student/student.css";
import "./projects.css";
import {
  addToLibrary,
  deleteProject,
  deleteProjectDraft,
  generateProjectInsights,
  getProject,
  removeFromLibrary,
  updateLibraryItem,
  updateProject,
  uploadProjectDraft,
} from "./api";
import type { ItemChanges } from "./api";
import GapsTab from "./GapsTab";
import LibraryTab from "./LibraryTab";
import type { LibraryFilter } from "./LibraryTab";
import NextStepsTab from "./NextStepsTab";
import QuestionsTab from "./QuestionsTab";
import { questionLabel } from "./types";
import type { NextStep, Project, Question } from "./types";

type Tab = "next" | "questions" | "library" | "find" | "gaps" | "draft" | "citations" | "notes";

const CATEGORY_LABEL: Partial<Record<Category, string>> = {
  RRL: "Related literature (RRL)",
  LOCAL: "Local studies",
  FOREIGN: "Foreign studies",
  RECENT: "Recent studies",
  THEORIES: "Theories & frameworks",
};

/** A student's capstone project (ADR-21): signed in, owner only. */
export default function ProjectPage({ id }: { id: string }) {
  const { ready, session } = useAuth();
  const [project, setProject] = useState<Project | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>("next");
  const [filter, setFilter] = useState<LibraryFilter>("all");
  const [request, setRequest] = useState<SearchRequest | null>(null);
  const [status, setStatus] = useState("");
  const [notes, setNotes] = useState("");
  const [copied, setCopied] = useState(false);
  const noteTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const signedIn = Boolean(session);

  useEffect(() => {
    if (!ready || !signedIn) return;
    getProject(id).then(
      (p) => {
        setProject(p);
        setNotes(p.notes ?? "");
      },
      (err) => setError(errorMessage(err).message),
    );
  }, [id, ready, signedIn]);

  useEffect(() => () => {
    if (noteTimer.current) clearTimeout(noteTimer.current);
  }, []);

  if (ready && !signedIn) {
    return (
      <section className="card state-card">
        <h1>Sign in to open your project</h1>
        <p className="muted">Capstone projects are saved to your account, so you can open them on any device.</p>
        <Link href={`/signin?next=${encodeURIComponent(`/research/p/${id}`)}`} className="button button--primary">
          Sign in
        </Link>
      </section>
    );
  }
  if (error) {
    return (
      <section className="card state-card">
        <h1>Project not available</h1>
        <p className="muted">{error}</p>
        <Link href="/research" className="button button--primary">
          Go to ResearchFact
        </Link>
      </section>
    );
  }
  if (!project) {
    return (
      <div className="card state-card" role="status">
        <span className="spinner" aria-hidden="true" />
        <p>Loading your project…</p>
      </div>
    );
  }
  const p = project;

  /** Runs a change and shows the updated project (or the problem). */
  async function apply(work: Promise<Project>, done?: string) {
    try {
      setProject(await work);
      if (done) setStatus(done);
    } catch (err) {
      setStatus(errorMessage(err).message);
    }
  }

  function find(category: Category, label: string, text: string | null = null, questionId: string | null = null) {
    setTab("find");
    setRequest({ category, label, text, questionId, nonce: Date.now() });
  }

  function findFor(q: Question) {
    find("FOR_TEXT", `Studies for ${questionLabel(p.questions, q.id)}`, q.text, q.id);
  }

  function onAction(step: NextStep) {
    switch (step.action) {
      case "ADD_QUESTIONS":
        setTab("questions");
        break;
      case "FIND_SOURCES": {
        const q = step.questionId ? p.questions.find((x) => x.id === step.questionId) : undefined;
        if (q) findFor(q);
        else if (step.category) find(step.category, CATEGORY_LABEL[step.category] ?? "Sources");
        else setTab("find");
        break;
      }
      case "LINK_SOURCES":
        setFilter("unlinked");
        setTab("library");
        break;
      case "OPEN_LIBRARY":
        setFilter("to-read");
        setTab("library");
        break;
      case "REVIEW_RETRACTED":
        setFilter("retracted");
        setTab("library");
        break;
      case "WRITE_GAP":
      case "GENERATE_INSIGHTS":
        setTab("gaps");
        break;
      default:
        setTab("draft");
    }
  }

  function changeNotes(value: string) {
    setNotes(value);
    if (noteTimer.current) clearTimeout(noteTimer.current);
    setStatus("Saving notes…");
    noteTimer.current = setTimeout(() => void apply(updateProject(id, { notes: value }), "Notes saved."), 2000);
  }

  async function remove() {
    if (!window.confirm("Delete this project and everything in it (library, notes, draft)? This can't be undone.")) return;
    try {
      await deleteProject(id);
      navigate("/research");
    } catch (err) {
      setStatus(errorMessage(err).message);
    }
  }

  const citations = p.library.map((i) => apa(i.source)).sort((a, b) => a.localeCompare(b));
  const tabs: { value: Tab; label: string }[] = [
    { value: "next", label: "Next steps" },
    { value: "questions", label: `Questions (${p.questions.length})` },
    { value: "library", label: `Library (${p.library.length})` },
    { value: "find", label: "Find sources" },
    { value: "gaps", label: "Gaps & framework" },
    { value: "draft", label: "My draft" },
    { value: "citations", label: "Citations" },
    { value: "notes", label: "Notes" },
  ];

  return (
    <article className="report st-workspace pj" aria-labelledby="pj-heading">
      <header className="research-hero st-hero">
        <p className="hero-kicker">ResearchFact · Capstone project</p>
        <h1 id="pj-heading">{p.title}</h1>
        <p className="muted small">
          {[p.field, p.country ? `Local studies: ${p.country}` : null, `${p.progress.percent}% of the research checklist done`].filter(Boolean).join(" · ")}
        </p>
        <p className="small st-retention">
          Saved to your account. Kept while you work on it; deleted on {formatDate(p.deletesAt)} only if you make no changes
          for 12 months.
        </p>
      </header>
      <div className="pj-tabs" role="tablist" aria-label="Project">
        {tabs.map((t) => (
          <button key={t.value} type="button" role="tab" className="mode-tab" aria-selected={tab === t.value} onClick={() => setTab(t.value)}>
            {t.label}
          </button>
        ))}
      </div>
      <p className="small pj-status" aria-live="polite">
        {status}
      </p>

      {tab === "next" && <NextStepsTab project={p} onAction={onAction} />}

      {tab === "questions" && (
        <QuestionsTab
          key={p.questions.map((q) => q.id + q.text).join("|")}
          project={p}
          onSave={(rows) => apply(updateProject(id, { questions: rows }), "Questions saved.")}
          onFind={findFor}
          onOpenLibrary={(qid) => {
            setFilter(`rq:${qid}`);
            setTab("library");
          }}
        />
      )}

      {tab === "library" && (
        <LibraryTab
          project={p}
          filter={filter}
          onFilter={setFilter}
          onChange={(key: string, changes: ItemChanges) => void apply(updateLibraryItem(id, key, changes), "Saved.")}
          onRemove={(key) => void apply(removeFromLibrary(id, key), "Removed from your library.")}
        />
      )}

      {tab === "find" && (
        <FindSources
          topic={p.title}
          country={p.country}
          savedKeys={new Set(p.library.map((i) => i.key))}
          canSave
          request={request}
          questions={p.questions.map((q) => ({ id: q.id, label: `Link to ${questionLabel(p.questions, q.id)}` }))}
          onSave={(source, folder, questionId) => void apply(addToLibrary(id, source, folder, questionId), "Saved to your library.")}
        />
      )}

      {tab === "gaps" && (
        <GapsTab
          key={p.gaps.map((g) => g.id + g.statement + g.sourceKeys.join(",")).join("|")}
          project={p}
          onSave={(rows) => apply(updateProject(id, { gaps: rows }), "Gap statements saved.")}
          onGenerate={() => apply(generateProjectInsights(id))}
        />
      )}

      {tab === "draft" && (
        <DraftTab
          draft={p.draft}
          canEdit
          busy={false}
          onUpload={(file) => apply(uploadProjectDraft(id, file), "Draft analysed.")}
          onRemove={() => apply(deleteProjectDraft(id))}
          onSearch={(category, passage, label) => find(category, label, passage)}
        />
      )}

      {tab === "citations" && (
        <section className="report-section" aria-label="Citations">
          {citations.length === 0 ? (
            <p className="pj-empty">Save sources to build your reference list.</p>
          ) : (
            <>
              <ol className="st-refs">
                {citations.map((c) => (
                  <li key={c}>{c}</li>
                ))}
              </ol>
              <div className="row">
                <button
                  type="button"
                  className="button button--secondary button--small"
                  onClick={() => void navigator.clipboard?.writeText(citations.join("\n")).then(() => setCopied(true))}
                >
                  {copied ? "Copied" : "Copy reference list"}
                </button>
              </div>
              <p className="muted small">
                APA 7 from database metadata. Check capitalisation, italics and author names against each paper before submitting.
              </p>
            </>
          )}
        </section>
      )}

      {tab === "notes" && (
        <section className="report-section" aria-label="Notes">
          <label htmlFor="pj-notes" className="st-why-label">
            Research notes, questions for your adviser, ideas
          </label>
          <textarea id="pj-notes" className="text-input" rows={14} maxLength={20000} value={notes} onChange={(e) => changeNotes(e.target.value)} />
        </section>
      )}

      <p className="small">
        <button type="button" className="text-button" onClick={() => void remove()}>
          Delete this project
        </button>
      </p>
    </article>
  );
}
