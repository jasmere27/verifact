import { parseVerdict } from "../parseResponse";
import VerdictGauge from "./VerdictGauge";

export default function ResultCard({ response }: { response: string }) {
  const verdict = parseVerdict(response);

  return (
    <div className="result-card">
      <VerdictGauge state="settled" verdict={verdict} />
      <details className="result-detail">
        <summary>Full reading</summary>
        <pre className="result-body">{response}</pre>
      </details>
    </div>
  );
}
