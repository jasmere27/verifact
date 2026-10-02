import { useId, useRef, useState } from "react";
import type { DragEvent, FormEvent, KeyboardEvent } from "react";

export type Mode = "text" | "image" | "audio";

export type Submission = { mode: "text"; text: string } | { mode: "image" | "audio"; file: File };

const MAX_TEXT = 10_000;
const MAX_FILE_BYTES = 10 * 1024 * 1024;

const MODES: { id: Mode; label: string }[] = [
  { id: "text", label: "Text or link" },
  { id: "image", label: "Image" },
  { id: "audio", label: "Audio" },
];

const FILE_RULES = {
  image: {
    accept: "image/jpeg,image/png,image/gif,image/bmp,image/tiff",
    types: ["image/jpeg", "image/png", "image/gif", "image/bmp", "image/tiff"],
    extensions: /\.(jpe?g|png|gif|bmp|tiff?)$/i,
    prompt: "Choose an image",
    hint: "A screenshot or photo containing a claim. JPEG, PNG, GIF, BMP or TIFF, up to 10 MB. The image is sent to our AI provider to be read; it isn't stored.",
    typeError: "That file isn't a supported image. Use JPEG, PNG, GIF, BMP or TIFF.",
  },
  audio: {
    accept: "audio/wav,audio/x-wav,audio/wave,.wav",
    types: ["audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave"],
    extensions: /\.wav$/i,
    prompt: "Choose a WAV recording",
    hint: "English speech, under about a minute. WAV only, up to 10 MB.",
    typeError: "That file isn't a WAV recording.",
  },
} as const;

const LINK_ONLY = /^https?:\/\/\S+$/i;

const EXAMPLES = [
  "Drinking hot water kills the coronavirus",
  "The Great Wall of China is visible from space",
  "José Rizal was executed in 1896",
  "Humans only use 10% of their brains",
];

function formatBytes(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

interface Props {
  onSubmit: (submission: Submission) => void;
  /** Pre-filled text, e.g. shared from another app. */
  initialText?: string;
  /** Starting tab, e.g. the Home "Screenshot" shortcut. */
  initialMode?: Mode;
}

export default function CheckForm({ onSubmit, initialText, initialMode }: Props) {
  const [mode, setMode] = useState<Mode>(initialMode ?? "text");
  const [text, setText] = useState(initialText ?? "");
  const [files, setFiles] = useState<Record<"image" | "audio", File | null>>({ image: null, audio: null });
  const [problem, setProblem] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const baseId = useId();

  const tabId = (m: Mode) => `${baseId}-tab-${m}`;
  const panelId = `${baseId}-panel`;
  const hintId = `${baseId}-hint`;
  const problemId = `${baseId}-problem`;

  function selectMode(next: Mode) {
    setMode(next);
    setProblem(null);
  }

  function onTabKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    let next: number | null = null;
    if (event.key === "ArrowRight") next = (index + 1) % MODES.length;
    else if (event.key === "ArrowLeft") next = (index - 1 + MODES.length) % MODES.length;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = MODES.length - 1;
    if (next === null) return;
    event.preventDefault();
    selectMode(MODES[next].id);
    tabRefs.current[next]?.focus();
  }

  function acceptFile(kind: "image" | "audio", file: File | null) {
    setProblem(null);
    if (!file) {
      setFiles((prev) => ({ ...prev, [kind]: null }));
      return;
    }
    const rules = FILE_RULES[kind];
    const typeOk = (rules.types as readonly string[]).includes(file.type) || rules.extensions.test(file.name);
    if (!typeOk) {
      setProblem(rules.typeError);
      return;
    }
    if (file.size > MAX_FILE_BYTES) {
      setProblem(`That file is ${formatBytes(file.size)}. The limit is 10 MB.`);
      return;
    }
    setFiles((prev) => ({ ...prev, [kind]: file }));
  }

  function handleDrop(event: DragEvent<HTMLLabelElement>, kind: "image" | "audio") {
    event.preventDefault();
    setDragging(false);
    acceptFile(kind, event.dataTransfer.files?.[0] ?? null);
  }

  function handleSubmit(event?: FormEvent) {
    event?.preventDefault();
    if (mode === "text") {
      const trimmed = text.trim();
      if (!trimmed) {
        setProblem("Paste a claim, some article text, or a link to check.");
        return;
      }
      if (trimmed.length > MAX_TEXT) {
        setProblem(`That's ${trimmed.length.toLocaleString()} characters. The limit is ${MAX_TEXT.toLocaleString()}.`);
        return;
      }
      setProblem(null);
      onSubmit({ mode: "text", text: trimmed });
      return;
    }
    const file = files[mode];
    if (!file) {
      setProblem(mode === "image" ? "Choose an image to check." : "Choose a WAV recording to check.");
      return;
    }
    setProblem(null);
    onSubmit({ mode, file });
  }

  function tryExample(example: string) {
    setMode("text");
    setText(example);
    setProblem(null);
    onSubmit({ mode: "text", text: example });
  }

  const trimmedLength = text.trim().length;
  const isLink = mode === "text" && LINK_ONLY.test(text.trim());
  const describedBy = [hintId, problem ? problemId : null].filter(Boolean).join(" ");

  return (
    <form className="check-form" onSubmit={handleSubmit} noValidate aria-label="Check a claim">
      <div className="mode-tabs" role="tablist" aria-label="What are you checking?">
        {MODES.map((m, index) => (
          <button
            key={m.id}
            ref={(el) => {
              tabRefs.current[index] = el;
            }}
            id={tabId(m.id)}
            type="button"
            role="tab"
            aria-selected={mode === m.id}
            aria-controls={panelId}
            tabIndex={mode === m.id ? 0 : -1}
            className="mode-tab"
            onClick={() => selectMode(m.id)}
            onKeyDown={(e) => onTabKeyDown(e, index)}
          >
            {m.label}
          </button>
        ))}
      </div>

      <div className="mode-panel" id={panelId} role="tabpanel" aria-labelledby={tabId(mode)}>
        {mode === "text" ? (
          <>
            <label className="visually-hidden" htmlFor={`${baseId}-text`}>
              Claim, article text, or link
            </label>
            <textarea
              id={`${baseId}-text`}
              className="text-input"
              placeholder="Paste a post, a claim, or a link…"
              value={text}
              rows={6}
              aria-describedby={describedBy}
              aria-invalid={problem ? true : undefined}
              onChange={(e) => {
                setText(e.target.value);
                if (problem) setProblem(null);
              }}
              onKeyDown={(e) => {
                if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) handleSubmit();
              }}
            />
            <div className="input-foot">
              <p id={hintId} className="hint">
                {isLink
                  ? "We'll read the article at this link and check its main claims."
                  : "A single claim works best. Longer text is fine; we'll pick out the checkable claims."}
              </p>
              <span className={trimmedLength > MAX_TEXT ? "counter counter--over" : "counter"}>
                {trimmedLength.toLocaleString()} / {MAX_TEXT.toLocaleString()}
              </span>
            </div>
          </>
        ) : (
          <>
            <label
              className={dragging ? "file-drop file-drop--active" : "file-drop"}
              onDragOver={(e) => {
                e.preventDefault();
                setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={(e) => handleDrop(e, mode)}
            >
              <input
                key={mode}
                type="file"
                className="file-input"
                accept={FILE_RULES[mode].accept}
                aria-describedby={describedBy}
                aria-invalid={problem ? true : undefined}
                onChange={(e) => acceptFile(mode, e.target.files?.[0] ?? null)}
              />
              {files[mode] ? (
                <span className="file-chosen">
                  <span className="file-name">{files[mode]?.name}</span>
                  <span className="file-size">
                    {formatBytes(files[mode]?.size ?? 0)} · <span className="file-change">Choose a different file</span>
                  </span>
                </span>
              ) : (
                <span className="file-empty">
                  <span className="file-prompt">{FILE_RULES[mode].prompt}</span>
                  <span className="file-or">or drop it here</span>
                </span>
              )}
            </label>
            <p id={hintId} className="hint">
              {FILE_RULES[mode].hint}
            </p>
          </>
        )}
      </div>

      {problem && (
        <p id={problemId} className="field-problem" role="alert">
          {problem}
        </p>
      )}

      <div className="form-actions">
        <button type="submit" className="button button--primary">
          Check the evidence
        </button>
        <span className="form-note">Usually takes 10–40 seconds.</span>
      </div>

      <div className="examples">
        <p className="examples-label" id={`${baseId}-examples`}>
          Or try an example
        </p>
        <ul className="example-list" aria-labelledby={`${baseId}-examples`}>
          {EXAMPLES.map((example) => (
            <li key={example}>
              <button type="button" className="example-chip" onClick={() => tryExample(example)}>
                {example}
              </button>
            </li>
          ))}
        </ul>
      </div>
    </form>
  );
}
