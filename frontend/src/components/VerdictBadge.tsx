import type { ParsedVerdict } from "../parseResponse";

const LABELS: Record<string, string> = {
  real: "Real",
  fake: "Fake",
  mixed: "Mixed",
  unverified: "Unverified",
};

export default function VerdictBadge({ verdict }: { verdict: ParsedVerdict }) {
  if (!verdict.classification) {
    return null;
  }
  const key = verdict.classification.toLowerCase();
  const label = LABELS[key] ?? verdict.classification;

  return (
    <div className="verdict-badge" data-verdict={key}>
      <span className="verdict-label">{label}</span>
      {verdict.confidenceScore !== null && (
        <span className="verdict-confidence">{verdict.confidenceScore}% confidence</span>
      )}
    </div>
  );
}
