import { useState } from "react";
import { errorMessage } from "../../api";
import { navigate } from "../../router";
import { createWorkspace } from "./api";

/** Entry point on the ResearchFact page: topic → a saved Student Research Mode workspace. */
export default function StartWorkspace() {
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
      const w = await createWorkspace(topic.trim(), field.trim(), country || null);
      navigate(`/research/w/${w.id}`);
    } catch (err) {
      setProblem(errorMessage(err).message);
      setBusy(false);
    }
  }

  return (
    <section className="card st-start" aria-labelledby="st-start-heading">
      <p className="st-start-kicker">Student Research Mode</p>
      <h2 id="st-start-heading">Working on a thesis, capstone or research paper?</h2>
      <p className="muted">
        Start a workspace for your topic. Find real, verifiable sources for your RRL and RRS, local and foreign studies,
        theories and methods, then save and organise them with ready-made APA references.
      </p>
      <form
        className="st-start-form"
        onSubmit={(e) => {
          e.preventDefault();
          void start();
        }}
        noValidate
      >
        <label htmlFor="st-topic" className="st-why-label">
          Research title or topic
        </label>
        <input
          id="st-topic"
          className="text-input"
          value={topic}
          maxLength={300}
          onChange={(e) => setTopic(e.target.value)}
          placeholder="e.g. Effect of flipped classroom on Grade 11 students' mathematics achievement"
        />
        <div className="st-start-row">
          <div>
            <label htmlFor="st-field" className="st-why-label">
              Field (optional)
            </label>
            <input id="st-field" className="text-input" value={field} maxLength={120} onChange={(e) => setField(e.target.value)} placeholder="e.g. Education" />
          </div>
          <div>
            <label htmlFor="st-country" className="st-why-label">
              Local studies from
            </label>
            <select id="st-country" className="st-select" value={country} onChange={(e) => setCountry(e.target.value)}>
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
        <div className="form-actions">
          <button type="submit" className="button button--primary" disabled={busy}>
            {busy ? "Creating…" : "Start my research workspace"}
          </button>
          <span className="muted small">Free, no sign-up. Deleted after 90 days without changes, or whenever you choose.</span>
        </div>
      </form>
    </section>
  );
}
