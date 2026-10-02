import { useState } from "react";
import { errorMessage } from "../../api";
import { navigate } from "../../router";
import { useAuth } from "../../auth/useAuth";
import Link from "../../components/Link";
import { createProject, startFromFile } from "../projects/api";
import { createWorkspace } from "./api";

const COUNTRIES: [string, string][] = [
  ["PH", "Philippines"],
  ["", "No local/foreign split"],
  ["ID", "Indonesia"],
  ["MY", "Malaysia"],
  ["IN", "India"],
  ["NG", "Nigeria"],
  ["US", "United States"],
  ["GB", "United Kingdom"],
];

/**
 * The ResearchFact page's main action. Signed in: a capstone project on the account (ADR-21), set up from the
 * student's Chapter 1 or proposal when they have one (ADR-25), else from a typed title. Signed out: a quick
 * workspace kept in this browser.
 */
export default function StartWorkspace() {
  const { session, enabled } = useAuth();
  const signedIn = Boolean(session);
  const [topic, setTopic] = useState("");
  const [field, setField] = useState("");
  const [country, setCountry] = useState("PH");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  // Signed in, starting from a file is the main path; typing a title is the fallback.
  const [mode, setMode] = useState<"file" | "title">("file");
  const [file, setFile] = useState<File | null>(null);
  const fromFile = signedIn && mode === "file";

  async function startWithFile() {
    if (!file) {
      setProblem("Choose your Chapter 1 or proposal (PDF, Word, PowerPoint or text).");
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      const r = await startFromFile(file, country || null, "Chapter 1");
      navigate(`/research/p/${r.project.id}?setup=${r.titleSource.toLowerCase()}`);
    } catch (err) {
      setProblem(errorMessage(err).message);
      setBusy(false);
    }
  }

  async function start() {
    if (fromFile) {
      await startWithFile();
      return;
    }
    if (topic.trim().length < 5) {
      setProblem("Enter your research title or topic.");
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      if (signedIn) {
        const p = await createProject(topic.trim(), field.trim(), country || null);
        navigate(`/research/p/${p.id}`);
      } else {
        const w = await createWorkspace(topic.trim(), field.trim(), country || null);
        navigate(`/research/w/${w.id}`);
      }
    } catch (err) {
      setProblem(errorMessage(err).message);
      setBusy(false);
    }
  }

  return (
    <form
      className="rf-start"
      aria-label="Start a research workspace"
      onSubmit={(e) => {
        e.preventDefault();
        void start();
      }}
      noValidate
    >
      {fromFile ? (
        <>
          <label htmlFor="st-file" className="rf-field-label">
            Upload your Chapter 1 or proposal
          </label>
          <input
            id="st-file"
            type="file"
            className="rf-input rf-file"
            accept=".pdf,.docx,.pptx,.txt,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            aria-invalid={problem ? true : undefined}
          />
          <p className="rf-start-note rf-start-note--left">
            We fill in your title and research questions from it, and point out statements that need a citation. You
            check everything before you continue.{" "}
            <button type="button" className="text-button" onClick={() => setMode("title")}>
              No draft yet? Type your title instead
            </button>
          </p>
        </>
      ) : (
        <>
          <label htmlFor="st-topic" className="rf-field-label">
            Your research title or topic
          </label>
          <input
            id="st-topic"
            className="rf-input rf-input--lg"
            value={topic}
            maxLength={300}
            onChange={(e) => setTopic(e.target.value)}
            placeholder="e.g. Effect of flipped classroom on Grade 11 students' mathematics achievement"
            aria-invalid={problem ? true : undefined}
          />
          {signedIn && (
            <p className="rf-start-note rf-start-note--left">
              <button type="button" className="text-button" onClick={() => setMode("file")}>
                Have a Chapter 1 or proposal? Upload it instead
              </button>
            </p>
          )}
        </>
      )}
      <div className="rf-start-row">
        {!fromFile && (
        <div>
          <label htmlFor="st-field" className="rf-field-label">
            Field <span className="rf-optional">(optional)</span>
          </label>
          <input id="st-field" className="rf-input" value={field} maxLength={120} onChange={(e) => setField(e.target.value)} placeholder="e.g. Education" />
        </div>
        )}
        <div>
          <label htmlFor="st-country" className="rf-field-label">
            Local studies from
          </label>
          <select id="st-country" className="rf-input" value={country} onChange={(e) => setCountry(e.target.value)}>
            {COUNTRIES.map(([code, name]) => (
              <option key={code} value={code}>
                {name}
              </option>
            ))}
          </select>
        </div>
      </div>
      {problem && (
        <p className="field-problem" role="alert">
          {problem}
        </p>
      )}
      <button type="submit" className="button button--primary rf-start-button" disabled={busy}>
        {busy
          ? fromFile
            ? "Reading your file… (about 30–60 seconds)"
            : "Creating…"
          : fromFile
            ? "Set up my project from this file"
            : signedIn
              ? "Start my capstone project"
              : "Start my research workspace"}
      </button>
      {signedIn ? (
        <p className="rf-start-note">Saved to your account · your questions, saved studies, progress and next steps</p>
      ) : (
        <p className="rf-start-note">
          Free · no sign-up · kept 90 days in this browser.
          {enabled && (
            <>
              {" "}
              <Link href="/signin?next=/research">Sign in</Link> to make it a capstone project you can open on any device for a whole
              school year.
            </>
          )}
        </p>
      )}
    </form>
  );
}
