import { useIntel } from "../context";
import Link from "../../components/Link";
import { ago, useLoad } from "../lib";
import { Empty, HealthBadge, Load, PageHead, Panel } from "../ui";
import MentionsTable from "./MentionsTable";

export default function MonitoringPage() {
  const { api, orgId, href, can } = useIntel();
  const projects = useLoad(() => api.projects(), orgId);
  return (
    <>
      <PageHead
        title="Monitoring"
        sub="Brands and topics watched on a schedule, and the mentions found. Sources are limited to what each project is configured for."
        actions={
          can("ADMIN") && (
            <Link href={href("monitoring", "new")} className="i-btn i-btn--primary">
              New project
            </Link>
          )
        }
      />
      <div className="i-stack">
        <Panel title="Projects" id="projects" tight>
          <Load r={projects}>
            {(ps) =>
              ps.length === 0 ? (
                <Empty
                  title="No monitoring projects"
                  action={
                    can("ADMIN") ? (
                      <Link href={href("monitoring", "new")} className="i-btn i-btn--primary">
                        Set up your first brand
                      </Link>
                    ) : undefined
                  }
                >
                  A project names a brand, its aliases, products and people, the topics you worry about, and which sources to check.
                </Empty>
              ) : (
                <div className="i-table-wrap">
                  <table className="i-table">
                    <thead>
                      <tr>
                        <th>Project</th>
                        <th>Health</th>
                        <th className="hide-sm">Sources</th>
                        <th className="hide-sm">Every</th>
                        <th>Last run</th>
                        <th className="hide-sm">Next run</th>
                      </tr>
                    </thead>
                    <tbody>
                      {ps.map((p) => (
                        <tr key={p.id}>
                          <td>
                            <Link href={href("monitoring", p.id)} className="primary">
                              {p.name}
                            </Link>
                            <div className="muted small">{p.brand}</div>
                          </td>
                          <td>
                            <HealthBadge h={p.health} />
                          </td>
                          <td className="hide-sm small">
                            {[
                              p.config.sources.webSearch && "Web",
                              p.config.sources.newsSearch && "News",
                              p.config.sources.rssFeeds.length > 0 && `${p.config.sources.rssFeeds.length} RSS`,
                              p.config.sources.pages.length > 0 && `${p.config.sources.pages.length} pages`,
                            ]
                              .filter(Boolean)
                              .join(" · ")}
                          </td>
                          <td className="hide-sm num">{p.checkIntervalHours} h</td>
                          <td className="small">{ago(p.lastRunAt)}</td>
                          <td className="hide-sm small">{p.status === "PAUSED" ? "paused" : ago(p.nextRunAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )
            }
          </Load>
        </Panel>
        <Panel title="Mentions" id="mentions" tight>
          <MentionsTable />
        </Panel>
      </div>
    </>
  );
}
