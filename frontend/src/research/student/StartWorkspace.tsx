import { useState } from "react";
import { errorMessage } from "../../api";
import { navigate } from "../../router";
import { useAuth } from "../../auth/useAuth";
import Link from "../../components/Link";
import { createProject } from "../projects/api";
import { createWorkspace } from "./api";

/**
 * The ResearchFact page's main action: topic → signed in, a capstone project on the account (ADR-21); signed out,
 * a quick workspace kept in this browser.
 */
export default function StartWorkspace() {
  const { session, enabled } = useAuth();
  const signedIn = Boolean(session);
  const [topic, setTopic] = useState("");
  const [field, setField] = useState("");
  const [country, setCountry] = useState("PH");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  async function start() {
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
      <div className="rf-start-row">
        <div>
          <label htmlFor="st-field" className="rf-field-label">
            Field <span className="rf-optional">(optional)</span>
          </label>
          <input id="st-field" className="rf-input" value={field} maxLength={120} onChange={(e) => setField(e.target.value)} placeholder="e.g. Education" />
        </div>
        <div>
          <label htmlFor="st-country" className="rf-field-label">
            Local studies from
          </label>
          <select id="st-country" className="rf-input" value={country} onChange={(e) => setCountry(e.target.value)}>
            <option value="PH">Philippines</option>
            <option value="">No local/foreign split</option>
            <option value="ID">Indonesia</option>
            <option value="MY">Malaysia</option>
            <option value="IN">India</option>
            <option value="NG">Nigeria</option>
            <option value="US">United States</option>
            <option value="GB">United Kingdom</option>
          </select>
        </div>
      </div>
      {problem && (
        <p className="field-problem" role="alert">
          {problem}
        </p>
      )}
      <button type="submit" className="button button--primary rf-start-button" disabled={busy}>
        {busy ? "Creating…" : signedIn ? "Start my capstone project" : "Start my research workspace"}
      </button>
      {signedIn ? (
        <p className="rf-start-note">Saved to your account · research questions, evidence library, progress and next steps</p>
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
