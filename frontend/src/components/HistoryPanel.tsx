import { useEffect, useState } from "react";
import { fetchHistory } from "../api";
import type { FactCheckResult, HistoryPage } from "../types";
import VerdictBadge from "./VerdictBadge";

function formatTimestamp(iso: string): string {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

function snippet(text: string, max = 140): string {
  const trimmed = text.trim();
  return trimmed.length > max ? `${trimmed.slice(0, max)}…` : trimmed;
}

export default function HistoryPanel() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<HistoryPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    fetchHistory(page)
      .then((result) => {
        if (!cancelled) setData(result);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Failed to load history.");
      });
    return () => {
      cancelled = true;
    };
  }, [page]);

  if (error) {
    return <p className="error-message">{error}</p>;
  }

  if (!data) {
    return <p className="muted">Loading history…</p>;
  }

  if (data.content.length === 0) {
    return <p className="muted">No fact-checks yet.</p>;
  }

  return (
    <div className="panel">
      <ul className="history-list">
        {data.content.map((item: FactCheckResult) => (
          <li key={item.id} className="history-item">
            <button
              type="button"
              className="history-row"
              onClick={() => setExpandedId(expandedId === item.id ? null : item.id)}
            >
              <VerdictBadge
                verdict={{
                  classification: item.classification,
                  confidenceScore: item.confidenceScore,
                }}
              />
              <span className="history-type">{item.inputType}</span>
              <span className="history-snippet">{snippet(item.originalInput)}</span>
              <span className="history-time">{formatTimestamp(item.createdAt)}</span>
            </button>
            {expandedId === item.id && <pre className="result-body">{item.fullResponse}</pre>}
          </li>
        ))}
      </ul>

      <div className="pagination">
        <button type="button" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
          Previous
        </button>
        <span>
          Page {data.number + 1} of {Math.max(data.totalPages, 1)}
        </span>
        <button
          type="button"
          disabled={page + 1 >= data.totalPages}
          onClick={() => setPage((p) => p + 1)}
        >
          Next
        </button>
      </div>
    </div>
  );
}
