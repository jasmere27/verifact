import { useEffect, useState } from "react";
import { errorMessage } from "../../api";
import { useAuth } from "../../auth/useAuth";
import Link from "../../components/Link";
import { formatDate } from "../../format";
import { listProjects } from "./api";
import type { ProjectSummary } from "./types";
import "./projects.css";

/** The signed-in student's capstone projects, on the ResearchFact page. */
export default function ProjectList() {
  const { session } = useAuth();
  const userId = session?.user.id ?? null;
  const [projects, setProjects] = useState<ProjectSummary[] | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    if (!userId) return;
    listProjects().then(setProjects, (err) => setProblem(errorMessage(err).message));
  }, [userId]);

  if (!userId || (projects !== null && projects.length === 0 && !problem)) return null;
  return (
    <section className="pj-list" aria-labelledby="pj-list-heading">
      <h2 id="pj-list-heading">My capstone projects</h2>
      {problem && <p className="muted small">{problem}</p>}
      {projects === null && !problem && <p className="muted small">Loading…</p>}
      {projects && projects.length > 0 && (
        <ul>
          {projects.map((p) => (
            <li key={p.id}>
              <Link href={`/research/p/${p.id}`} className="pj-list-item">
                <span className="pj-list-title">{p.title}</span>
                <span className="muted small">
                  {p.percent}% done · {p.questions} question{p.questions === 1 ? "" : "s"} · {p.sources} source{p.sources === 1 ? "" : "s"} · updated{" "}
                  {formatDate(p.updatedAt)}
                </span>
                <span className="pj-bar pj-bar--thin" aria-hidden="true">
                  <span style={{ width: `${p.percent}%` }} />
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
