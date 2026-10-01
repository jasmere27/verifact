import { useState } from "react";
import { formatDateTime } from "../format";
import type { RecentCheck } from "../recent";
import { reportPath } from "../router";
import Link from "./Link";
import VerdictBadge from "./VerdictBadge";

/** Signed out: this browser's last checks (localStorage). Signed in: the account's history from the server. */
type Props =
  | { mode: "browser"; items: RecentCheck[]; onClear: () => void }
  | { mode: "account"; items: RecentCheck[] | null; error: string | null; onRemove: (id: string) => void };

const SHOWN = 10;

export default function RecentChecks(props: Props) {
  const [showAll, setShowAll] = useState(false);
  const account = props.mode === "account";
  const items = props.items ?? [];
  const shown = showAll ? items : items.slice(0, SHOWN);

  return (
    <section className="recent" aria-labelledby="recent-heading">
      <div className="recent-head">
        <h2 id="recent-heading">{account ? "Your checks" : "Recent checks"}</h2>
        {props.mode === "browser" && items.length > 0 && (
          <button type="button" className="text-button" onClick={props.onClear}>
            Clear
          </button>
        )}
      </div>
      <p className="muted small">
        {account
          ? "Saved to your account, so you can see them on any device where you sign in. Only you can see this list. Reports are deleted after 90 days."
          : "Saved only in this browser, so anyone using it can see this list. On a shared computer, clear it when you're done."}
      </p>
      {props.mode === "account" && props.error ? (
        <p className="recent-empty" role="alert">
          {props.error}
        </p>
      ) : account && props.items === null ? (
        <p className="recent-empty" role="status">
          Loading your checks…
        </p>
      ) : items.length === 0 ? (
        <p className="recent-empty">
          {account ? "Checks you run while signed in will appear here." : "Your last 10 reports will appear here so you can reopen them."}
        </p>
      ) : (
        <>
          <ul className="recent-list">
            {shown.map((item) => (
              <li key={item.id} className={account ? "recent-row" : undefined}>
                <Link href={reportPath(item.id)} className="recent-item">
                  <span className="recent-label">{item.label}</span>
                  <span className="recent-meta">
                    <VerdictBadge verdict={item.overallVerdict} size="sm" />
                    <time dateTime={item.createdAt}>{formatDateTime(item.createdAt)}</time>
                  </span>
                </Link>
                {props.mode === "account" && (
                  <button
                    type="button"
                    className="text-button recent-remove"
                    onClick={() => props.onRemove(item.id)}
                    aria-label={`Remove “${item.label}” from your checks`}
                  >
                    Remove
                  </button>
                )}
              </li>
            ))}
          </ul>
          {items.length > SHOWN && (
            <button type="button" className="text-button" onClick={() => setShowAll((v) => !v)}>
              {showAll ? "Show fewer" : `Show all ${items.length}`}
            </button>
          )}
        </>
      )}
    </section>
  );
}
