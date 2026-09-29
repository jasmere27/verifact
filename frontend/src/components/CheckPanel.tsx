import { useState } from "react";
import type { FormEvent } from "react";
import { checkAudio, checkImage, checkText } from "../api";
import { parseVerdict } from "../parseResponse";
import ResultCard from "./ResultCard";
import VerdictGauge from "./VerdictGauge";

type Mode = "text" | "image" | "audio";

const MODE_TAGS: Record<Mode, string> = {
  text: "TXT · URL",
  image: "IMG",
  audio: "AUD",
};

const MODE_LABELS: Record<Mode, string> = {
  text: "Text or link",
  image: "Image",
  audio: "Audio",
};

export default function CheckPanel() {
  const [mode, setMode] = useState<Mode>("text");
  const [newsText, setNewsText] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<string | null>(null);

  function switchMode(next: Mode) {
    setMode(next);
    setFile(null);
    setError(null);
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setResult(null);
    setLoading(true);

    try {
      let response: string;
      if (mode === "text") {
        if (!newsText.trim()) {
          throw new Error("No signal to read — paste a claim or link first.");
        }
        response = await checkText(newsText.trim());
      } else if (mode === "image") {
        if (!file) {
          throw new Error("No sample loaded — choose an image to scan.");
        }
        response = await checkImage(file);
      } else {
        if (!file) {
          throw new Error("No sample loaded — choose an audio clip to scan.");
        }
        response = await checkAudio(file);
      }
      setResult(response);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong.");
    } finally {
      setLoading(false);
    }
  }

  const gaugeState = loading ? "reading" : result ? "settled" : "idle";
  const verdict = result ? parseVerdict(result) : { classification: null, confidenceScore: null };

  return (
    <div className="console">
      <VerdictGauge state={gaugeState} verdict={verdict} />

      <div className="panel">
        <span className="eyebrow">Sample intake</span>

        <div className="mode-tabs" role="tablist">
          {(Object.keys(MODE_LABELS) as Mode[]).map((m) => (
            <button
              key={m}
              type="button"
              role="tab"
              aria-selected={mode === m}
              className={mode === m ? "mode-tab active" : "mode-tab"}
              onClick={() => switchMode(m)}
            >
              <span className="mode-tag">{MODE_TAGS[m]}</span>
              {MODE_LABELS[m]}
            </button>
          ))}
        </div>

        <form onSubmit={handleSubmit} className="check-form">
          {mode === "text" && (
            <textarea
              placeholder="Paste a claim, an article, or a link…"
              value={newsText}
              onChange={(e) => setNewsText(e.target.value)}
              rows={6}
            />
          )}
          {mode === "image" && (
            <label className="drop-slot">
              <input
                type="file"
                accept="image/jpeg,image/png,image/gif,image/bmp,image/tiff"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
              {file ? file.name : "Choose an image to scan"}
            </label>
          )}
          {mode === "audio" && (
            <label className="drop-slot">
              <input
                type="file"
                accept="audio/wav,audio/x-wav"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
              {file ? file.name : "Choose an audio clip to scan"}
            </label>
          )}

          <button type="submit" disabled={loading} className="submit-button">
            {loading ? "Reading…" : "Run analysis"}
          </button>
        </form>

        {error && <p className="error-message">{error}</p>}
      </div>

      {result && <ResultCard response={result} />}
    </div>
  );
}
