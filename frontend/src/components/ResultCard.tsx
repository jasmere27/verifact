import { parseVerdict } from "../parseResponse";
import VerdictBadge from "./VerdictBadge";

export default function ResultCard({ response }: { response: string }) {
  const verdict = parseVerdict(response);

  return (
    <div className="result-card">
      <VerdictBadge verdict={verdict} />
      <pre className="result-body">{response}</pre>
    </div>
  );
}
