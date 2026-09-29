/** Display helpers. Every value here comes from the API and is treated as plain text. */

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });
const dateOnly = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });
const dateOnlyUtc = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeZone: "UTC" });

/** ISO instant → local date and time; falls back to the raw string. */
export function formatDateTime(iso: string): string {
  const time = Date.parse(iso);
  return Number.isNaN(time) ? iso : dateTime.format(time);
}

export function formatDate(iso: string): string {
  const time = Date.parse(iso);
  return Number.isNaN(time) ? iso : dateOnly.format(time);
}

/**
 * Published dates vary by provider ("2024-05-01", ISO instants, RFC 1123 strings, free text).
 * Plain calendar dates are formatted in UTC so they don't shift a day in western time zones.
 */
export function formatPublishedDate(value: string | null): string {
  if (!value || !value.trim()) return "Date unknown";
  const trimmed = value.trim();
  const time = Date.parse(trimmed);
  if (Number.isNaN(time)) return trimmed;
  return /^\d{4}-\d{2}-\d{2}$/.test(trimmed) ? dateOnlyUtc.format(time) : dateOnly.format(time);
}

export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return "—";
  if (ms < 1000) return "under 1 s";
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds} s`;
  return `${Math.floor(seconds / 60)} min ${seconds % 60} s`;
}

export function excerpt(text: string, max = 140): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length > max ? `${flat.slice(0, max - 1).trimEnd()}…` : flat;
}

/** Only http(s) URLs may become links. */
export function safeHttpUrl(url: string): string | null {
  return /^https?:\/\//i.test(url.trim()) ? url.trim() : null;
}

export function plural(n: number, one: string, many = `${one}s`): string {
  return `${n} ${n === 1 ? one : many}`;
}
