import { useEffect, useState } from "react";
import { ApiError } from "../../api";
import Link from "../../components/Link";
import { formatRelative } from "../../format";
import { forgetWorkspace, getWorkspace, savedWorkspaceIds } from "./api";
import type { Workspace } from "./types";

/** Workspaces created in this browser, newest change first, so students can pick up where they left off. */
export default function RecentWorkspaces() {
  const [items, setItems] = useState<Workspace[]>([]);
  const [now] = useState(() => Date.now());

  useEffect(() => {
    const ids = savedWorkspaceIds().slice(0, 12);
    if (ids.length === 0) return;
    let cancelled = false;
    Promise.all(
      ids.map((id) =>
        getWorkspace(id).catch((err: unknown) => {
          if (err instanceof ApiError && err.status === 404) forgetWorkspace(id);
          return null;
        }),
      ),
    ).then((found) => {
      if (cancelled) return;
      setItems(
        found
          .filter((w): w is Workspace => w !== null)
          .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
          .slice(0, 5),
      );
    });
    return () => {
      cancelled = true;
    };
  }, []);

  if (items.length === 0) return null;
  return (
    <section className="rf-recent" aria-labelledby="rf-recent-heading">
      <h2 id="rf-recent-heading" className="rf-section-label">
        Continue your research
      </h2>
      <ul>
        {items.map((w) => (
          <li key={w.id}>
            <Link href={`/research/w/${w.id}`} className="rf-recent-item">
              <span className="rf-recent-topic">{w.topic}</span>
              <span className="rf-recent-meta">
                {w.sources.length} saved source{w.sources.length === 1 ? "" : "s"}
                {w.draft ? " · draft uploaded" : ""} · updated {formatRelative(w.updatedAt, now)}
              </span>
              <span className="rf-recent-arrow" aria-hidden="true">
                →
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
