import { useState } from "react";
import type { FormEvent } from "react";
import { useIntel } from "../context";
import type { MonitoringProject, ProjectInput } from "../types";
import { message, useLoad } from "../lib";
import { Notice } from "../ui";

const split = (s: string) =>
  s
    .split(/[\n,]/)
    .map((x) => x.trim())
    .filter(Boolean);
const lines = (s: string) =>
  s
    .split(/\n/)
    .map((x) => x.trim())
    .filter(Boolean);

const INTERVALS = [1, 2, 6, 12, 24, 72, 168];

/** Create or edit a monitoring project. The server validates everything again, including plan limits. */
export default function ProjectForm({ project, onSaved, onCancel }: { project?: MonitoringProject; onSaved: (p: MonitoringProject) => void; onCancel: () => void }) {
  const { api, orgId, workspace } = useIntel();
  const caps = useLoad(() => api.capabilities(), orgId);
  const plan = workspace?.subscription.plan ?? null;
  const c = project?.config;
  const [name, setName] = useState(project?.name ?? "");
  const [brand, setBrand] = useState(project?.brand ?? "");
  const [aliases, setAliases] = useState(c?.aliases.join(", ") ?? "");
  const [products, setProducts] = useState(c?.products.join(", ") ?? "");
  const [executives, setExecutives] = useState(c?.executives.join(", ") ?? "");
  const [keywords, setKeywords] = useState(c?.keywords.join(", ") ?? "");
  const [topics, setTopics] = useState(c?.topics.join(", ") ?? "");
  const [excluded, setExcluded] = useState(c?.excludedTerms.join(", ") ?? "");
  const [web, setWeb] = useState(c?.sources.webSearch ?? true);
  const [news, setNews] = useState(c?.sources.newsSearch ?? true);
  const [rss, setRss] = useState(c?.sources.rssFeeds.join("\n") ?? "");
  const [pages, setPages] = useState(c?.sources.pages.join("\n") ?? "");
  const [lookback, setLookback] = useState(c?.lookbackDays ?? 7);
  const [threshold, setThreshold] = useState<string>(c?.alertThreshold ?? "HIGH");
  const minInterval = plan?.minCheckIntervalHours ?? 24;
  const [interval, setIntervalHours] = useState(project?.checkIntervalHours ?? Math.max(24, minInterval));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const termCount = [aliases, products, executives, keywords, topics, excluded].reduce((n, s) => n + split(s).length, 0);
  const maxTerms = plan?.maxTermsPerProject ?? 0;
  const searchAvailable = caps.data ? caps.data.sources.WEB_SEARCH !== false : true;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const input: ProjectInput = {
      name,
      brand,
      aliases: split(aliases),
      products: split(products),
      executives: split(executives),
      keywords: split(keywords),
      topics: split(topics),
      excludedTerms: split(excluded),
      webSearch: web,
      newsSearch: news,
      rssFeeds: lines(rss),
      pages: lines(pages),
      lookbackDays: lookback,
      alertThreshold: threshold,
      checkIntervalHours: interval,
    };
    try {
      onSaved(project ? await api.updateProject(project.id, input) : await api.createProject(input));
    } catch (err) {
      setError(message(err));
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="i-stack" noValidate>
      {!workspace?.subscription.entitled && (
        <Notice tone="warn" title="No active plan">
          Projects can be created once the workspace has a plan or trial (see Subscription &amp; usage).
        </Notice>
      )}
      <fieldset className="i-fieldset">
        <legend>Brand</legend>
        <div className="i-form-grid">
          <label className="i-field">
            <span>Brand or company name *</span>
            <input className="i-input" value={brand} onChange={(e) => setBrand(e.target.value)} maxLength={120} required />
          </label>
          <label className="i-field">
            <span>Project name</span>
            <input className="i-input" value={name} onChange={(e) => setName(e.target.value)} maxLength={120} placeholder="Defaults to the brand" />
          </label>
          <TermField label="Aliases and spellings" value={aliases} set={setAliases} hint="e.g. Acme, ACME Corp" />
          <TermField label="Products" value={products} set={setProducts} hint="Names that identify the brand on their own" />
          <TermField label="Executives and spokespeople" value={executives} set={setExecutives} hint="Full names" />
        </div>
      </fieldset>

      <fieldset className="i-fieldset">
        <legend>What you're worried about</legend>
        <div className="i-form-grid">
          <TermField label="Keywords" value={keywords} set={setKeywords} hint="e.g. recall, lawsuit, breach, layoffs" />
          <TermField label="Topics of concern" value={topics} set={setTopics} hint="e.g. food safety, data privacy" />
          <TermField label="Excluded terms" value={excluded} set={setExcluded} hint="Items containing these are ignored (e.g. a namesake company)" />
        </div>
        <p className={`small ${maxTerms && termCount > maxTerms ? "" : "muted"}`} style={maxTerms && termCount > maxTerms ? { color: "var(--i-bad)" } : undefined}>
          {termCount} terms{maxTerms ? ` of ${maxTerms} on the ${plan?.name} plan` : ""}. Separate with commas.
        </p>
      </fieldset>

      <fieldset className="i-fieldset">
        <legend>Sources</legend>
        <label className="i-check">
          <input type="checkbox" checked={web} onChange={(e) => setWeb(e.target.checked)} disabled={!searchAvailable} />
          <span>
            Web search <span className="muted small">— indexed websites, via the configured search provider</span>
          </span>
        </label>
        <label className="i-check">
          <input type="checkbox" checked={news} onChange={(e) => setNews(e.target.checked)} disabled={!searchAvailable} />
          <span>
            News search <span className="muted small">— online news sources</span>
          </span>
        </label>
        {!searchAvailable && <Notice tone="warn">Search isn't configured in this deployment, so web and news search can't run.</Notice>}
        <div className="i-form-grid">
          <label className="i-field">
            <span>Public RSS or Atom feeds</span>
            <textarea className="i-input" value={rss} onChange={(e) => setRss(e.target.value)} placeholder="https://example.com/news/feed.xml" />
            <small>
              One per line{plan ? `, up to ${plan.maxRssFeedsPerProject}` : ""}. Only feeds you're allowed to use commercially; Google News RSS is for
              personal use only and is not supported.
            </small>
          </label>
          <label className="i-field">
            <span>Public pages to watch</span>
            <textarea className="i-input" value={pages} onChange={(e) => setPages(e.target.value)} placeholder="https://reviews.example.com/acme" />
            <small>One per line. Pages behind a login can't be read and are reported as failures.</small>
          </label>
        </div>
        <p className="small muted">Social media platforms aren't monitored: their APIs need separate paid access and approval.</p>
      </fieldset>

      <fieldset className="i-fieldset">
        <legend>Schedule and alerts</legend>
        <div className="i-form-grid">
          <label className="i-field">
            <span>Check every</span>
            <select className="i-input" value={interval} onChange={(e) => setIntervalHours(Number(e.target.value))}>
              {INTERVALS.map((h) => (
                <option key={h} value={h} disabled={h < minInterval}>
                  {h < 24 ? `${h} hours` : h === 24 ? "day" : h === 72 ? "3 days" : "week"}
                  {h < minInterval ? " (higher plan)" : ""}
                </option>
              ))}
            </select>
          </label>
          <label className="i-field">
            <span>Each run looks back</span>
            <select className="i-input" value={lookback} onChange={(e) => setLookback(Number(e.target.value))}>
              <option value={1}>1 day</option>
              <option value={7}>7 days</option>
              <option value={30}>30 days</option>
            </select>
          </label>
          <label className="i-field">
            <span>Open incidents automatically for</span>
            <select className="i-input" value={threshold} onChange={(e) => setThreshold(e.target.value)}>
              <option value="HIGH">High-severity claims only</option>
              <option value="MEDIUM">Medium and high severity</option>
              <option value="NONE">Never (review mentions by hand)</option>
            </select>
            <small>Severity is an AI reading of how damaging a claim would be if believed.</small>
          </label>
        </div>
      </fieldset>

      {error && <Notice tone="bad">{error}</Notice>}
      <div className="i-actions">
        <button type="submit" className="i-btn i-btn--primary" disabled={busy || !brand.trim()}>
          {busy ? "Saving…" : project ? "Save changes" : "Create project"}
        </button>
        <button type="button" className="i-btn" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}

function TermField({ label, value, set, hint }: { label: string; value: string; set: (v: string) => void; hint: string }) {
  return (
    <label className="i-field">
      <span>{label}</span>
      <input className="i-input" value={value} onChange={(e) => set(e.target.value)} />
      <small>{hint}</small>
    </label>
  );
}
