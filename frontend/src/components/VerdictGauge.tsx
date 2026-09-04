import type { CSSProperties } from "react";
import { readVerdict } from "../verdict";
import type { Verdict } from "../verdict";

export type GaugeState = "idle" | "reading" | "settled";

interface VerdictGaugeProps {
  state: GaugeState;
  verdict: Verdict;
  compact?: boolean;
}

export default function VerdictGauge({ state, verdict, compact = false }: VerdictGaugeProps) {
  const reading = readVerdict(verdict);
  const position = state === "settled" ? reading.position : 50;
  const style = {
    "--pos": `${position}%`,
    "--needle-color": state === "settled" ? `var(${reading.colorVar})` : "var(--muted)",
  } as CSSProperties;

  if (compact) {
    return (
      <span className="gauge gauge--compact" data-state={state} style={style} aria-hidden="true">
        <span className="gauge-track">
          <span className="gauge-needle" />
        </span>
      </span>
    );
  }

  return (
    <div className="gauge" data-state={state} style={style}>
      <div className="gauge-poles">
        <span className="pole pole--false">FALSE</span>
        <span className="pole-center">—</span>
        <span className="pole pole--true">TRUE</span>
      </div>
      <div className="gauge-track">
        <span className="gauge-tick" style={{ left: "0%" }} />
        <span className="gauge-tick" style={{ left: "25%" }} />
        <span className="gauge-tick gauge-tick--center" style={{ left: "50%" }} />
        <span className="gauge-tick" style={{ left: "75%" }} />
        <span className="gauge-tick" style={{ left: "100%" }} />
        <span className="gauge-needle" />
      </div>
      <div className="gauge-readout">
        {state === "reading" && <span className="gauge-status">READING…</span>}
        {state === "idle" && <span className="gauge-status">AWAITING SAMPLE</span>}
        {state === "settled" && (
          <>
            <span className="gauge-verdict">{reading.label}</span>
            {verdict.confidenceScore !== null && (
              <span className="gauge-confidence">{verdict.confidenceScore}% confidence</span>
            )}
          </>
        )}
      </div>
    </div>
  );
}
