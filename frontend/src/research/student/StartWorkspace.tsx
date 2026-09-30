import { useState } from "react";
import { errorMessage } from "../../api";
import { navigate } from "../../router";
import { createWorkspace } from "./api";

/** The ResearchFact page's main action: topic → a saved Student Research Mode workspace. */
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
        {busy ? "Creating your workspace…" : "Start my research workspace"}
      </button>
      <p className="rf-start-note">Free · no sign-up · deleted after 90 days without changes, or whenever you choose</p>
    </form>
  );
}
