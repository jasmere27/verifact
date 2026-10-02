import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import { getMyChecks } from "../auth/api";
import { shownName, useAuth } from "../auth/useAuth";
import Link from "../components/Link";
import VerdictBadge from "../components/VerdictBadge";
import { formatRelative } from "../format";
import { useInstall } from "../install";
import { getNewsWorkspace, savedReviewIds } from "../news/api";
import { listProjects } from "../research/projects/api";
import type { ProjectSummary } from "../research/projects/types";
import { getWorkspace, savedWorkspaceIds } from "../research/student/api";
import { loadRecent } from "../recent";
import { reportPath } from "../router";
import type { OverallVerdict } from "../types";
import "./home.css";

interface Recent {
  id: string;
  label: string;
  verdict: OverallVerdict;
  at: string;
}

interface Continue {
  href: string;
  title: string;
  meta: string;
  at: string;
  percent?: number;
}

const QUICK: { href: string; label: string; hint: string; tone: string }[] = [
  { href: "/check", label: "Check a post", hint: "Text, link or screenshot", tone: "check" },
  { href: "/research", label: "Research", hint: "Capstone & sources", tone: "research" },
  { href: "/news", label: "Review a story", hint: "Claim by claim", tone: "news" },
  { href: "/legal", label: "Legal sources", hint: "Official sources", tone: "legal" },
];

/**
 * Home on a phone: the student's own work first (quick actions, what to continue, recent checks) instead of
 * the marketing page, which stays at /about. Everything here comes from the account or this browser.
 */
export default function MobileHome() {
  const { session, account } = useAuth();
  const { installed } = useInstall();
  const userId = session?.user.id ?? null;
  const [now] = useState(() => Date.now());
  const [accountRecent, setAccountRecent] = useState<Recent[] | null>(null);
  const [accountProjects, setAccountProjects] = useState<ProjectSummary[]>([]);
  // Signed out, recent checks come from this browser (read once, during render).
  const [localRecent] = useState<Recent[]>(() =>
    loadRecent().slice(0, 5).map((c) => ({ id: c.id, label: c.label, verdict: c.overallVerdict, at: c.createdAt })),
  );
  const [work, setWork] = useState<Continue[]>([]);

  // Signed in: the account's check history and capstone projects.
  useEffect(() => {
    if (!userId) return;
    let active = true;
    getMyChecks().then(
      (items) =>
        active && setAccountRecent(items.slice(0, 5).map((c) => ({ id: c.id, label: c.label, verdict: c.overallVerdict as OverallVerdict, at: c.checkedAt }))),
      () => active && setAccountRecent([]),
    );
    listProjects().then((p) => active && setAccountProjects(p.slice(0, 3)), () => {});
    return () => {
      active = false;
    };
  }, [userId]);
  const recent = userId ? accountRecent : localRecent;
  const projects = userId ? accountProjects : [];

  // Workspaces and NewsFact reviews made in this browser.
  useEffect(() => {
    let active = true;
    const workspaces = savedWorkspaceIds().slice(0, 6).map((id) =>
      getWorkspace(id).then(
        (w): Continue => ({ href: `/research/w/${w.id}`, title: w.topic, meta: `Research workspace · ${w.sources.length} sources`, at: w.updatedAt }),
        () => null,
      ),
    );
    const reviews = savedReviewIds().slice(0, 6).map((id) =>
      getNewsWorkspace(id).then(
        (n): Continue => ({
          href: `/news/${n.check.id}`,
          title: n.check.articleTitle || n.check.claims[0]?.claim || "News review",
          meta: `NewsFact review · ${n.check.claims.length} claims`,
          at: n.review.updatedAt ?? n.check.createdAt,
        }),
        () => null,
      ),
    );
    Promise.all([...workspaces, ...reviews]).then((items) => {
      if (!active) return;
      setWork(items.filter((x): x is Continue => x !== null).sort((a, b) => b.at.localeCompare(a.at)).slice(0, 4));
    });
    return () => {
      active = false;
    };
  }, []);

  const continueItems: Continue[] = [
    ...projects.map((p) => ({
      href: `/research/p/${p.id}`,
      title: p.title,
      meta: `Capstone project · ${p.percent}% done`,
      at: p.updatedAt,
      percent: p.percent,
    })),
    ...work,
  ];
  const firstTime = recent !== null && recent.length === 0 && continueItems.length === 0;
  const name = session ? shownName(account, session) : null;

  return (
    <div className="m-home">
      <header className="m-home-head">
        <h1>{name ? `Hi, ${name}` : "Saw something viral?"}</h1>
        <p className="muted">{name ? "Pick up where you left off, or check something new." : "Check it against real sources before you share it."}</p>
      </header>

      <section aria-label="Quick actions">
        <ul className="m-quick">
          {QUICK.map((q) => (
            <li key={q.href}>
              <Link href={q.href} className={`m-quick-item m-quick-item--${q.tone}`}>
                <strong>{q.label}</strong>
                <span>{q.hint}</span>
              </Link>
            </li>
          ))}
        </ul>
      </section>

      {continueItems.length > 0 && (
        <Section title="Continue">
          <ul className="m-list">
            {continueItems.map((c) => (
              <li key={c.href}>
                <Link href={c.href} className="m-row">
                  <span className="m-row-title">{c.title}</span>
                  <span className="m-row-meta">
                    {c.meta} · {formatRelative(c.at, now)}
                  </span>
                  {c.percent !== undefined && (
                    <span className="pj-bar pj-bar--thin" aria-hidden="true">
                      <span style={{ width: `${c.percent}%` }} />
                    </span>
                  )}
                </Link>
              </li>
            ))}
          </ul>
        </Section>
      )}

      {recent && recent.length > 0 && (
        <Section title={userId ? "Your recent checks" : "Recent checks on this phone"} action={<Link href="/check">All</Link>}>
          <ul className="m-list">
            {recent.map((r) => (
              <li key={r.id}>
                <Link href={reportPath(r.id)} className="m-row">
                  <span className="m-row-title">{r.label}</span>
                  <span className="m-row-meta m-row-meta--badge">
                    <VerdictBadge verdict={r.verdict} size="sm" /> {formatRelative(r.at, now)}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </Section>
      )}

      {firstTime && (
        <section className="m-intro">
          <p>
            VeriFact searches fact-checkers, news and reference sources and shows you what they say, with links, so you can
            decide for yourself.
          </p>
          <Link href="/about" className="button button--secondary button--small">
            How it works
          </Link>
        </section>
      )}

      {!installed && (
        <Link href="/install" className="m-install">
          <img src="/icon-192.png" width={40} height={40} alt="" />
          <span>
            <strong>Get the app</strong>
            <span className="muted small">Add VeriFact to your home screen. Free, no app store.</span>
          </span>
        </Link>
      )}
    </div>
  );
}

function Section({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section className="m-section">
      <div className="m-section-head">
        <h2>{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}
