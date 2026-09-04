import { useState } from "react";
import type { FormEvent } from "react";
import { checkAudio, checkImage, checkText } from "../api";
import ResultCard from "./ResultCard";

type Mode = "text" | "image" | "audio";

const MODE_LABELS: Record<Mode, string> = {
  text: "Text / URL",
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
          throw new Error("Enter some text or a URL to check.");
        }
        response = await checkText(newsText.trim());
      } else if (mode === "image") {
        if (!file) {
          throw new Error("Choose an image to check.");
        }
        response = await checkImage(file);
      } else {
        if (!file) {
          throw new Error("Choose an audio file to check.");
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

  return (
    <div className="panel">
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
            {MODE_LABELS[m]}
          </button>
        ))}
      </div>

      <form onSubmit={handleSubmit} className="check-form">
        {mode === "text" && (
          <textarea
            placeholder="Paste a claim, article text, or a URL…"
            value={newsText}
            onChange={(e) => setNewsText(e.target.value)}
            rows={6}
          />
        )}
        {mode === "image" && (
          <input
            type="file"
            accept="image/*"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
        )}
        {mode === "audio" && (
          <input
            type="file"
            accept="audio/*"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
        )}

        <button type="submit" disabled={loading} className="submit-button">
          {loading ? "Checking…" : "Check facts"}
        </button>
      </form>

      {error && <p className="error-message">{error}</p>}
      {result && <ResultCard response={result} />}
    </div>
  );
}
