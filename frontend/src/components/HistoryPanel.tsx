import { useEffect, useState } from "react";
import { ApiError, fetchHistory } from "../api";
import type { FactCheckResult, HistoryPage } from "../types";
import { readVerdict } from "../verdict";
import VerdictGauge from "./VerdictGauge";

const CHANNEL_TAG: Record<string, string> = {
  TEXT: "TXT",
  URL: "URL",
  IMAGE: "IMG",
  AUDIO: "AUD",
};

function formatTimestamp(iso: string): string {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

function snippet(text: string, max = 120): string {
  const trimmed = text.trim();
  return trimmed.length > max ? `${trimmed.slice(0, max)}…` : trimmed;
}

export default function HistoryPanel() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<HistoryPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [expandedId, setExpandedId] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchHistory(page)
      .then((result) => {
        if (!cancelled) setData(result);
      })
      .catch((err) => {
        if (cancelled) return;
        // The history API is disabled until accounts exist (it would expose everyone's checks).
        if (err instanceof ApiError && err.status === 404) {
          setUnavailable(true);
        } else {
          setError(err instanceof Error ? err.message : "Failed to load the log.");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [page]);

  function goToPage(next: number) {
    setError(null);
    setPage(next);
  }

  if (unavailable) {
    return (
      <p className="muted">
        Your check history will appear here once accounts are available. Checks aren&apos;t shown publicly.
      </p>
    );
  }

  if (error) {
    return <p className="error-message">{error}</p>;
  }

  if (!data) {
    return <p className="muted">Loading log…</p>;
  }

  if (data.content.length === 0) {
    return <p className="muted">Log is empty — run a check to add the first entry.</p>;
  }

  return (
    <div className="panel">
      <span className="eyebrow">Reading log</span>
      <ul className="history-list">
        {data.content.map((item: FactCheckResult) => {
          const reading = readVerdict(item);
          return (
            <li key={item.id} className="history-item">
              <button
                type="button"
                className="history-row"
                onClick={() => setExpandedId(expandedId === item.id ? null : item.id)}
              >
                <VerdictGauge
                  compact
                  state="settled"
                  verdict={{ classification: item.classification, confidenceScore: item.confidenceScore }}
                />
                <span className="history-channel">{CHANNEL_TAG[item.inputType] ?? item.inputType}</span>
                <span className="history-verdict" style={{ color: `var(${reading.colorVar})` }}>
                  {reading.label}
                </span>
                <span className="history-snippet">{snippet(item.originalInput)}</span>
                <span className="history-time">{formatTimestamp(item.createdAt)}</span>
              </button>
              {expandedId === item.id && <pre className="result-body">{item.fullResponse}</pre>}
            </li>
          );
        })}
      </ul>

      <div className="pagination">
        <button type="button" disabled={page === 0} onClick={() => goToPage(page - 1)}>
          Previous
        </button>
        <span>
          Page {data.number + 1} of {Math.max(data.totalPages, 1)}
        </span>
        <button
          type="button"
          disabled={page + 1 >= data.totalPages}
          onClick={() => goToPage(page + 1)}
        >
          Next
        </button>
      </div>
    </div>
  );
}
