import { useCallback, useEffect, useEffectEvent, useState } from "react";
import { AccountApiError } from "../auth/api";
import type { Assessment, Priority } from "./types";

/** Non-component helpers for the workspace UI: data loading, actions, formatting, labels. */

/* ---------------------------------------------------------------- data */

export interface Loaded<T> {
  data: T | null;
  error: string | null;
  status: number | null;
  loading: boolean;
  reload: () => void;
  setData: (data: T) => void;
}

interface Result<T> {
  data: T | null;
  error: string | null;
  status: number | null;
  /** Which request this result answers; loading is derived from it, so no state is set synchronously. */
  for: string;
}

/** Loads on mount and when {@code key} changes; keeps the previous data while reloading (no flicker). */
export function useLoad<T>(load: () => Promise<T>, key: string): Loaded<T> {
  const [tick, setTick] = useState(0);
  const [result, setResult] = useState<Result<T>>({ data: null, error: null, status: null, for: "" });
  const request = `${key}#${tick}`;
  const run = useEffectEvent(() => load());
  useEffect(() => {
    let live = true;
    run().then(
      (d) => {
        if (live) setResult({ data: d, error: null, status: null, for: request });
      },
      (e: unknown) => {
        if (live) setResult((prev) => ({ data: prev.data, error: message(e), status: e instanceof AccountApiError ? e.status : null, for: request }));
      },
    );
    return () => {
      live = false;
    };
  }, [request]);
  const reload = useCallback(() => setTick((t) => t + 1), []);
  const setData = useCallback((d: T) => setResult((prev) => ({ ...prev, data: d, error: null })), []);
  return { data: result.data, error: result.error, status: result.status, loading: result.for !== request, reload, setData };
}

/** Polls while {@code active}, e.g. while a run or investigation is in progress. */
export function usePoll(active: boolean, reload: () => void, ms = 3000) {
  useEffect(() => {
    if (!active) return;
    const t = setInterval(reload, ms);
    return () => clearInterval(t);
  }, [active, reload, ms]);
}

export function message(e: unknown): string {
  if (e instanceof Error) return e.message;
  return "Something went wrong. Please try again.";
}

/** Runs an action with busy state and a user-visible error. */
export function useAction() {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const run = useCallback(async <T,>(name: string, action: () => Promise<T>): Promise<T | undefined> => {
    setBusy(name);
    setError(null);
    try {
      return await action();
    } catch (e) {
      setError(message(e));
      return undefined;
    } finally {
      setBusy(null);
    }
  }, []);
  return { busy, error, setError, run };
}

/* ---------------------------------------------------------------- formatting */

const dateFmt = new Intl.DateTimeFormat(undefined, { day: "numeric", month: "short", year: "numeric" });
const dateTimeFmt = new Intl.DateTimeFormat(undefined, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });

export function fmtDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : dateFmt.format(d);
}

export function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : dateTimeFmt.format(d);
}

export function ago(iso: string | null | undefined): string {
  if (!iso) return "never";
  const s = (Date.now() - new Date(iso).getTime()) / 1000;
  if (Number.isNaN(s)) return iso;
  const future = s < 0;
  const a = Math.abs(s);
  const v = a < 60 ? "just now" : a < 3600 ? `${Math.round(a / 60)} min` : a < 86400 ? `${Math.round(a / 3600)} h` : `${Math.round(a / 86400)} d`;
  if (v === "just now") return v;
  return future ? `in ${v}` : `${v} ago`;
}

export function humanize(s: string | null | undefined): string {
  if (!s) return "";
  const t = s.replace(/_/g, " ").toLowerCase();
  return t.charAt(0).toUpperCase() + t.slice(1);
}

export function hostOf(url: string | null | undefined): string {
  if (!url) return "";
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return url;
  }
}

export const PRIORITY_TONE: Record<Priority, Tone> = { CRITICAL: "critical", HIGH: "bad", MEDIUM: "warn", LOW: "neutral" };

export type Tone = "ok" | "warn" | "bad" | "info" | "accent" | "critical" | "neutral";

export const ASSESSMENT_LABEL: Record<Assessment, string> = {
  SUPPORTED: "Supported by available evidence",
  CONTRADICTED: "Contradicted by available evidence",
  MIXED: "Mixed evidence",
  INSUFFICIENT_EVIDENCE: "Insufficient evidence",
  MISSING_CONTEXT: "Outdated or missing context",
  UNDER_INVESTIGATION: "Under investigation",
};

/** A short-lived confirmation, announced to screen readers. */
export function useToast() {
  const [text, setText] = useState<string | null>(null);
  useEffect(() => {
    if (!text) return;
    const t = setTimeout(() => setText(null), 4000);
    return () => clearTimeout(t);
  }, [text]);
  const node = (
    <div aria-live="polite" aria-atomic="true">
      {text && <div className="i-toast">{text}</div>}
    </div>
  );
  return { show: setText, node };
}
