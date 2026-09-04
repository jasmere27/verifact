export type Verdict = {
  classification: string | null;
  confidenceScore: number | null;
};

export type VerdictReading = {
  /** 0 (FALSE pole) .. 100 (TRUE pole), 50 = center */
  position: number;
  colorVar: "--signal" | "--alarm" | "--brass" | "--muted";
  label: string;
  hollow: boolean;
};

export function readVerdict(verdict: Verdict): VerdictReading {
  const classification = verdict.classification?.toLowerCase() ?? null;
  const confidence = verdict.confidenceScore ?? 0;

  switch (classification) {
    case "real":
      return { position: 50 + confidence / 2, colorVar: "--signal", label: "TRUE", hollow: false };
    case "fake":
      return { position: 50 - confidence / 2, colorVar: "--alarm", label: "FALSE", hollow: false };
    case "mixed":
      return { position: 50, colorVar: "--brass", label: "MIXED SIGNAL", hollow: false };
    case "unverified":
      return { position: 50, colorVar: "--muted", label: "NO SIGNAL", hollow: true };
    default:
      return { position: 50, colorVar: "--muted", label: "NO SIGNAL", hollow: true };
  }
}
