import { useEffect, useRef, useState } from "react";
import { errorMessage } from "../../api";
import { useAuth } from "../../auth/useAuth";
import Link from "../../components/Link";
import { formatDate } from "../../format";
import { navigate } from "../../router";
import { apa } from "../student/apa";
import FindSources from "../student/FindSources";
import type { SearchRequest } from "../student/FindSources";
import type { Category } from "../student/types";
import "../student/student.css";
import "./projects.css";
import {
  addToLibrary,
  deleteProject,
  deleteProjectFile,
  generateProjectInsights,
  getProject,
  relabelProjectFile,
  removeFromLibrary,
  reviewLink,
  suggestLinks,
  updateLibraryItem,
  updateProject,
  uploadProjectFile,
} from "./api";
import FilesTab from "./FilesTab";
import type { ItemChanges } from "./api";
import GapsTab from "./GapsTab";
import LibraryTab from "./LibraryTab";
import type { LibraryFilter } from "./LibraryTab";
import HomeTab from "./HomeTab";
import Tour from "./Tour";
import { tourSeen } from "./tourStorage";
import type { TourStep } from "./Tour";
import type { TitleSource } from "./api";
import QuestionsTab from "./QuestionsTab";
import type { FindKind } from "./QuestionsTab";
import { questionLabel } from "./types";
import type { FileKind, NextStep, Project, Question } from "./types";

type Tab = "home" | "sources" | "paper" | "notes";
/** Sources holds what used to be four tabs. */
type SourcesView = "questions" | "saved" | "find" | "gaps";

const TITLE_SOURCES: TitleSource[] = ["DOCUMENT", "SUGGESTED", "NONE"];

/** `?setup=document` after "Start from your Chapter 1": show the check-what-we-found card once. */
function setupFromUrl(): TitleSource | null {
  const value = new URLSearchParams(window.location.search).get("setup")?.toUpperCase();
  return TITLE_SOURCES.find((t) => t === value) ?? null;
}

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
  const [tab, setTab] = useState<Tab>("home");
  const [view, setView] = useState<SourcesView>("questions");
  const [setup, setSetup] = useState<TitleSource | null>(setupFromUrl);
  const [touring, setTouring] = useState(() => !tourSeen());
  const [filter, setFilter] = useState<LibraryFilter>("all");
  const [request, setRequest] = useState<SearchRequest | null>(null);
  const [openFile, setOpenFile] = useState<string | null>(null);
  const [uploadKind, setUploadKind] = useState<FileKind>("DRAFT");
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

  // A new tab or view starts at its top. The tabs are sticky, so measure a plain marker placed just above them.
  const tabsRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = tabsRef.current;
    if (el && el.getBoundingClientRect().top < 0) el.scrollIntoView({ block: "start" });
  }, [tab, view]);

  // The setup flag is for this visit only; keep the address clean (and Back/refresh from showing it again).
  useEffect(() => {
    if (window.location.search) navigate(`/research/p/${id}`, { replace: true });
  }, [id]);

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

  function sources(next: SourcesView) {
    setTab("sources");
    setView(next);
  }

  function find(category: Category, label: string, text: string | null = null, questionId: string | null = null) {
    sources("find");
    setRequest({ category, label, text, questionId, nonce: Date.now() });
  }

  function findFor(q: Question, kind: FindKind = "studies") {
    const rq = questionLabel(p.questions, q.id);
    if (kind === "supporting" && q.hypothesis) find("SUPPORTING", `Studies that support your expected answer to ${rq}`, q.hypothesis, q.id);
    else if (kind === "conflicting" && q.hypothesis) find("CONTRADICTING", `Studies that point the other way on ${rq}`, q.hypothesis, q.id);
    else find("FOR_TEXT", `Studies for ${rq}`, q.text, q.id);
  }

  /** Opens the Questions tab at the suggested links. */
  function openLinks() {
    sources("questions");
    requestAnimationFrame(() => {
      const el = document.getElementById("pj-links");
      el?.scrollIntoView({ block: "start" });
      el?.focus({ preventScroll: true });
    });
  }

  function onAction(step: NextStep) {
    switch (step.action) {
      case "ADD_QUESTIONS":
        sources("questions");
        break;
      case "FIND_SOURCES": {
        const q = step.questionId ? p.questions.find((x) => x.id === step.questionId) : undefined;
        if (q) findFor(q);
        else if (step.category) find(step.category, CATEGORY_LABEL[step.category] ?? "Sources");
        else sources("find");
        break;
      }
      case "LINK_SOURCES":
      case "REVIEW_LINKS":
        openLinks();
        break;
      case "OPEN_LIBRARY":
        setFilter("to-read");
        sources("saved");
        break;
      case "REVIEW_RETRACTED":
        setFilter("retracted");
        sources("saved");
        break;
      case "WRITE_GAP":
      case "GENERATE_INSIGHTS":
        sources("gaps");
        break;
      case "UPLOAD_DRAFT":
        setOpenFile(null);
        setUploadKind("DRAFT");
        setTab("paper");
        break;
      default:
        // Draft claims, reference checks, unsaved papers: open the file the step is about.
        setOpenFile(step.fileId);
        setTab("paper");
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
    { value: "home", label: "Home" },
    { value: "sources", label: "Sources" },
    { value: "paper", label: "My paper" },
    { value: "notes", label: "Notes" },
  ];
  const views: { value: SourcesView; label: string }[] = [
    { value: "questions", label: "By question" },
    { value: "saved", label: `Saved (${p.library.length})` },
    { value: "find", label: "Find studies" },
    { value: "gaps", label: "Not yet studied" },
  ];
  const tourSteps: TourStep[] = [
    ...(setup
      ? [{ target: "setup", title: "First, check what we found", body: "We read your file and filled in your title and research questions. Fix anything that's not right, then tap Looks right." }]
      : []),
    { target: "next", title: "Start here", body: "This card always shows the one thing to do next. Tap its button and we'll take you straight there." },
    { target: "tab-sources", title: "Sources", body: "Find studies for each research question, save the good ones, and see which question each one answers." },
    { target: "tab-paper", title: "My paper", body: "Upload your chapters. We point out statements that need a citation and check your reference list." },
    { target: "progress", title: "Your progress", body: "Every step you finish fills this up. Everything saves to your account automatically." },
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
      <div className="pj-tabs-anchor" ref={tabsRef} aria-hidden="true" />
      <div className="pj-tabs" role="tablist" aria-label="Project">
        {tabs.map((t) => (
          <button
            key={t.value}
            type="button"
            role="tab"
            className="mode-tab"
            aria-selected={tab === t.value}
            data-tour={`tab-${t.value}`}
            onClick={() => setTab(t.value)}
          >
            {t.label}
          </button>
        ))}
      </div>
      <p className="small pj-status" aria-live="polite">
        {status}
      </p>

      {tab === "home" && (
        <HomeTab
          project={p}
          setup={setup}
          onSetupDone={() => setSetup(null)}
          onRename={(title) => apply(updateProject(id, { title }), "Title saved.")}
          onEditQuestions={() => {
            setSetup(null);
            sources("questions");
          }}
          onAction={onAction}
        />
      )}

      {tab === "sources" && (
        <div className="pj-views" role="group" aria-label="Sources">
          {views.map((v) => (
            <button key={v.value} type="button" className="pj-view" aria-pressed={view === v.value} onClick={() => setView(v.value)}>
              {v.label}
            </button>
          ))}
        </div>
      )}

      {tab === "sources" && view === "questions" && (
        <QuestionsTab
          key={p.questions.map((q) => q.id + q.text + (q.hypothesis ?? "")).join("|")}
          project={p}
          onSave={(rows) => apply(updateProject(id, { questions: rows }), "Questions saved.")}
          onFind={findFor}
          onSuggest={async () => {
            try {
              setStatus("");
              const r = await suggestLinks(id);
              setProject(r.project);
              return r;
            } catch (err) {
              setStatus(errorMessage(err).message);
              return null;
            }
          }}
          onReview={(key, qid, accept) => apply(reviewLink(id, key, qid, accept), accept ? "Linked." : "Got it, we won't suggest that one again.")}
          onOpenLibrary={(qid) => {
            setFilter(`rq:${qid}`);
            sources("saved");
          }}
        />
      )}

      {tab === "sources" && view === "saved" && (
        <LibraryTab
          project={p}
          filter={filter}
          onFilter={setFilter}
          onChange={(key: string, changes: ItemChanges) => void apply(updateLibraryItem(id, key, changes), "Saved.")}
          onRemove={(key) => void apply(removeFromLibrary(id, key), "Removed from your library.")}
        />
      )}

      {tab === "sources" && view === "find" && (
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

      {tab === "sources" && view === "gaps" && (
        <GapsTab
          key={p.gaps.map((g) => g.id + g.statement + g.sourceKeys.join(",")).join("|")}
          project={p}
          onSave={(rows) => apply(updateProject(id, { gaps: rows }), "Gap statements saved.")}
          onGenerate={() => apply(generateProjectInsights(id))}
        />
      )}

      {tab === "paper" && (
        <FilesTab
          project={p}
          openId={openFile}
          defaultKind={uploadKind}
          onOpen={setOpenFile}
          onUpload={async (file, kind, label) => {
            try {
              setStatus("");
              const next = await uploadProjectFile(id, file, kind, label);
              setProject(next);
              setStatus(kind === "DRAFT" ? "Draft analysed." : "Paper read.");
              setOpenFile(next.files[0]?.id ?? null); // newest first: open what was just uploaded
              return true;
            } catch (err) {
              setStatus(errorMessage(err).message);
              return false;
            }
          }}
          onRelabel={(fid, label) => void apply(relabelProjectFile(id, fid, label), "Saved.")}
          onDelete={(fid) => apply(deleteProjectFile(id, fid), "File deleted.")}
          onAdd={(source, folder, questionId) => void apply(addToLibrary(id, source, folder, questionId), "Added to your library.")}
          onSearch={(category, passage, label) => find(category, label, passage)}
        />
      )}

      {tab === "paper" && !openFile && (
        <section className="report-section" aria-labelledby="pj-refs-heading">
          <h2 id="pj-refs-heading">Your reference list</h2>
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

      <p className="small pj-foot">
        <button
          type="button"
          className="text-button"
          onClick={() => {
            setTab("home");
            setTouring(true);
          }}
        >
          Show me around
        </button>
        <button type="button" className="text-button" onClick={() => void remove()}>
          Delete this project
        </button>
      </p>

      {touring && (
        <Tour
          steps={tourSteps}
          onClose={() => {
            setTouring(false);
            setTab("home");
          }}
        />
      )}
    </article>
  );
}
