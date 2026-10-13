import { useState } from "react";
import { useIntel } from "../context";
import Link from "../../components/Link";
import { fmtDate, humanize, useLoad } from "../lib";
import { Badge, Empty, ExternalLink, Load, PageHead, Pager, Panel } from "../ui";

export default function EvidencePage() {
  const { api, orgId, href } = useIntel();
  const [q, setQ] = useState("");
  const [query, setQuery] = useState("");
  const [direction, setDirection] = useState("");
  const [sourceType, setSourceType] = useState("");
  const [page, setPage] = useState(0);
  const r = useLoad(() => api.evidence({ q: query, direction, sourceType, page, size: 25 }), [orgId, query, direction, sourceType, page].join("|"));
  return (
    <>
      <PageHead
        title="Evidence Library"
        sub="Every source your investigations retrieved, with the source's own words where an assessment relied on them. Latest version of each investigation."
      />
      <Panel tight>
        <form
          className="i-filters"
          role="search"
          onSubmit={(e) => {
            e.preventDefault();
            setQuery(q);
            setPage(0);
          }}
        >
          <label className="i-field grow">
            <span>Search title, site or excerpt</span>
            <input className="i-input" value={q} onChange={(e) => setQ(e.target.value)} />
          </label>
          <label className="i-field">
            <span>Direction</span>
            <select className="i-input" value={direction} onChange={(e) => { setDirection(e.target.value); setPage(0); }}>
              <option value="">Any</option>
              <option value="SUPPORTS">Supports a claim</option>
              <option value="CONTRADICTS">Contradicts a claim</option>
              <option value="MIXED">Both</option>
              <option value="CONTEXT">Context only</option>
            </select>
          </label>
          <label className="i-field">
            <span>Source type</span>
            <select className="i-input" value={sourceType} onChange={(e) => { setSourceType(e.target.value); setPage(0); }}>
              <option value="">Any</option>
              {["NEWS", "GOVERNMENT", "FACT_CHECKER", "ACADEMIC", "REFERENCE", "SOCIAL", "OTHER"].map((t) => (
                <option key={t} value={t}>
                  {humanize(t)}
                </option>
              ))}
            </select>
          </label>
          <button type="submit" className="i-btn">
            Search
          </button>
        </form>
        <Load r={r}>
          {(p) =>
            p.items.length === 0 ? (
              <Empty title="No evidence yet">Sources appear here as investigations complete.</Empty>
            ) : (
              <>
                <ul className="i-list">
                  {p.items.map((e) => (
                    <li key={e.id}>
                      <div className="i-row">
                        <ExternalLink href={e.url}>
                          <strong>{e.title || e.domain}</strong>
                        </ExternalLink>
                        <Badge tone={e.direction === "SUPPORTS" ? "ok" : e.direction === "CONTRADICTS" ? "bad" : e.direction === "MIXED" ? "warn" : "neutral"}>
                          {e.direction === "CONTEXT" ? "Context" : humanize(e.direction)}
                        </Badge>
                      </div>
                      <div className="i-meta">
                        <span>{e.domain}</span>
                        <span>{humanize(e.sourceType)}</span>
                        <span>published {e.publishedDate ?? "date unknown"}</span>
                        <span>retrieved {fmtDate(e.retrievedAt)}</span>
                        <span>
                          in <Link href={href("investigations", e.investigationId)}>{e.investigationTitle}</Link> (v{e.version}, {e.sourceRef})
                        </span>
                      </div>
                      {e.excerpt ? (
                        <div className={`i-quote ${e.direction === "CONTRADICTS" ? "i-quote--contradicts" : "i-quote--supports"}`} style={{ marginTop: 6 }}>
                          <span className="i-kind">Source's words (verified)</span>
                          <blockquote className="small">“{e.excerpt}”</blockquote>
                        </div>
                      ) : (
                        e.snippet && <p className="small muted" style={{ marginTop: 4 }}>{e.snippet}</p>
                      )}
                    </li>
                  ))}
                </ul>
                <Pager page={p.page} size={p.size} total={p.total} onPage={setPage} />
              </>
            )
          }
        </Load>
      </Panel>
    </>
  );
}
