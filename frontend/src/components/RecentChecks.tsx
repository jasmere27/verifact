import { formatDateTime } from "../format";
import type { RecentCheck } from "../recent";
import { reportPath } from "../router";
import Link from "./Link";
import VerdictBadge from "./VerdictBadge";

interface Props {
  items: RecentCheck[];
  onClear: () => void;
}

export default function RecentChecks({ items, onClear }: Props) {
  return (
    <section className="recent" aria-labelledby="recent-heading">
      <div className="recent-head">
        <h2 id="recent-heading">Recent checks</h2>
        {items.length > 0 && (
          <button type="button" className="text-button" onClick={onClear}>
            Clear
          </button>
        )}
      </div>
      <p className="muted small">Saved only in this browser. Nobody else can see this list.</p>
      {items.length === 0 ? (
        <p className="recent-empty">Your last 10 reports will appear here so you can reopen them.</p>
      ) : (
        <ul className="recent-list">
          {items.map((item) => (
            <li key={item.id}>
              <Link href={reportPath(item.id)} className="recent-item">
                <span className="recent-label">{item.label}</span>
                <span className="recent-meta">
                  <VerdictBadge verdict={item.overallVerdict} size="sm" />
                  <time dateTime={item.createdAt}>{formatDateTime(item.createdAt)}</time>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
